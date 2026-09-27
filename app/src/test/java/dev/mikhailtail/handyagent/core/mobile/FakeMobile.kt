package dev.mikhailtail.handyagent.core.mobile

import dev.mikhailtail.handyagent.core.model.ImageRef

/**
 * 可脚本化的假设备。
 *
 * 它**记录每一次真实发出的操作**（[taps] / [swipes] / [typed] / [globalKeys] / [launched]）——
 * 护栏测试正是靠"这些列表为空"来证明「审批通过之前没有任何动作真的落到设备上」。
 * 这类断言如果只靠读代码是保证不了的。
 */
class FakeMobile(
    var tree: FlatTree? = null,
    var foreground: String? = "com.demo",
    var notificationRecords: List<NotifRecord> = emptyList(),
    var notificationAccess: Boolean = true,
    /** 覆盖默认的能力可用性；默认只有无障碍可用。 */
    var capabilityOverrides: Map<Capability, Boolean> = emptyMap(),
    var captureFails: MobileResult<ImageRef>? = null,
    var tapFails: MobileResult<Unit>? = null,
) {
    val taps = ArrayList<Pair<Int, Int>>()
    val swipes = ArrayList<List<Pair<Int, Int>>>()
    val typed = ArrayList<String>()
    val globalKeys = ArrayList<MobileKey>()
    val launched = ArrayList<String>()

    var dumpCount = 0
    var captureCount = 0

    val screen = ScreenInfo(1080, 2400, densityDpi = 420)

    fun bridge(): MobileBridge = MobileBridge(
        capability = object : MobileCapabilityProbe {
            override fun list(): List<CapabilityStatus> = Capability.entries.map { c ->
                CapabilityStatus(
                    capability = c,
                    available = capabilityOverrides[c] ?: (c == Capability.ACCESSIBILITY),
                    detail = "",
                )
            }
        },

        screen = ScreenCapture {
            captureCount++
            captureFails ?: MobileResult.Ok(
                ImageRef(
                    id = "img_$captureCount.jpg",
                    mediaType = "image/jpeg",
                    width = 1080,
                    height = 2400,
                    byteSize = 120_000,
                )
            )
        },

        ui = UiInspector {
            dumpCount++
            tree?.let { MobileResult.Ok(it) }
                ?: MobileResult.Unavailable(UnavailableReason.SERVICE_NOT_ENABLED, "没有可用的界面快照")
        },

        input = object : MobileInput {
            override suspend fun tap(x: Int, y: Int, durationMs: Int): MobileResult<Unit> {
                tapFails?.let { return it }
                taps.add(x to y)
                return MobileResult.Ok(Unit)
            }

            override suspend fun swipe(path: List<Pair<Int, Int>>, durationMs: Int): MobileResult<Unit> {
                swipes.add(path)
                return MobileResult.Ok(Unit)
            }

            override suspend fun globalAction(key: MobileKey): MobileResult<Unit> {
                globalKeys.add(key)
                return MobileResult.Ok(Unit)
            }

            override suspend fun type(text: String): MobileResult<Unit> {
                typed.add(text)
                return MobileResult.Ok(Unit)
            }
        },

        apps = object : AppController {
            override suspend fun launch(packageName: String): MobileResult<Unit> {
                launched.add(packageName)
                return MobileResult.Ok(Unit)
            }

            override suspend fun foregroundPackage(): MobileResult<String> =
                foreground?.let { MobileResult.Ok(it) } ?: MobileResult.Failed("未知")

            override fun screenInfo(): MobileResult<ScreenInfo> = MobileResult.Ok(screen)
        },

        notifications = object : NotificationReader {
            override fun available(): Boolean = notificationAccess

            override fun recent(limit: Int, packageFilter: String?): List<NotifRecord> =
                notificationRecords
                    .filter { packageFilter == null || it.packageName == packageFilter }
                    .take(limit)
        },
    )

    companion object {
        /** 造一棵扁平树，供测试当"当前界面"。 */
        fun treeOf(dumpId: String, vararg nodes: UiNode): FlatTree =
            UiTreeFlattener.flatten(
                UiNode("Root", children = nodes.toList()),
                ScreenInfo(1080, 2400),
                "com.demo",
                dumpId,
            )

        fun button(
            text: String,
            bounds: RectI = RectI(900, 2100, 1020, 2200),
            password: Boolean = false,
        ) = UiNode(
            className = if (password) "EditText" else "Button",
            text = text,
            bounds = bounds,
            clickable = !password,
            editable = password,
            password = password,
        )
    }
}
