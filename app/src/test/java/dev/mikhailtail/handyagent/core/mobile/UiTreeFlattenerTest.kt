package dev.mikhailtail.handyagent.core.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI 树扁平化。这是 mobile use 的"眼睛"：压得不好，模型要么看不见要点的东西，
 * 要么被上千个无用节点淹没。
 */
class UiTreeFlattenerTest {

    private val screen = ScreenInfo(1080, 2400, densityDpi = 420)

    private fun node(
        cls: String = "View",
        text: String? = null,
        id: String? = null,
        bounds: RectI = RectI(0, 0, 100, 100),
        clickable: Boolean = false,
        editable: Boolean = false,
        password: Boolean = false,
        visible: Boolean = true,
        children: List<UiNode> = emptyList(),
    ) = UiNode(
        className = cls,
        viewId = id,
        text = text,
        bounds = bounds,
        clickable = clickable,
        editable = editable,
        password = password,
        visibleToUser = visible,
        children = children,
    )

    @Test
    fun `interactive and labelled nodes get sequential indices`() {
        val root = node(
            "FrameLayout",
            children = listOf(
                node("Button", "发送", clickable = true, bounds = RectI(0, 2000, 200, 2100)),
                node("EditText", editable = true, bounds = RectI(0, 1800, 1000, 1900)),
            ),
        )

        val tree = UiTreeFlattener.flatten(root, screen, "com.x", "d1")

        assertEquals(listOf(1, 2), tree.nodes.map { it.index })
        assertEquals(NodeRole.BUTTON, tree.nodes[0].role)
        assertEquals(NodeRole.EDIT, tree.nodes[1].role)
    }

    @Test
    fun `plain layout containers are skipped`() {
        val root = node(
            "FrameLayout",
            children = listOf(
                node("LinearLayout", children = listOf(node("Button", "OK", clickable = true))),
            ),
        )

        val tree = UiTreeFlattener.flatten(root, screen, null, "d1")

        assertEquals("只有真正可用的节点该出现", 1, tree.nodes.size)
        assertEquals("OK", tree.nodes.single().label)
    }

    @Test
    fun `same-bounds wrappers collapse into one entry`() {
        val b = RectI(10, 10, 200, 100)
        val root = node(
            "FrameLayout",
            bounds = b,
            children = listOf(node("TextView", "保存", bounds = b, clickable = true)),
        )

        val tree = UiTreeFlattener.flatten(root, screen, null, "d1")

        assertEquals("同坐标只留一条，否则模型会点错层", 1, tree.nodes.size)
        assertEquals("保存", tree.nodes.single().label)
    }

    @Test
    fun `header carries dump id package and screen`() {
        val tree = UiTreeFlattener.flatten(
            node("Button", "X", clickable = true), screen, "com.demo", "d7",
        )

        assertTrue(tree.text, tree.text.startsWith("[ui_tree dump=d7 pkg=com.demo screen=1080x2400"))
    }

    @Test
    fun `rows are compact and machine friendly`() {
        val root = node(
            "Button", "发送", id = "com.x:id/send",
            bounds = RectI(900, 2100, 1020, 2200), clickable = true,
        )

        val row = UiTreeFlattener.flatten(root, screen, null, "d1").text.lines()[1]

        assertTrue(row, row.startsWith("[1] BUTTON"))
        assertTrue(row, row.contains("id=com.x:id/send"))
        assertTrue(row, row.contains("text=\"发送\""))
        assertTrue(row, row.contains("bounds=[900,2100,1020,2200]"))
        assertTrue(row, row.contains("clickable"))
    }

    @Test
    fun `invisible nodes are dropped by default`() {
        val root = node(children = listOf(node("Button", "隐藏", clickable = true, visible = false)))

        assertTrue(UiTreeFlattener.flatten(root, screen, null, "d1").nodes.isEmpty())
    }

    @Test
    fun `zero-area nodes are dropped`() {
        val root = node(
            children = listOf(node("Button", "退化", bounds = RectI(5, 5, 5, 5), clickable = true)),
        )

        assertTrue(UiTreeFlattener.flatten(root, screen, null, "d1").nodes.isEmpty())
    }

    @Test
    fun `truncation keeps inputs and buttons over plain text`() {
        val filler = (1..40).map { node("TextView", "text$it", bounds = RectI(0, it, 10, it + 5)) }
        val input = node("EditText", editable = true, bounds = RectI(0, 999, 10, 1005))
        val root = node(children = filler + input)

        val tree = UiTreeFlattener.flatten(root, screen, null, "d1", FlattenOptions(maxNodes = 5))

        assertTrue("必须标记被截断，否则模型会以为看到了全部", tree.truncated)
        assertEquals(5, tree.nodes.size)
        assertTrue("输入框优先级最高，必须保留", tree.nodes.any { it.editable })
    }

    @Test
    fun `kept nodes stay in depth-first order`() {
        val root = node(
            children = listOf(
                node("Button", "A", clickable = true, bounds = RectI(0, 0, 10, 10)),
                node("Button", "B", clickable = true, bounds = RectI(0, 20, 10, 30)),
                node("Button", "C", clickable = true, bounds = RectI(0, 40, 10, 50)),
            ),
        )

        val labels = UiTreeFlattener.flatten(root, screen, null, "d1").nodes.map { it.label }

        assertEquals(listOf("A", "B", "C"), labels)
    }

    @Test
    fun `an empty tree explains itself instead of rendering nothing`() {
        val tree = UiTreeFlattener.flatten(node("FrameLayout"), screen, null, "d1")

        assertTrue(tree.nodes.isEmpty())
        assertTrue(tree.text, tree.text.contains("没有可操作的控件"))
    }

    @Test
    fun `password flag is surfaced for the guard layer`() {
        val root = node("EditText", editable = true, password = true)

        val flat = UiTreeFlattener.flatten(root, screen, null, "d1").nodes.single()

        assertTrue(flat.flags.contains(NodeFlag.PASSWORD))
    }

    @Test
    fun `label falls back to content description when text is absent`() {
        val root = UiNode(
            className = "ImageButton",
            contentDescription = "返回",
            bounds = RectI(0, 0, 100, 100),
            clickable = true,
        )

        assertEquals("返回", UiTreeFlattener.flatten(root, screen, null, "d1").nodes.single().label)
    }
}
