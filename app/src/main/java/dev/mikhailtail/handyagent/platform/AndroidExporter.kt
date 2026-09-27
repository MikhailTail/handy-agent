package dev.mikhailtail.handyagent.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dev.mikhailtail.handyagent.ui.export.ExportPlan
import dev.mikhailtail.handyagent.ui.export.ExportResult
import dev.mikhailtail.handyagent.ui.export.Exporter
import dev.mikhailtail.handyagent.ui.export.ExportStaging
import dev.mikhailtail.handyagent.ui.export.StagedExport
import java.io.File

/**
 * 导出的 Android 出口。所有平台调用都收在这一个文件里，[dev.mikhailtail.handyagent.ui.export.Exporter]
 * 保持零 `android.*` 依赖，从而可以在宿主 JVM 上全量单测。
 *
 * 两条出口：
 * 1. **另存为** —— UI 用 SAF 拿到 `content://` 目标 URI，这里写进去。用户可存到 Download、
 *    Documents、SD 卡、云盘盘符，因而「产物看不见」的问题从根上解决。
 * 2. **分享** —— 先 [stage] 到 `cacheDir/exports/`，再用 FileProvider 授权给目标 App
 *    （见 `res/xml/file_paths.xml`：只暴露 exports/ 一个目录）。
 *
 * 二者共用 [Exporter.write]，因此「另存为」与「分享」得到的内容必然一致。
 */
object AndroidExporter {

    private const val AUTHORITY_SUFFIX = ".fileprovider"

    /** 分享中转目录；必须是 [file_paths.xml] 里声明的那一个。 */
    fun exportsDir(context: Context): File = File(context.cacheDir, "exports")

    fun authority(context: Context): String = context.packageName + AUTHORITY_SUFFIX

    /**
     * 供 UI 决定「另存为」用哪种 MIME：保证 DocumentsUI 不会给文件名再补一个扩展名，
     * 详见 [Exporter.saveAsMime]。
     */
    fun saveAsMime(plan: ExportPlan): String = Exporter.saveAsMime(plan.suggestedName)

    /**
     * SAF 目标流是**写失败即丢弃**的：`use` 正常关闭才会提交成文档，
     * 中途抛异常时系统不会留下一个残缺文件。这里如实把错误交给调用方提示。
     */
    fun writeTo(context: Context, uri: Uri, plan: ExportPlan): ExportResult = try {
        val out = context.contentResolver.openOutputStream(uri)
        if (out == null) {
            ExportResult(error = "无法打开目标位置")
        } else {
            out.use { Exporter.write(plan, it) }
        }
    } catch (e: Exception) {
        ExportResult(error = e.message ?: "写入失败")
    }

    /** 把内容定稿到缓存并返回落盘结果；失败返回 null（调用方只提示）。 */
    fun stage(context: Context, plan: ExportPlan): StagedExport? =
        ExportStaging.stageInto(exportsDir(context), plan)

    /** 把缓存里的文件授权成一个可给外部 App 读的 `content://` URI。 */
    fun sharedUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, authority(context), file)

    /**
     * 分享意图。必须带上 [Intent.FLAG_GRANT_READ_URI_PERMISSION] ——
     * 否则接收方（另一个进程）会因为没有读该 URI 的权限而拿到一个空文件。
     */
    fun shareIntent(plan: ExportPlan, uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
        type = plan.mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, plan.suggestedName)
        // 部分机型（尤其国产 ROM）只认 ClipData 里的授权，EXTRA_STREAM 之外再挂一份。
        clipData = android.content.ClipData.newRawUri(plan.label, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
