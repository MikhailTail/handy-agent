package dev.mikhailtail.handyagent.mobile

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 把无障碍节点树压平成带序号的清单。
 *
 * 模型不该去解析一棵嵌套的 XML —— 它需要的是"第 12 号是个叫『发送』的按钮"。
 * 压平之后按序号操作，比让模型自己数层级可靠得多。
 *
 * 输出形态（每行一个节点）：
 * ```
 * [0] FrameLayout
 * [1] EditText "搜索" [可输入] bounds=[36,220][1044,316]
 * [2] Button "发送" [可点击] bounds=[880,1820][1044,1916]
 * ```
 *
 * **只输出模型用得上的属性**。节点有几十个属性，全列出来会淹没关键信息；
 * 这里只给：类名、文本、内容描述、以及几个决定"能不能操作"的标志。
 */
object UiTreeFlattener {

    /** 一次压平最多输出这么多节点 —— 长列表页可能有几百项，全给会撑爆上下文。 */
    const val MAX_NODES = 300

    private const val MAX_DEPTH = 40

    /**
     * 压平结果。
     *
     * [nodes] 与文本行一一对应，**序号就是列表下标** —— 工具拿到 index 后
     * 直接查这张表，不需要再遍历树（也就不会因为两次遍历间的界面变化而错位）。
     */
    data class Flattened(
        val text: String,
        val nodes: List<AccessibilityNodeInfo>,
        val truncated: Boolean,
    )

    fun flatten(root: AccessibilityNodeInfo?): Flattened {
        if (root == null) return Flattened("（界面为空）", emptyList(), truncated = false)

        val nodes = mutableListOf<AccessibilityNodeInfo>()
        val lines = mutableListOf<String>()
        var truncated = false

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (truncated) return
            if (nodes.size >= MAX_NODES) {
                truncated = true
                return
            }
            if (depth > MAX_DEPTH) return

            // 只收录"看得见"的节点。不可见的节点模型点不到，列出来只会干扰判断。
            if (node.isVisibleToUser) {
                nodes += node
                lines += render(node, nodes.lastIndex)
            }

            // 注意：**即使本节点被跳过（不可见），也要继续遍历子树**。
            // 很多容器自己不绘制但子节点可见，提前 return 会让整棵子树从模型视野里消失
            // —— 真机上表现为"屏幕上明明有按钮却看不见"。
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child, depth + 1)
                if (truncated) return
            }
        }

        walk(root, 0)

        val text = buildString {
            append(lines.joinToString("\n"))
            if (truncated) {
                append("\n\n[已达 ${MAX_NODES} 个节点的上限，清单被截断。")
                append("请用滚动或更精确的操作缩小范围，不要凭这份不完整的清单下结论。]")
            }
        }
        return Flattened(text, nodes, truncated)
    }

    private fun render(node: AccessibilityNodeInfo, index: Int): String = buildString {
        append('[').append(index).append("] ")
        append(node.className?.toString()?.substringAfterLast('.') ?: "View")

        node.text?.toString()?.takeIf { it.isNotBlank() }?.let {
            append(" \"").append(it.take(80)).append('"')
        }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let {
            append(" desc=\"").append(it.take(80)).append('"')
        }
        node.hintText?.toString()?.takeIf { it.isNotBlank() }?.let {
            append(" hint=\"").append(it.take(40)).append('"')
        }

        // 这几个标志决定模型能对它做什么，必须给。
        val flags = buildList {
            if (node.isClickable) add("可点击")
            if (node.isEditable) add("可输入")
            if (node.isScrollable) add("可滚动")
            if (node.isCheckable) add("可勾选")
            if (!node.isEnabled) add("已禁用")
            if (node.isSelected) add("已选中")
        }
        if (flags.isNotEmpty()) append(" [").append(flags.joinToString(" ")).append(']')

        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        append(" bounds=[").append(rect.left).append(',').append(rect.top)
        append("][").append(rect.right).append(',').append(rect.bottom).append(']')
    }
}
