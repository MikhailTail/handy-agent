package dev.mikhailtail.handyagent.core.mobile

import dev.mikhailtail.handyagent.core.model.ImageRef

/**
 * 审批预览需要的设备上下文快照。
 *
 * 只在**生成审批卡文案**时读一次，不进任何业务逻辑 —— 目的是让用户看懂
 * "Agent 到底要操作哪个元素"，而不是对着一串 JSON 点同意。
 */
data class MobilePreviewInfo(
    val tree: FlatTree? = null,
    val foregroundPackage: String? = null,
    /** 最近一次截图，附在审批卡上供用户视觉确认。 */
    val lastShot: ImageRef? = null,
)

/** 惰性取快照：装配期还没有会话状态，必须推迟到真正弹审批的那一刻。 */
fun interface MobilePreviewSource {
    fun snapshot(): MobilePreviewInfo?
}
