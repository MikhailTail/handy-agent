package dev.mikhailtail.handyagent.core.mobile

enum class LoopVerdict {
    NONE,

    /** 同样的动作在同样的界面上反复执行 —— 多半是没生效（选错了元素、按钮不可用）。 */
    REPEATING,

    /** 界面连续多步没有变化 —— 卡住了（加载中、弹窗遮挡、需要滚动但没滚）。 */
    STUCK,
}

/** 一步的指纹：当前界面 + 刚执行的动作。 */
data class StepFingerprint(val screenHash: String, val actionHash: String)

/**
 * 循环/卡死检测。
 *
 * 手机上的自动化失败往往不是"报错"，而是**安静地原地打转**：
 * 点了没反应、滑不动、一直停在同一个页面。没有这个检测，模型会把上下文烧光才发现无解。
 * 它的产出不是终止，而是**给模型一句提示**（"这个操作无效，换个思路"）。
 */
class LoopDetector(
    /** 连续多少步界面不变算卡死。 */
    private val stuckThreshold: Int = 6,
    /** 同一 (界面,动作) 重复多少次算无效操作。 */
    private val repeatThreshold: Int = 3,
) {

    private val recent = ArrayDeque<StepFingerprint>()
    private var lastScreenHash: String? = null
    private var sameScreenStreak = 0

    fun observe(step: StepFingerprint): LoopVerdict {
        // 同界面连续计数（用于 STUCK）
        if (step.screenHash == lastScreenHash) {
            sameScreenStreak++
        } else {
            lastScreenHash = step.screenHash
            sameScreenStreak = 1
        }

        recent.addLast(step)
        while (recent.size > REPEAT_WINDOW) recent.removeFirst()

        val repeats = recent.count { it == step }
        return when {
            repeats >= repeatThreshold -> LoopVerdict.REPEATING
            sameScreenStreak >= stuckThreshold -> LoopVerdict.STUCK
            else -> LoopVerdict.NONE
        }
    }

    fun reset() {
        recent.clear()
        lastScreenHash = null
        sameScreenStreak = 0
    }

    companion object {
        private const val REPEAT_WINDOW = 10
    }
}

/**
 * 界面指纹。
 *
 * 只取「角色 + 标签 + 相对位置档位」，故意**忽略绝对坐标**：
 * 列表轻微滚动、状态栏时钟跳动都会改变像素，但界面其实没变，用绝对坐标会永远判不出卡死。
 */
object ScreenHasher {

    fun hash(tree: FlatTree): String {
        if (tree.nodes.isEmpty()) return "empty:${tree.packageName.orEmpty()}"
        val sb = StringBuilder(tree.nodes.size * 24)
        sb.append(tree.packageName.orEmpty()).append('|')
        for (n in tree.nodes) {
            sb.append(n.role.name).append(':').append(n.label).append(':')
            // 位置按屏高/宽归一化成 0..9 的档位，抵消毛刺
            sb.append(quantize(n.bounds.centerY, tree.screen.height))
            sb.append(',')
            sb.append(quantize(n.bounds.centerX, tree.screen.width))
            sb.append(';')
        }
        return sb.toString()
    }

    private fun quantize(v: Int, total: Int): Int {
        if (total <= 0) return 0
        val bucket = (v.toLong() * 10L / total).toInt()
        return bucket.coerceIn(0, 9)
    }
}
