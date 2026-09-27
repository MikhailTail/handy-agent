package dev.mikhailtail.handyagent

import android.webkit.WebView

/**
 * 环境自检：把决定界面能否正常渲染的事实一次性问出来。
 *
 * **为什么要做这个**：用户在手机上看到"弹窗透明、只剩黑字"，而同样的页面在
 * 开发机上完全正常 —— 这类差异无法靠读代码定位，只能问运行环境本身。
 * 与其反复猜（我已经猜错了好几轮），不如让 App 把事实报出来。
 *
 * 检测的都是前端产物实际用到的特性（见 cc-haha 的 globals.css）：
 * 任一不支持都会造成**整片样式失效**，且症状各不相同、都很难凭肉眼归因。
 */
object EnvProbe {

    /**
     * 在页面里跑一段自检并把结果回传。
     *
     * 用 `evaluateJavascript` 而不是注入脚本：这里只需要读能力，不需要改页面。
     */
    fun run(webView: WebView, onResult: (String) -> Unit) {
        webView.evaluateJavascript(SCRIPT) { raw ->
            // evaluateJavascript 回传的是 JSON 编码的字符串，去掉两端引号与转义。
            val text = raw?.trim()?.removeSurrounding("\"")
                ?.replace("\\n", "\n")
                ?.replace("\\\"", "\"")
                ?.replace("\\\\", "\\")
                ?: "（无结果）"
            onResult(text)
        }
    }

    /**
     * 报告是否表明界面有问题。
     *
     * 判据取自"会导致界面透明/错位"的那几项 —— **正常时不弹，免得打扰**；
     * 有任一项缺失才展示，此时它是有用的诊断而非噪音。
     */
    fun looksBroken(report: String): Boolean {
        if (report.isBlank() || report.startsWith("（")) return false
        // 变量缺失 → 所有 var() 失效 → 背景透明、只剩黑字
        if (report.contains("[缺] --cc-bg")) return true
        if (report.contains("[缺] --color-surface")) return true
        // 样式表没加载或规则为空 → 整片样式失效
        if (Regex("""可读取规则: 0 条""").containsMatchIn(report)) return true
        // body 背景全透明 = 没有背景色生效
        if (Regex("""body 背景 = rgba\(0,\s*0,\s*0,\s*0\)""").containsMatchIn(report)) return true
        return false
    }

    private const val SCRIPT = """
        (function () {
          var out = [];
          var ua = navigator.userAgent || '';
          var m = ua.match(/Chrome\/(\d+)/);
          out.push('WebView 版本: ' + (m ? m[1] : '未知') + '  (' + ua.slice(0, 120) + ')');
          out.push('');

          function probe(label, prop, value) {
            var ok = false;
            try { ok = CSS.supports(prop, value); } catch (e) { ok = false; }
            out.push((ok ? '[有] ' : '[缺] ') + label);
          }
          out.push('== CSS 特性 ==');
          probe('color-mix()   (需 111)', 'color', 'color-mix(in srgb, red, blue)');
          probe('dvh           (需 108)', 'height', '100dvh');
          probe('@container    (需 105)', 'container-type', 'inline-size');
          probe(':has()        (需 105)', 'color', 'red');
          probe('oklab 颜色     (需 111)', 'color', 'oklab(0.5 0 0)');
          probe('aspect-ratio  (需 88)',  'aspect-ratio', '1 / 1');
          probe('gap 布局       (需 84)',  'gap', '1px');
          out.push('');

          out.push('== 主题变量是否生效 ==');
          try {
            var cs = getComputedStyle(document.documentElement);
            var keys = ['--cc-bg', '--color-surface', '--color-background', '--color-text-primary', '--color-border'];
            for (var i = 0; i < keys.length; i++) {
              var v = cs.getPropertyValue(keys[i]).trim();
              out.push((v ? '[有] ' : '[缺] ') + keys[i] + (v ? ' = ' + v : ''));
            }
          } catch (e) { out.push('读取变量失败: ' + e.message); }
          out.push('');

          out.push('== 实际生效的样式 ==');
          try {
            out.push('html[data-theme] = ' + (document.documentElement.getAttribute('data-theme') || '(未设置)'));
            var body = getComputedStyle(document.body);
            out.push('body 背景 = ' + (body.backgroundColor || '(空)'));
            out.push('body 字色 = ' + (body.color || '(空)'));
            var sheets = document.styleSheets ? document.styleSheets.length : 0;
            out.push('已加载样式表: ' + sheets + ' 个');
            var rules = 0;
            for (var s = 0; s < sheets; s++) {
              try { rules += (document.styleSheets[s].cssRules || []).length; } catch (e) {}
            }
            out.push('可读取规则: ' + rules + ' 条');
          } catch (e) { out.push('样式读取失败: ' + e.message); }
          return out.join('\n');
        })();
    """
}
