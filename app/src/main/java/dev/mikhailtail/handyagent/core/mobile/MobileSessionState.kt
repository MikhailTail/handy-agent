package dev.mikhailtail.handyagent.core.mobile

/**
 * 一次会话内的 mobile 执行状态。
 *
 * 关键用途是保存**最近一次 dump 的树**：模型看到序号后，后续 tap/swipe/type 要靠它把序号
 * 换回坐标。序号只在它所属的那次 dump 内有效，[ActionResolver] 用 dumpId 强制这一点 ——
 * 界面一变就作废，绝不能沿用旧序号。
 */
class MobileSessionState(
    private val loopDetector: LoopDetector = LoopDetector(),
) {

    @Volatile
    var lastTree: FlatTree? = null
        private set

    /** 最近一次判定的敏感状态；[SensitiveVerdict.hardStop] 为真时本轮禁止一切操作。 */
    @Volatile
    var lastSensitive: SensitiveVerdict = SensitiveVerdict.OK
        private set

    var stepCount: Int = 0
        private set

    /** 记一次界面快照，顺便做敏感界面判定（每步都判，而不是只在开头判）。 */
    fun recordDump(tree: FlatTree) {
        lastTree = tree
        lastSensitive = SensitiveScreenDetector.inspect(
            packageName = tree.packageName,
            nodes = tree.nodes,
        )
    }

    /** 截图被 FLAG_SECURE 拒绝：整轮停手，转人工。 */
    fun markSecureWindow() {
        lastSensitive = SensitiveVerdict(
            sensitive = true,
            reason = SensitiveReason.SECURE_WINDOW,
            hardStop = true,
        )
    }

    /** 记录一次动作；返回是否在打转（用于给模型提示，而不是终止）。 */
    fun recordAction(action: String): LoopVerdict {
        stepCount++
        val tree = lastTree ?: return LoopVerdict.NONE
        return loopDetector.observe(StepFingerprint(ScreenHasher.hash(tree), action))
    }

    fun reset() {
        lastTree = null
        lastSensitive = SensitiveVerdict.OK
        stepCount = 0
        loopDetector.reset()
    }

    val hardStopped: Boolean get() = lastSensitive.hardStop
}
