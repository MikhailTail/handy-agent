package dev.pocket.agent.core.platform

/** 解密失败（密文被篡改、密钥被系统轮换、数据来自其它设备）。 */
class CipherException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 对称加密抽象。
 *
 * 实现必须满足：
 * 1. 每次 [encrypt] 使用新的随机 IV —— 相同明文两次加密结果必须不同（否则可被比对推断）。
 * 2. 自带完整性校验（AEAD），[decrypt] 对任何被篡改的输入抛 [CipherException]。
 * 3. 输出是可安全放进字符串存储的 ASCII（Base64）。
 *
 * Android 实现是 Keystore 里的 AES/GCM 密钥；单测用假实现（只需可逆）。
 */
interface SecretCipher {

    fun encrypt(plaintext: String): String

    /** @throws CipherException 密文不可解密时。 */
    fun decrypt(blob: String): String
}
