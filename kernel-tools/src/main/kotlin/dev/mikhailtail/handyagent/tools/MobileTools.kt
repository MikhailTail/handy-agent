package dev.mikhailtail.handyagent.tools

import dev.mikhailtail.handyagent.kernel.api.MobileCapability
import dev.mikhailtail.handyagent.kernel.api.MobileKey
import dev.mikhailtail.handyagent.kernel.api.MobileResult
import dev.mikhailtail.handyagent.kernel.api.ScrollDirection
import dev.mikhailtail.handyagent.kernel.api.Tool
import dev.mikhailtail.handyagent.kernel.api.ToolContext
import dev.mikhailtail.handyagent.kernel.api.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 手机操作工具。
 *
 * 这是这个项目区别于普通聊天客户端的地方：模型能真的"看见"并操作这台手机。
 *
 * **护栏**：写操作（点击、输入、启动应用）都需要审批 —— 误触在手机上可能是
 * 转账或发消息。读操作（列表、通知）放行，否则用户会在第三次就放弃使用。
 *
 * 设计上刻意**不做"点坐标"优先**：绝大多数操作应按元素索引进行，坐标只在
 * 元素点不动时作退路。原因与 cc-haha 的 macOS 路径一致 —— 按元素操作能扛住
 * 窗口移动与布局变化，按坐标则是"此刻恰好在那儿"。
 */
class MobileToolSet(private val mobile: MobileCapability) {

    fun tools(): List<Tool> = listOf(
        ListAppsTool(mobile),
        GetStateTool(mobile),
        ClickTool(mobile),
        TapTool(mobile),
        SetValueTool(mobile),
        ScrollTool(mobile),
        PressKeyTool(mobile),
        LaunchAppTool(mobile),
        NotificationsTool(mobile),
    )
}

/** 能力未就绪时的统一回复 —— 告诉模型"做不到"而不是让它反复重试。 */
private fun notReady(mobile: MobileCapability): ToolResult? =
    if (mobile.isReady()) null
    else ToolResult(
        content = JsonPrimitive(
            "手机操作能力当前不可用：${mobile.unavailableReason() ?: "无障碍服务未开启"}\n" +
                "请让用户到「设置 → 无障碍」里打开 Handy Agent 的服务，不要反复重试。",
        ),
        isError = true,
    )

private fun <T> MobileResult<T>.toToolResult(onOk: (T) -> String): ToolResult = when (this) {
    is MobileResult.Ok -> ToolResult(JsonPrimitive(onOk(value)))
    is MobileResult.Failed -> ToolResult(JsonPrimitive(message), isError = true)
    // 过期与不可用都要让模型改变行为（重新观察 / 告知用户），而不是原样重试。
    is MobileResult.Stale -> ToolResult(JsonPrimitive(message), isError = true)
    is MobileResult.Unavailable -> ToolResult(JsonPrimitive(message), isError = true)
}

private fun str(v: String): JsonElement = JsonPrimitive(v)
private fun JsonObject.strOf(key: String): String? =
    runCatching { (this[key] as? JsonPrimitive)?.content }.getOrNull()
private fun JsonObject.intOf(key: String): Int? = strOf(key)?.toIntOrNull()
private fun JsonObject.doubleOf(key: String): Double? = strOf(key)?.toDoubleOrNull()

private fun schema(properties: Map<String, JsonObject>, required: List<String>): JsonObject =
    buildJsonObject {
        put("type", str("object"))
        put("properties", buildJsonObject { properties.forEach { (k, v) -> put(k, v) } })
        put("required", buildJsonArray { required.forEach { add(str(it)) } })
    }

private fun prop(type: String, description: String): JsonObject =
    buildJsonObject { put("type", str(type)); put("description", str(description)) }

// ─── 只读 ────────────────────────────────────────────────────────────────────

private class ListAppsTool(private val mobile: MobileCapability) : Tool {
    override val name = "mobile_list_apps"
    override val description = "列出手机上可启动的应用（包名与显示名）。"
    override val inputSchema = schema(emptyMap(), emptyList())
    override val isReadOnly = true

    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        notReady(mobile)?.let { return it }
        return MobileResult.Ok(mobile.listApps()).toToolResult { apps ->
            if (apps.isEmpty()) "没有可启动的应用"
            else apps.joinToString("\n") { "${it.label}\t${it.packageName}" }
        }
    }
}

/**
 * 读当前界面 —— **控件树与截图一起给**。
 *
 * 两者缺一不可：树负责精确选中（按 index 点），截图负责看清内容
 * （图标、图片、颜色、没有文字标签的区域）。
 */
private class GetStateTool(private val mobile: MobileCapability) : Tool {
    override val name = "mobile_get_state"
    override val description = """
        观察当前手机界面：**同时**返回带序号的控件清单 + 当前屏幕截图。

        两者要配合看：
        - 控件清单让你精确挑中元素（用 index，不要猜坐标）；
        - 截图让你看清视觉内容（图标、图片、颜色、没有文字标签的区域）。

        每次调用都会产生一个新的 dump_id。**index 只在同一次 dump 内有效** ——
        拿旧的 index 去点会被拒绝，届时应重新调用本工具观察。
    """.trimIndent()

    override val inputSchema = schema(emptyMap(), emptyList())
    override val isReadOnly = true

    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        notReady(mobile)?.let { return it }

        val state = runCatching { mobile.getState() }.getOrElse {
            return ToolResult(JsonPrimitive("读取界面失败：${it.message}"), isError = true)
        }

        // 截图拿不到时要说清是"被系统拒绝"还是"暂时不可用" —— 前者意味着
        // 屏幕上可能有用户不想被看到的东西，模型该谨慎行事而不是换个法子偷看。
        val note = when {
            state.secure ->
                "\n\n[这是受保护界面，截图被系统拒绝。只能依据控件清单操作；若涉及敏感操作请交还给用户。]"
            state.screenshotBase64 == null -> "\n\n[截图不可用，只能依据控件清单。]"
            else -> ""
        }

        return ToolResult(
            content = JsonPrimitive(
                "当前应用：${state.appLabel ?: "未知"}（${state.packageName ?: "?"}）\n" +
                    "屏幕尺寸：${state.screenWidth}×${state.screenHeight}\n" +
                    "dump_id：${state.dumpId}\n\n" +
                    state.treeText + note,
            ),
            images = listOfNotNull(state.screenshotBase64),
        )
    }
}

private class NotificationsTool(private val mobile: MobileCapability) : Tool {
    override val name = "mobile_notifications"
    override val description = "读取当前通知栏里的通知。"
    override val inputSchema = schema(emptyMap(), emptyList())
    override val isReadOnly = true

    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        notReady(mobile)?.let { return it }
        return MobileResult.Ok(mobile.readNotifications()).toToolResult { list ->
            if (list.isEmpty()) "没有通知"
            else list.joinToString("\n") { n ->
                "[${n.packageName}] ${n.title.orEmpty()} ${n.text.orEmpty()}".trim()
            }
        }
    }
}

// ─── 写操作（需审批）──────────────────────────────────────────────────────────

private class ClickTool(private val mobile: MobileCapability) : Tool {
    override val name = "mobile_click"
    override val description = """
        按控件序号点击。index 与 dump_id 来自最近一次 mobile_get_state。

        优先用本工具而不是按坐标点 —— 按元素操作能扛住界面布局变化。
        只有在元素点不动（点了没反应）时才改用 mobile_tap 配合截图坐标。
    """.trimIndent()

    override val inputSchema = schema(
        mapOf(
            "index" to prop("integer", "控件序号，来自 mobile_get_state"),
            "dump_id" to prop("string", "该序号所属的快照 id"),
        ),
        required = listOf("index", "dump_id"),
    )

    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        notReady(mobile)?.let { return it }
        val index = input.intOf("index")
            ?: return ToolResult(str("缺少 index 参数"), isError = true)
        val dumpId = input.strOf("dump_id")
            ?: return ToolResult(str("缺少 dump_id 参数，请先调用 mobile_get_state"), isError = true)

        return mobile.click(index, dumpId).toToolResult { "已点击 [$index]" }
    }
}

private class TapTool(private val mobile: MobileCapability) : Tool {
    override val name = "mobile_tap"
    override val description = """
        按屏幕坐标点击。**这是退路，不是首选。**

        坐标从 mobile_get_state 返回的截图上读，是该图自身的像素空间。
        能用 mobile_click 按序号点就不要用这个 —— 坐标会因布局变化而失效。
    """.trimIndent()

    override val inputSchema = schema(
        mapOf(
            "x" to prop("integer", "横坐标（截图像素）"),
            "y" to prop("integer", "纵坐标（截图像素）"),
        ),
        required = listOf("x", "y"),
    )

    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        notReady(mobile)?.let { return it }
        val x = input.intOf("x") ?: return ToolResult(str("缺少 x 参数"), isError = true)
        val y = input.intOf("y") ?: return ToolResult(str("缺少 y 参数"), isError = true)

        return mobile.tap(x, y).toToolResult { "已点击坐标 ($x, $y)" }
    }
}

private class SetValueTool(private val mobile: MobileCapability) : Tool {
    override val name = "mobile_set_value"
    override val description = """
        往输入框里写入文本。index 指向那个输入框，来自 mobile_get_state。

        写入后建议重新调用 mobile_get_state 确认内容真的进去了 ——
        部分输入框会拒绝直接赋值，需要改用点击 + mobile_press_key 的方式。
    """.trimIndent()

    override val inputSchema = schema(
        mapOf(
            "index" to prop("integer", "输入框的控件序号"),
            "dump_id" to prop("string", "该序号所属的快照 id"),
            "text" to prop("string", "要写入的文本"),
        ),
        required = listOf("index", "dump_id", "text"),
    )

    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        notReady(mobile)?.let { return it }
        val index = input.intOf("index") ?: return ToolResult(str("缺少 index 参数"), isError = true)
        val dumpId = input.strOf("dump_id")
            ?: return ToolResult(str("缺少 dump_id 参数"), isError = true)
        val text = input.strOf("text") ?: return ToolResult(str("缺少 text 参数"), isError = true)

        return mobile.setValue(index, dumpId, text).toToolResult { "已写入文本" }
    }
}

private class ScrollTool(private val mobile: MobileCapability) : Tool {
    override val name = "mobile_scroll"
    override val description = "滚动屏幕。可以指定从哪个控件起滚，不指定则从屏幕中心。"

    override val inputSchema = schema(
        mapOf(
            "direction" to prop("string", "up / down / left / right"),
            "index" to prop("integer", "起始控件序号（可选）"),
            "dump_id" to prop("string", "序号所属的快照 id（给了 index 就要给）"),
            "pages" to prop("number", "滚动幅度，1 表示一屏（可选，默认 1）"),
        ),
        required = listOf("direction"),
    )

    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        notReady(mobile)?.let { return it }
        val direction = when (input.strOf("direction")?.lowercase()) {
            "up" -> ScrollDirection.UP
            "down" -> ScrollDirection.DOWN
            "left" -> ScrollDirection.LEFT
            "right" -> ScrollDirection.RIGHT
            else -> return ToolResult(str("direction 必须是 up/down/left/right"), isError = true)
        }
        val index = input.intOf("index")
        val dumpId = input.strOf("dump_id").orEmpty()
        if (index != null && dumpId.isEmpty()) {
            return ToolResult(str("给了 index 就必须给 dump_id"), isError = true)
        }

        return mobile.scroll(index, dumpId, direction, input.doubleOf("pages") ?: 1.0)
            .toToolResult { "已向 $direction 滚动" }
    }
}

private class PressKeyTool(private val mobile: MobileCapability) : Tool {
    override val name = "mobile_press_key"
    override val description = """
        按系统键：BACK / HOME / ENTER / TAB / ESCAPE / DEL / APP_SWITCH。

        常用的是 BACK（返回上一级）与 HOME（回到桌面）。
    """.trimIndent()

    override val inputSchema = schema(
        mapOf("key" to prop("string", "BACK / HOME / ENTER / TAB / ESCAPE / DEL / APP_SWITCH")),
        required = listOf("key"),
    )

    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        notReady(mobile)?.let { return it }
        val key = runCatching { MobileKey.valueOf(input.strOf("key")?.uppercase().orEmpty()) }
            .getOrNull()
            ?: return ToolResult(
                str("key 必须是：${MobileKey.entries.joinToString(" / ")}"),
                isError = true,
            )

        return mobile.pressKey(key).toToolResult { "已按下 $key" }
    }
}

private class LaunchAppTool(private val mobile: MobileCapability) : Tool {
    override val name = "mobile_launch_app"
    override val description = "启动一个应用。包名从 mobile_list_apps 取。"
    override val inputSchema = schema(
        mapOf("package_name" to prop("string", "应用包名")),
        required = listOf("package_name"),
    )

    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        notReady(mobile)?.let { return it }
        val pkg = input.strOf("package_name")
            ?: return ToolResult(str("缺少 package_name 参数"), isError = true)

        return mobile.launchApp(pkg).toToolResult { "已启动 $pkg" }
    }
}
