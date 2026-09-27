package dev.mikhailtail.handyagent

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 把打包进 assets 的前端产物铺到真实文件系统。
 *
 * **为什么必须铺**：Ktor 的 `staticFiles` 需要一个 `File` 目录，而 Android 的 assets
 * 只能经 AssetManager 流式读取，永远不是文件。所以首次启动解压一次。
 *
 * **为什么带版本标记**：产物有 500 多个文件，每次启动都重铺会让冷启动明显变慢。
 * 用 `BuildConfig.VERSION_NAME` 当标记 —— 只要 App 升级过就重铺一次，覆盖掉旧版本
 * 残留的文件，避免旧 asset 与新 index.html 混用导致的诡异问题。
 */
internal object H5Assets {

    private const val TAG = "H5Assets"
    private const val STAMP_FILE = ".extracted-version"

    fun ensureExtracted(context: Context, assetDir: String, target: File, version: String) {
        val stamp = File(target, STAMP_FILE)
        if (stamp.isFile && stamp.readText().trim() == version) {
            Log.i(TAG, "already extracted for $version, skipping")
            return
        }

        if (target.exists()) target.deleteRecursively()
        target.mkdirs()
        copyDir(context, assetDir, target)
        stamp.writeText(version)
        Log.i(TAG, "extracted $assetDir -> $target for $version")
    }

    /**
     * AssetManager 不提供"这是目录还是文件"的查询，只能靠 `list()` 区分：
     * 有子项就是目录，返回空数组就是文件。
     */
    private fun copyDir(context: Context, assetPath: String, target: File) {
        val assets = context.assets
        val children = assets.list(assetPath).orEmpty()

        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }

        target.mkdirs()
        for (child in children) {
            copyDir(context, "$assetPath/$child", File(target, child))
        }
    }
}
