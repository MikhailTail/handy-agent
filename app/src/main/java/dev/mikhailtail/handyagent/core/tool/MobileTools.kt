package dev.mikhailtail.handyagent.core.tool

import dev.mikhailtail.handyagent.core.json.Json
import dev.mikhailtail.handyagent.core.mobile.ActionResolver
import dev.mikhailtail.handyagent.core.mobile.LoopVerdict
import dev.mikhailtail.handyagent.core.mobile.MobileAction
import dev.mikhailtail.handyagent.core.mobile.MobileBridge
import dev.mikhailtail.handyagent.core.mobile.MobileKey
import dev.mikhailtail.handyagent.core.mobile.MobileResult
import dev.mikhailtail.handyagent.core.mobile.ResolvedAction
import dev.mikhailtail.handyagent.core.mobile.ScrollDir
import dev.mikhailtail.handyagent.core.mobile.UnavailableReason

/**
 * mobile use 的工具集。
 *
 * 设计取舍：
 * 1. **感知类只读**（ui_tree / screenshot / state / notifications）——免审批，否则每看一眼
 *    都要弹窗，根本没法用。
 * 2. **操作类一律 `readOnly = false`** —— 走 broker 的 ASK 通道，每一步都过用户审批。
 *    手机上的误触代价可能是转账或发消息，不能自动放行。
 * 3. 所有失败都翻译成**给模型看的文本**，绝不抛异常：模型需要读到"序号过期了"才能自我纠正。
 */

private const val NO_MOBILE = "当前环境不支持手机操作（未接入无障碍服务）"
private const val HARD_STOP = "已进入受保护界面，自动化已停止，请人工处理"

private fun unavailableText(r: MobileResult.Unavailable): String = when (r.reason) {
    // 平台没接无障碍时给出**可操作的引导**（去哪开），而不是平台自报的内部细节
    UnavailableReason.SERVICE_NOT_ENABLED -> MobileBridge.NO_SERVICE
    UnavailableReason.RATE_LIMITED -> "截图太频繁（系统限制约每秒 3 张），请稍后重试"
    UnavailableReason.SECURE_WINDOW -> "当前界面受保护（FLAG_SECURE），系统拒绝截图"
    UnavailableReason.NOTIFICATION_ACCESS_MISSING -> r.message
    UnavailableReason.IME_NOT_SELECTED -> r.message
    UnavailableReason.GESTURE_PREEMPTED -> "手势被用户触摸打断，请重新感知界面后再试"
    else -> r.message
}

private fun loopHint(v: LoopVerdict): String = when (v) {
    LoopVerdict.REPEATING -> "\n⚠ 同样的操作在同一界面上重复多次仍未生效，请换一种方式"
    LoopVerdict.STUCK -> "\n⚠ 界面已连续多步没有变化，可能卡住了（尝试返回或滚动）"
    LoopVerdict.NONE -> ""
}

// =====================================================================  感知

object MobileUiTreeTool : Tool {
    override val name = "mobile_ui_tree"
    override val description = """
        观察当前手机界面：**同时**返回当前屏幕截图 + 带序号的控件清单。
        两者要配合看：截图让你看清视觉内容（图标、图片、颜色、没有文字标签的区域），
        控件清单让你精确挑中元素（用序号而不是猜坐标）。
        需要操作时，用 mobile_tap / mobile_swipe / mobile_type 带上 index 与 dump_id。
        序号只在本次 dump_id 内有效，界面变化后必须重新观察。
    """.trimIndent()

    override val inputSchema = toolSchema(
        required = emptyList(),
        properties = mapOf("include_invisible" to boolProp("是否包含不可见节点（默认 false）")),
    )

    override val readOnly = true

    override suspend fun execute(input: Json, ctx: ToolContext): ToolOutcome {
        val mobile = ctx.mobile ?: return ToolOutcome.error(NO_MOBILE)
        if (ctx.mobileState.hardStopped) return ToolOutcome.error(HARD_STOP)

        val dump = when (val r = mobile.ui.dump(input.bool("include_invisible") ?: false)) {
            is MobileResult.Ok -> r.value
            is MobileResult.Unavailable -> return ToolOutcome.error(unavailableText(r))
            is MobileResult.Failed -> return ToolOutcome.error("读取界面失败：${r.message}")
        }
        ctx.mobileState.recordDump(dump)

        // 关键：观察必须**同时**给截图和控件树。
        //
        // 只用控件树会丢掉大量信息：图标长什么样、图片内容、颜色状态、以及那些
        // 没有语义标签却需要点的区域 —— 模型会"看不见"这些，然后瞎点。
        // 反过来只用截图又拿不到精确的元素边界，点不准。
        // 两者一起才是可用的"观察"，跟 computer use 的截图+可访问性树是同一个道理。
        val shot = mobile.screen.capture("ui_tree")
        val images = (shot as? MobileResult.Ok)?.let { listOf(it.value) } ?: emptyList()
        if (shot is MobileResult.Unavailable && shot.reason == UnavailableReason.SECURE_WINDOW) {
            ctx.mobileState.markSecureWindow()
        }

        val treeNote = if (dump.nodes.isEmpty()) "\n\n提示：控件树为空，本次主要依据截图判断。" else ""

        val shotNote = when (shot) {
            is MobileResult.Ok ->
                "\n\n（已附当前屏幕截图 ${shot.value.width}x${shot.value.height}" +
                    "；控件清单用于精确选中元素，截图用于看清视觉内容）"

            is MobileResult.Unavailable -> when (shot.reason) {
                UnavailableReason.SECURE_WINDOW ->
                    "\n\n（截图被系统拒绝：当前是受保护界面，只能依据控件清单判断，且自动化已停止）"
                else -> "\n\n（截图不可用：${shot.message}）"
            }

            is MobileResult.Failed -> "\n\n（截图失败：${shot.message}）"
        }

        return ToolOutcome(content = dump.text + treeNote + shotNote, images = images)
    }
}

object MobileScreenshotTool : Tool {
    override val name = "mobile_screenshot"
    override val description =
        "截取当前屏幕并把图片交给模型。系统限制约每秒 3 张，不要连续快速调用。"
    override val inputSchema = toolSchema(
        required = emptyList(),
        properties = mapOf("label" to stringProp("可选说明，用于日志")),
    )
    override val readOnly = true

    override suspend fun execute(input: Json, ctx: ToolContext): ToolOutcome {
        val mobile = ctx.mobile ?: return ToolOutcome.error(NO_MOBILE)
        if (ctx.mobileState.hardStopped) return ToolOutcome.error(HARD_STOP)

        return when (val r = mobile.screen.capture(input.str("label").orEmpty())) {
            is MobileResult.Ok -> ToolOutcome(
                content = "已截屏 ${r.value.width}x${r.value.height}（${r.value.byteSize / 1024} KB）",
                images = listOf(r.value),
            )

            is MobileResult.Unavailable -> {
                if (r.reason == UnavailableReason.SECURE_WINDOW) ctx.mobileState.markSecureWindow()
                ToolOutcome.error(unavailableText(r))
            }

            is MobileResult.Failed -> ToolOutcome.error("截屏失败：${r.message}")
        }
    }
}

object MobileStateTool : Tool {
    override val name = "mobile_state"
    override val description = "报告当前设备状态：前台应用、屏幕尺寸、各项能力是否可用、已执行步数。"
    override val inputSchema = toolSchema(emptyList(), emptyMap())
    override val readOnly = true

    override suspend fun execute(input: Json, ctx: ToolContext): ToolOutcome {
        val mobile = ctx.mobile ?: return ToolOutcome.error(NO_MOBILE)
        val sb = StringBuilder()

        sb.append("前台应用: ").append(
            when (val fg = mobile.apps.foregroundPackage()) {
                is MobileResult.Ok -> fg.value
                is MobileResult.Unavailable -> "未知（${fg.message}）"
                is MobileResult.Failed -> "读取失败（${fg.message}）"
            }
        ).append('\n')

        (mobile.apps.screenInfo() as? MobileResult.Ok)?.let {
            sb.append("屏幕: ").append(it.value.width).append('x').append(it.value.height)
                .append(" 旋转=").append(it.value.rotation.name).append('\n')
        }

        sb.append("本会话已执行步数: ").append(ctx.mobileState.stepCount).append('\n')

        val sensitive = ctx.mobileState.lastSensitive
        if (sensitive.sensitive) {
            sb.append("⚠ 当前界面被判定为敏感（").append(sensitive.reason?.name).append("）")
            if (sensitive.hardStop) sb.append("，自动化已停止")
            sb.append('\n')
        }

        sb.append("能力状态:\n")
        for (c in mobile.capability.list()) {
            sb.append("  - ").append(c.capability.name).append(": ")
                .append(if (c.available) "可用" else "不可用")
            if (c.detail.isNotBlank()) sb.append("（").append(c.detail).append("）")
            sb.append('\n')
        }
        return ToolOutcome.ok(sb.toString().trim())
    }
}

object MobileNotificationsTool : Tool {
    override val name = "mobile_notifications"
    override val description =
        "读取最近的通知。内容可能包含隐私信息，只在用户明确需要时使用。"
    override val inputSchema = toolSchema(
        required = emptyList(),
        properties = mapOf(
            "limit" to intProp("最多返回多少条（默认 20）"),
            "package" to stringProp("只看某个应用的通知"),
        ),
    )
    override val readOnly = true

    override suspend fun execute(input: Json, ctx: ToolContext): ToolOutcome {
        val mobile = ctx.mobile ?: return ToolOutcome.error(NO_MOBILE)
        if (!mobile.notifications.available()) {
            return ToolOutcome.error(
                "通知读取权限未授予：请到「设置 → 通知 → 设备与应用通知」中允许本应用"
            )
        }
        val list = mobile.notifications.recent(input.int("limit") ?: 20, input.str("package"))
        if (list.isEmpty()) return ToolOutcome.ok("（没有通知）")
        return ToolOutcome.ok(
            list.joinToString("\n") { n ->
                "[${n.packageName}] ${n.title.orEmpty()} — ${n.text.orEmpty().take(200)}"
            }
        )
    }
}

// =====================================================================  操作

object MobileTapTool : Tool {
    override val name = "mobile_tap"
    override val description = """
        点击屏幕。优先用 index + dump_id（来自最近一次 mobile_ui_tree）；
        只有控件树为空（游戏 / 画布界面）时才退回 x/y 绝对像素。
    """.trimIndent()

    override val inputSchema = toolSchema(
        required = emptyList(),
        properties = mapOf(
            "index" to intProp("节点序号（来自 mobile_ui_tree）"),
            "dump_id" to stringProp("序号所属的快照 id，与 index 成对使用"),
            "x" to intProp("屏幕绝对像素 X（兜底）"),
            "y" to intProp("屏幕绝对像素 Y（兜底）"),
        ),
    )

    override suspend fun execute(input: Json, ctx: ToolContext): ToolOutcome {
        val mobile = ctx.mobile ?: return ToolOutcome.error(NO_MOBILE)
        if (ctx.mobileState.hardStopped) return ToolOutcome.error(HARD_STOP)

        val index = input.int("index")
        val x = input.int("x")
        val y = input.int("y")
        val action = when {
            index != null -> MobileAction.TapIndex(index, input.str("dump_id").orEmpty())
            x != null && y != null -> MobileAction.TapXY(x, y)
            else -> return ToolOutcome.error("需要 index（并配 dump_id），或 x 与 y")
        }

        return when (val resolved = ActionResolver.resolve(action, ctx.mobileState.lastTree)) {
            is ResolvedAction.Tap -> {
                val verdict = ctx.mobileState.recordAction("tap:$action")
                when (val r = mobile.input.tap(resolved.point.x, resolved.point.y)) {
                    is MobileResult.Ok -> {
                        val what = resolved.target
                            ?.let { " → [${it.index}] ${it.role.name} \"${it.label}\"" }
                            .orEmpty()
                        ToolOutcome.ok("已点击 ${resolved.point}$what${loopHint(verdict)}")
                    }

                    is MobileResult.Unavailable -> ToolOutcome.error(unavailableText(r))
                    is MobileResult.Failed -> ToolOutcome.error("点击失败：${r.message}")
                }
            }

            is ResolvedAction.Failure -> ToolOutcome.error(resolved.message)
            else -> ToolOutcome.error("内部错误：解析结果与动作不匹配")
        }
    }
}

object MobileSwipeTool : Tool {
    override val name = "mobile_swipe"
    override val description = """
        在屏幕上滑动。direction 是**手指移动方向**（up = 手指上滑 = 内容向下滚动）。
        可给 index（在某个可滚动区域上滑动），否则作用于整屏。
    """.trimIndent()

    override val inputSchema = toolSchema(
        required = listOf("direction"),
        properties = mapOf(
            "direction" to stringProp("up | down | left | right"),
            "index" to intProp("可选的滑动起点节点序号"),
            "dump_id" to stringProp("序号所属的快照 id"),
            "distance_px" to intProp("滑动距离（像素），默认取控件或屏幕的一部分"),
        ),
    )

    override suspend fun execute(input: Json, ctx: ToolContext): ToolOutcome {
        val mobile = ctx.mobile ?: return ToolOutcome.error(NO_MOBILE)
        if (ctx.mobileState.hardStopped) return ToolOutcome.error(HARD_STOP)

        val dir = when (input.str("direction")?.lowercase()) {
            "up" -> ScrollDir.UP
            "down" -> ScrollDir.DOWN
            "left" -> ScrollDir.LEFT
            "right" -> ScrollDir.RIGHT
            else -> return ToolOutcome.error("direction 必须是 up / down / left / right")
        }

        val action = MobileAction.Swipe(
            dir = dir,
            index = input.int("index"),
            distancePx = input.int("distance_px"),
            dumpId = input.str("dump_id"),
        )

        return when (val resolved = ActionResolver.resolve(action, ctx.mobileState.lastTree)) {
            is ResolvedAction.Swipe -> {
                val verdict = ctx.mobileState.recordAction("swipe:$action")
                val points = resolved.path.map { it.x to it.y }
                when (val r = mobile.input.swipe(points)) {
                    is MobileResult.Ok -> ToolOutcome.ok(
                        "已向 ${dir.name.lowercase()} 滑动 ${resolved.path.size} 个采样点${loopHint(verdict)}"
                    )
                    is MobileResult.Unavailable -> ToolOutcome.error(unavailableText(r))
                    is MobileResult.Failed -> ToolOutcome.error("滑动失败：${r.message}")
                }
            }

            is ResolvedAction.Failure -> ToolOutcome.error(resolved.message)
            else -> ToolOutcome.error("内部错误：解析结果与动作不匹配")
        }
    }
}

object MobileTypeTool : Tool {
    override val name = "mobile_type"
    override val description = """
        往输入框输入文本。建议先用 index 指定输入框（执行侧会先聚焦它）。
        中文等非 ASCII 字符由应用自带输入法写入，比逐个按键可靠。
    """.trimIndent()

    override val inputSchema = toolSchema(
        required = listOf("text"),
        properties = mapOf(
            "text" to stringProp("要输入的内容"),
            "index" to intProp("输入框节点序号（可选但推荐）"),
            "dump_id" to stringProp("序号所属的快照 id"),
            "submit" to boolProp("输入后是否按回车提交（默认 false）"),
        ),
    )

    override suspend fun execute(input: Json, ctx: ToolContext): ToolOutcome {
        val mobile = ctx.mobile ?: return ToolOutcome.error(NO_MOBILE)
        if (ctx.mobileState.hardStopped) return ToolOutcome.error(HARD_STOP)

        val text = input.str("text").orEmpty()
        val action = MobileAction.Type(
            text = text,
            index = input.int("index"),
            submit = input.bool("submit") ?: false,
            dumpId = input.str("dump_id"),
        )

        return when (val resolved = ActionResolver.resolve(action, ctx.mobileState.lastTree)) {
            is ResolvedAction.TypeText -> {
                // 先点一下目标输入框，让它拿到焦点（很多界面不点不聚焦）
                resolved.target?.let { target ->
                    val p = dev.mikhailtail.handyagent.core.mobile.GesturePlanner
                        .tapPoint(target.bounds, ctx.mobileState.lastTree!!.screen)
                    mobile.input.tap(p.x, p.y)
                }
                val verdict = ctx.mobileState.recordAction("type:${text.length}chars")
                when (val r = mobile.input.type(resolved.text)) {
                    is MobileResult.Ok -> {
                        var out = "已输入 ${resolved.text.length} 个字符"
                        if (resolved.submit) {
                            mobile.input.globalAction(MobileKey.ENTER)
                            out += "，并提交"
                        }
                        ToolOutcome.ok(out + loopHint(verdict))
                    }

                    is MobileResult.Unavailable -> ToolOutcome.error(unavailableText(r))
                    is MobileResult.Failed -> ToolOutcome.error("输入失败：${r.message}")
                }
            }

            is ResolvedAction.Failure -> ToolOutcome.error(resolved.message)
            else -> ToolOutcome.error("内部错误：解析结果与动作不匹配")
        }
    }
}

object MobileKeyTool : Tool {
    override val name = "mobile_key"
    override val description =
        "按下系统按键：back / home / recents / notifications / quick_settings / lock_screen / enter。"

    override val inputSchema = toolSchema(
        required = listOf("key"),
        properties = mapOf("key" to stringProp("按键名")),
    )

    override suspend fun execute(input: Json, ctx: ToolContext): ToolOutcome {
        val mobile = ctx.mobile ?: return ToolOutcome.error(NO_MOBILE)
        if (ctx.mobileState.hardStopped) return ToolOutcome.error(HARD_STOP)

        val key = when (input.str("key")?.lowercase()) {
            "back" -> MobileKey.BACK
            "home" -> MobileKey.HOME
            "recents" -> MobileKey.RECENTS
            "notifications" -> MobileKey.NOTIFICATIONS
            "quick_settings" -> MobileKey.QUICK_SETTINGS
            "lock_screen" -> MobileKey.LOCK_SCREEN
            "enter" -> MobileKey.ENTER
            "delete" -> MobileKey.DELETE
            "search" -> MobileKey.SEARCH
            else -> return ToolOutcome.error("不支持的按键：${input.str("key")}")
        }

        return when (val r = mobile.input.globalAction(key)) {
            is MobileResult.Ok -> ToolOutcome.ok("已按下 ${key.name.lowercase()}")
            is MobileResult.Unavailable -> ToolOutcome.error(unavailableText(r))
            is MobileResult.Failed -> ToolOutcome.error("按键失败：${r.message}")
        }
    }
}

object MobileLaunchTool : Tool {
    override val name = "mobile_launch"
    override val description = "按包名启动一个应用，例如 com.tencent.mm。"

    override val inputSchema = toolSchema(
        required = listOf("package"),
        properties = mapOf("package" to stringProp("应用包名")),
    )

    override suspend fun execute(input: Json, ctx: ToolContext): ToolOutcome {
        val mobile = ctx.mobile ?: return ToolOutcome.error(NO_MOBILE)
        if (ctx.mobileState.hardStopped) return ToolOutcome.error(HARD_STOP)

        val action = MobileAction.Launch(input.str("package").orEmpty())
        return when (val resolved = ActionResolver.resolve(action, ctx.mobileState.lastTree)) {
            is ResolvedAction.LaunchApp -> when (val r = mobile.apps.launch(resolved.packageName)) {
                is MobileResult.Ok -> ToolOutcome.ok("已启动 ${resolved.packageName}")
                is MobileResult.Unavailable -> ToolOutcome.error(unavailableText(r))
                is MobileResult.Failed -> ToolOutcome.error("启动失败：${r.message}")
            }

            is ResolvedAction.Failure -> ToolOutcome.error(resolved.message)
            else -> ToolOutcome.error("内部错误：解析结果与动作不匹配")
        }
    }
}

/** 全部 mobile 工具，供 [ToolRegistry.builtin] 之外按需并入。 */
val MOBILE_TOOLS: List<Tool> = listOf(
    MobileUiTreeTool,
    MobileScreenshotTool,
    MobileStateTool,
    MobileNotificationsTool,
    MobileTapTool,
    MobileSwipeTool,
    MobileTypeTool,
    MobileKeyTool,
    MobileLaunchTool,
)
