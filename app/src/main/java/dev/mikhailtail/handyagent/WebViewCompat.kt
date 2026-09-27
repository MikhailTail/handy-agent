package dev.mikhailtail.handyagent

import android.content.Context
import android.os.Build
import android.util.Log
import android.webkit.WebView

/**
 * 老 WebView 的样式兜底。
 *
 * **问题**：cc-haha 的前端产物大量使用较新的 CSS 特性 —— 实测
 * `color-mix()` 62 处、`dvh` 10 处、`@container` 3 处。它们的浏览器要求分别是
 * Chrome 111+ / 108+ / 105+。而 **Android 的 WebView 是独立于系统的可更新组件**，
 * 出厂预装的版本可能远低于此。
 *
 * 症状很有辨识度：**布局高度塌陷**。因为 `app-shell` 的高度写的是
 * `min-height: var(--touch-h5-viewport-height, 100dvh)` —— 浏览器不认 `dvh`
 * 时这条声明整条失效，弹窗与抽屉就变成一条"很细的白框"，看得见但点不着。
 *
 * **为什么在宿主侧修而不是改前端产物**：前端要保持与上游一致（原版 dist 是
 * 我们复用它的前提）；而这本质是**宿主环境过旧**的问题，适配责任在宿主。
 */
object WebViewCompat {

    /**
     * 把 `dvh` / `svh` 系单位降级成 `vh`。
     *
     * 用 `@supports` 检测而不是按版本号判断：万一将来某些 ROM 的版本号与实际
     * 能力不符，以能力为准更可靠。
     */
    private const val CSS_FALLBACK = """
        (function () {
          if (!document.getElementById('handy-dvh-fallback')) {
            var s = document.createElement('style');
            s.id = 'handy-dvh-fallback';
            s.textContent = [
              '@supports not (height: 100dvh) {',
              '  html, body { height: 100vh !important; min-height: 100vh !important; }',
              '  .app-shell { min-height: 100vh !important; height: 100vh !important; }',
              '  /* Tailwind 生成的长度类：把 dvh 全量顶成 vh */',
              '  [class*="dvh"] { min-height: 100vh; }',
              '}'
            ].join('\n');
            (document.head || document.documentElement).appendChild(s);
          }
          // 弹层高度塌陷时给它一个可视下限，至少让人能点到。
          if (!document.getElementById('handy-min-height-fallback')) {
            var m = document.createElement('style');
            m.id = 'handy-min-height-fallback';
            m.textContent = '[role="dialog"], [role="menu"], [role="listbox"] { min-height: 120px; }';
            (document.head || document.documentElement).appendChild(m);
          }
        })();
    """

    /** 页面每次开始加载时注入 —— 必须早于样式计算，否则会先闪一下塌陷的布局。 */
    fun install(webView: WebView) {
        webView.evaluateJavascript(CSS_FALLBACK, null)
    }

    /**
     * WebView 的版本号，用于诊断。
     *
     * 取主版本号即可：上述特性要求的 105/108/111 都是主版本。
     */
    fun version(): String = runCatching {
        val pkg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WebView.getCurrentWebViewPackage()
        } else {
            null
        }
        pkg?.versionName ?: "未知"
    }.getOrElse { "未知" }

    fun majorVersion(): Int =
        version().substringBefore('.').toIntOrNull() ?: 0

    /**
     * 版本是否足以支撑前端样式。
     *
     * 低于 111 时 `color-mix()` 不生效（颜色可能异常但布局不塌），
     * 低于 108 则 `dvh` 失效、**布局会塌**。这里以 108 为可用线。
     */
    fun isAdequate(): Boolean = majorVersion() >= MIN_MAJOR

    private const val MIN_MAJOR = 108

    fun log(context: Context) {
        val v = version()
        val ok = isAdequate()
        Log.i("WebViewCompat", "WebView 版本=$v 满足要求=$ok (需要 $MIN_MAJOR+)")
    }
}
