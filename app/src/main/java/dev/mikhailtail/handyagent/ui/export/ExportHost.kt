package dev.mikhailtail.handyagent.ui.export

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.mikhailtail.handyagent.platform.AndroidExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 导出的 UI 入口：把 [ExportPlan] 变成一次真实的系统交互。
 *
 * 做成 `@Stable` 的小对象、由根页面统一持有，而不是「每个页面各自 remember 一套 launcher」：
 * ActivityResult 的注册是**全局按 key** 的，页面切换时若各自重建 launcher，正在等用户
 * 挑保存位置的这次导出会被注销掉。挂在 [dev.mikhailtail.handyagent.ui.RootScaffold] 上则切页不丢 ——
 * 与审批卡同一套理由。
 */
@Stable
class ExportHost internal constructor(
    private val onSaveAs: (ExportPlan) -> Unit,
    private val onShare: (ExportPlan) -> Unit,
) {
    /** 弹系统「另存为」，用户可挑 Download / Documents / SD 卡 / 云盘。 */
    fun saveAs(plan: ExportPlan) = onSaveAs(plan)

    /** 走系统分享面板。 */
    fun share(plan: ExportPlan) = onShare(plan)
}

/**
 * 建一个挂在当前 Activity 上的 [ExportHost]。
 *
 * 「另存为」要挑 MIME（见 [Exporter.saveAsMime]），而 `CreateDocument` 的 MIME 是**构造参数**，
 * 所以流程拆成两步：先把 plan 与 MIME 一起写进状态，等**重组把新契约注册好**之后，
 * 再由 [LaunchedEffect] 真正 `launch()` —— 否则会拿着上一个 plan 的 MIME 去挑文件，
 * 表现为「存 .zip 时系统却提示存 .txt」。
 */
@Composable
fun rememberExportHost(): ExportHost {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 待启动 / 进行中分开存：queued 驱动 launch，inFlight 供结果回调取回 plan。
    var queued by remember { mutableStateOf<ExportPlan?>(null) }
    val inFlight = remember { mutableStateOf<ExportPlan?>(null) }
    var queuedShare by remember { mutableStateOf<ExportPlan?>(null) }
    var saveMime by remember { mutableStateOf(Exporter.OCTET_STREAM) }

    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(saveMime),
    ) { uri ->
        val plan = inFlight.value
        inFlight.value = null
        // uri == null：用户自己取消了，不该弹「失败」。
        if (uri != null && plan != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    AndroidExporter.writeTo(context, uri, plan)
                }
                toast(context, result.describe())
            }
        }
    }

    val sharer = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { /* 分享无返回语义：目标 App 收下即结束，不打扰用户 */ }

    LaunchedEffect(queued) {
        val plan = queued ?: return@LaunchedEffect
        queued = null
        saver.launch(plan.suggestedName)
    }

    LaunchedEffect(queuedShare) {
        val plan = queuedShare ?: return@LaunchedEffect
        queuedShare = null
        val staged = withContext(Dispatchers.IO) { AndroidExporter.stage(context, plan) }
        if (staged == null) {
            toast(context, "分享失败：无法写入缓存")
            return@LaunchedEffect
        }
        val uri = AndroidExporter.sharedUri(context, staged.file)
        sharer.launch(Intent.createChooser(AndroidExporter.shareIntent(plan, uri), "导出到…"))
    }

    return remember(saver, sharer) {
        ExportHost(
            onSaveAs = { plan ->
                saveMime = AndroidExporter.saveAsMime(plan)
                inFlight.value = plan
                queued = plan
            },
            onShare = { plan -> queuedShare = plan },
        )
    }
}

/** 写失败一定要说出来 —— 静默失败会让用户以为「文件根本没生成」。 */
private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
