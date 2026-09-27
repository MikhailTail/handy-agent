package dev.mikhailtail.handyagent

import android.webkit.ConsoleMessage
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 收集 WebView 里的 JS 报错，供界面展示。
 *
 * **为什么需要它**：前端是 cc-haha 的原版产物，我们不能改；但它抛出的错误在手机上
 * 只表现为一句 toast（例如 `Cannot read properties of undefined (reading 'startsWith')`），
 * 既没有调用栈也没有文件名 —— 而在开发机上同样的操作一切正常，无从复现。
 *
 * 拿到**堆栈**才能定位：原版产物是打包压缩过的，但 bundle 里的函数名与行号仍然能指出
 * 是哪个模块出的问题。所以这里把 error 级消息连同 `sourceId:lineNumber` 一起留下来。
 */
object JsErrorReporter {

    private const val MAX_KEPT = 20

    private val errors = CopyOnWriteArrayList<String>()

    /** 供 WebChromeClient 调用。 */
    fun record(message: ConsoleMessage): Boolean {
        if (message.messageLevel() != ConsoleMessage.MessageLevel.ERROR) return false
        val location = buildString {
            val src = message.sourceId()
            if (!src.isNullOrBlank()) {
                append(src.substringAfterLast('/'))
                if (message.lineNumber() >= 0) append(':').append(message.lineNumber())
            }
        }
        val line = if (location.isEmpty()) message.message() else "${message.message()}  @$location"
        if (errors.size >= MAX_KEPT) errors.removeAt(0)
        errors += line
        return false
    }

    fun snapshot(): List<String> = errors.toList()

    fun clear() {
        errors.clear()
    }

    fun isEmpty(): Boolean = errors.isEmpty()
}
