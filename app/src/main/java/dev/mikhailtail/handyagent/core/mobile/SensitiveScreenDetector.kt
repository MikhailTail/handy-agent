package dev.mikhailtail.handyagent.core.mobile

enum class SensitiveReason {
    /** 目标窗口带 FLAG_SECURE，系统直接拒绝截图（银行/支付类应用普遍如此）。 */
    SECURE_WINDOW,
    PACKAGE_DENYLIST,
    PASSWORD_FIELD,
    PAYMENT_KEYWORDS,
}

/**
 * 敏感界面判定结果。
 *
 * [hardStop] 为 true 表示：**立刻停手、转人工**，本轮后续所有 mobile 动作一律拒绝。
 * 这不是"再确认一次"的级别，而是"自动化到此为止"。
 */
data class SensitiveVerdict(
    val sensitive: Boolean,
    val reason: SensitiveReason? = null,
    val hardStop: Boolean = false,
) {
    val safe: Boolean get() = !sensitive

    companion object {
        val OK = SensitiveVerdict(sensitive = false)
    }
}

data class SensitiveRules(
    val packageDenylist: Set<String> = DEFAULT_PACKAGE_DENYLIST,
    val keywords: Set<String> = DEFAULT_KEYWORDS,
    /** 命中包黑名单时是否直接硬停（默认否：仍允许只读感知，但每步都要审批）。 */
    val hardStopOnDenylistPackage: Boolean = false,
) {
    companion object {
        /** 常见银行 / 支付 / 应用商店包名（可按需扩充）。 */
        val DEFAULT_PACKAGE_DENYLIST: Set<String> = setOf(
            "com.android.settings",
            "com.android.vending",              // Play 商店
            "com.eg.android.AlipayGphone",      // 支付宝
            "com.unionpay",                     // 云闪付
            "com.icbc",                         // 工行
            "com.chinamworld.main",             // 建行
            "com.android.bankabc",              // 农行
            "com.bankcomm.Bankcomm",            // 交行
        )

        /** 界面文字命中就视为敏感。 */
        val DEFAULT_KEYWORDS: Set<String> = setOf(
            "密码", "支付", "验证码", "银行卡", "转账", "指纹", "面容",
            "password", "passcode", "passwd", "payment", "credit card", "cvv", "otp",
        )
    }
}

/**
 * 敏感界面识别。
 *
 * 最可靠的信号是平台给的 `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW`：窗口带 FLAG_SECURE 时
 * 截图会直接失败。把它当作硬开关，而不是"截图失败重试一下"。
 */
object SensitiveScreenDetector {

    fun inspect(
        packageName: String?,
        nodes: List<FlatNode>,
        /** 截图是否因 FLAG_SECURE 被系统拒绝。 */
        secureWindow: Boolean = false,
        rules: SensitiveRules = SensitiveRules(),
    ): SensitiveVerdict {
        // 1) 最高优先级：系统明说这个窗口不给看 —— 停手，不要再尝试任何自动化。
        if (secureWindow) {
            return SensitiveVerdict(
                sensitive = true,
                reason = SensitiveReason.SECURE_WINDOW,
                hardStop = true,
            )
        }

        // 2) 密码框：可能是任意 App 的登录页。
        if (nodes.any { it.node.password }) {
            return SensitiveVerdict(true, SensitiveReason.PASSWORD_FIELD)
        }

        // 3) 包名黑名单。
        if (packageName != null && packageName in rules.packageDenylist) {
            return SensitiveVerdict(
                sensitive = true,
                reason = SensitiveReason.PACKAGE_DENYLIST,
                hardStop = rules.hardStopOnDenylistPackage,
            )
        }

        // 4) 界面文字命中关键词。
        if (nodes.any { n -> matchesKeyword(n.label, rules.keywords) }) {
            return SensitiveVerdict(true, SensitiveReason.PAYMENT_KEYWORDS)
        }

        return SensitiveVerdict.OK
    }

    private fun matchesKeyword(label: String, keywords: Set<String>): Boolean {
        if (label.isBlank()) return false
        val lower = label.lowercase()
        return keywords.any { lower.contains(it.lowercase()) }
    }
}
