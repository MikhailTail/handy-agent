package dev.mikhailtail.handyagent.core.mobile

/** 节点的语义角色，让模型一眼看出这是什么控件。 */
enum class NodeRole { BUTTON, EDIT, TEXT, IMAGE, CHECKBOX, SWITCH, TAB, LIST, SCROLL, CONTAINER, UNKNOWN }

enum class NodeFlag { CLICKABLE, LONG_CLICKABLE, EDITABLE, SCROLLABLE, CHECKABLE, CHECKED, SELECTED, PASSWORD, DISABLED }

/**
 * 扁平化后的一项。
 *
 * **[index] 只在本次 dump 内有效** —— 界面一变就作废。模型必须"先看树、再按序号操作"，
 * 绝不能跨步骤复用序号（[ActionResolver] 会用 dumpId 强制这一点）。
 */
data class FlatNode(
    val index: Int,
    /** children 已在扁平化时展开，这里始终为空，避免快照里嵌套重复整棵树。 */
    val node: UiNode,
    val role: NodeRole,
    val label: String,
    val flags: Set<NodeFlag>,
) {
    val bounds: RectI get() = node.bounds
    val editable: Boolean get() = node.editable
}

/** 一次屏幕快照的扁平结果。 */
data class FlatTree(
    val dumpId: String,
    val screen: ScreenInfo,
    val packageName: String?,
    val nodes: List<FlatNode>,
    /** 因超出上限被丢弃了节点 —— 模型据此知道"看到的不是全部"。 */
    val truncated: Boolean = false,
) {
    fun byIndex(index: Int): FlatNode? = nodes.firstOrNull { it.index == index }

    /** 给模型看的紧凑文本。头部带 dump id，正文一行一个可操作节点。 */
    val text: String by lazy { render() }

    private fun render(): String = buildString {
        append("[ui_tree dump=").append(dumpId)
        append(" pkg=").append(packageName ?: "?")
        append(" screen=").append(screen.width).append('x').append(screen.height)
        append(" rot=").append(screen.rotation.name)
        if (truncated) append(" TRUNCATED")
        append("]\n")
        if (nodes.isEmpty()) {
            append("(没有可操作的控件；可能是游戏/画布类界面，可考虑截图)")
            return@buildString
        }
        for (n in nodes) {
            append('[').append(n.index).append("] ").append(n.role.name)
            n.node.viewId?.takeIf { it.isNotBlank() }?.let { append(" id=").append(it) }
            if (n.label.isNotBlank()) append(" text=\"").append(n.label).append('"')
            append(" bounds=").append(n.bounds)
            if (n.flags.isNotEmpty()) {
                append(" flags=").append(n.flags.joinToString(",") { it.name.lowercase() })
            }
            append('\n')
        }
    }
}

data class FlattenOptions(
    val maxNodes: Int = 200,
    val labelMax: Int = 120,
    val includeInvisible: Boolean = false,
    /** 连纯展示型文本也带上（默认只带可交互或有标签的）。 */
    val includeNonInteractive: Boolean = false,
)

/**
 * 把控件树压平成「带序号的一维清单」。
 *
 * 为什么这么做（而不是把整棵树丢给模型）：
 * 1. token —— 真实的 Android 树动辄上千节点，压平后只剩几十行；
 * 2. 可执行 —— 模型只要回一个序号，执行侧就能回查节点拿到准确坐标，不必让模型算坐标；
 * 3. 可解释 —— 审批弹窗里能原样展示「你要点的是第 12 项：发送按钮」。
 */
object UiTreeFlattener {

    fun flatten(
        root: UiNode,
        screen: ScreenInfo,
        packageName: String? = null,
        dumpId: String,
        options: FlattenOptions = FlattenOptions(),
    ): FlatTree {
        val collected = ArrayList<UiNode>(64)
        collect(root, options, collected)

        val deduped = dedupeByBounds(collected)
        val capped = capTo(deduped, options.maxNodes)

        val flat = capped.nodes.mapIndexed { i, node ->
            FlatNode(
                index = i + 1,
                node = node,
                role = roleOf(node),
                label = labelOf(node, options.labelMax),
                flags = flagsOf(node),
            )
        }
        return FlatTree(
            dumpId = dumpId,
            screen = screen,
            packageName = packageName,
            nodes = flat,
            truncated = capped.truncated,
        )
    }

    // ------------------------------------------------------------------ 收集

    /**
     * DFS 先序收集"值得给模型看"的节点。
     *
     * 注意：**零面积的容器依然要往下走**。真实界面里常有一层测量为 0 的包装 View，
     * 但它的子节点是正常可见的 —— 早期版本在这里直接 return，会把整棵子树吞掉，
     * 表现为"模型看不见屏幕上的按钮"。
     */
    private fun collect(node: UiNode, options: FlattenOptions, out: MutableList<UiNode>) {
        if (!node.visibleToUser && !options.includeInvisible) return

        val usable = !node.bounds.isEmpty
        if (usable) {
            val keep = options.includeNonInteractive || node.meaningful
            if (keep) out.add(node.withoutChildren())
        }

        for (child in node.children) collect(child, options, out)
    }

    private fun UiNode.withoutChildren(): UiNode =
        if (children.isEmpty()) this else copy(children = emptyList())

    // ------------------------------------------------------------------ 去重

    /**
     * 同一块区域的父子包装层只保留一条。
     *
     * Android 里一个按钮常常是「FrameLayout > LinearLayout > TextView」三层同坐标，
     * 全报给模型既费 token 又容易点错层，取信息量最大的那条即可。
     */
    private fun dedupeByBounds(nodes: List<UiNode>): List<UiNode> {
        val best = LinkedHashMap<String, UiNode>()
        for (n in nodes) {
            val key = "${n.windowId}:${n.bounds}"
            val prev = best[key]
            best[key] = if (prev == null || weightOf(n) > weightOf(prev)) n else prev
        }
        // 保持 DFS 原序，否则模型看到的顺序会和界面视觉顺序脱节
        return nodes.filter { best["${it.windowId}:${it.bounds}"] === it }
    }

    /** 同坐标时谁更值得保留。 */
    private fun weightOf(n: UiNode): Int = when {
        n.editable -> 30
        n.clickable -> 20
        n.checkable -> 15
        n.hasLabel -> 10
        else -> 0
    }

    // ------------------------------------------------------------------ 截断

    private data class Capped(val nodes: List<UiNode>, val truncated: Boolean)

    /**
     * 超出上限时按重要性裁剪：输入框 > 可点击 > 有文字 > 其它。
     * 保留后再按 DFS 序还原，使清单顺序仍与界面一致。
     */
    private fun capTo(nodes: List<UiNode>, max: Int): Capped {
        if (max <= 0) return Capped(emptyList(), nodes.isNotEmpty())
        if (nodes.size <= max) return Capped(nodes, false)
        val kept = nodes.withIndex()
            .sortedByDescending { weightOf(it.value) }
            .take(max)
            .sortedBy { it.index }
            .map { it.value }
        return Capped(kept, true)
    }

    // ------------------------------------------------------------------ 渲染

    private fun roleOf(n: UiNode): NodeRole {
        val cls = n.className.lowercase()
        return when {
            n.editable || cls.contains("edittext") || cls.contains("textfield") -> NodeRole.EDIT
            cls.contains("switch") || cls.contains("toggle") -> NodeRole.SWITCH
            cls.contains("checkbox") || cls.contains("radiobutton") || cls.contains("checkable") -> NodeRole.CHECKBOX
            cls.contains("tab") -> NodeRole.TAB
            cls.contains("imageview") || cls.contains("imagebutton") -> NodeRole.IMAGE
            n.scrollable || cls.contains("recyclerview") || cls.contains("listview") -> NodeRole.SCROLL
            cls.contains("list") -> NodeRole.LIST
            n.clickable || cls.contains("button") -> NodeRole.BUTTON
            n.hasLabel -> NodeRole.TEXT
            else -> NodeRole.CONTAINER
        }
    }

    /** 标签优先取可见文字，其次无障碍描述，再次提示语。 */
    private fun labelOf(n: UiNode, max: Int): String {
        val raw = sequenceOf(n.text, n.contentDescription, n.hintText)
            .firstOrNull { !it.isNullOrBlank() }
            .orEmpty()
        return raw.replace('\n', ' ').trim().take(max)
    }

    private fun flagsOf(n: UiNode): Set<NodeFlag> = buildSet {
        if (n.clickable) add(NodeFlag.CLICKABLE)
        if (n.longClickable) add(NodeFlag.LONG_CLICKABLE)
        if (n.editable) add(NodeFlag.EDITABLE)
        if (n.scrollable) add(NodeFlag.SCROLLABLE)
        if (n.checkable) add(NodeFlag.CHECKABLE)
        if (n.checked) add(NodeFlag.CHECKED)
        if (n.selected) add(NodeFlag.SELECTED)
        if (n.password) add(NodeFlag.PASSWORD)
        if (!n.enabled) add(NodeFlag.DISABLED)
    }
}
