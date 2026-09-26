package dev.pocket.agent.core.platform

/**
 * 可逆的假加密器，用来在宿主 JVM 上验证「密钥加密落盘」这条链路。
 *
 * 它刻意模拟真实 AEAD 的两个关键行为，否则测试会对真正的实现漏洞视而不见：
 * 1. 每次 [encrypt] 带新 nonce → 相同明文两次的密文不同（否则可被比对推断）；
 * 2. 密文被改动 → [decrypt] 抛 [CipherException]（完整性校验）。
 *
 * 算法本身只是 XOR + 校验和，**不是**加密，仅用于测试。
 */
class FakeSecretCipher(private val key: String = "unit-test-key") : SecretCipher {

    private var counter = 0

    override fun encrypt(plaintext: String): String {
        val nonce = "n${counter++}"
        val body = transform(plaintext.toByteArray(Charsets.UTF_8), nonce)
        return listOf(hex(nonce.toByteArray(Charsets.UTF_8)), hex(body), tag(body).toString(16))
            .joinToString(".")
    }

    override fun decrypt(blob: String): String {
        val parts = blob.split(".")
        if (parts.size != 3) throw CipherException("malformed blob: expected 3 parts, got ${parts.size}")
        val nonce = try {
            unhex(parts[0])
        } catch (e: IllegalArgumentException) {
            throw CipherException("malformed nonce", e)
        }
        val body = try {
            unhex(parts[1])
        } catch (e: IllegalArgumentException) {
            throw CipherException("malformed body", e)
        }
        if (tag(body).toString(16) != parts[2]) throw CipherException("integrity check failed")
        return String(transform(body, String(nonce, Charsets.UTF_8)), Charsets.UTF_8)
    }

    /** XOR 自反：同一 key + nonce 再做一次即还原。 */
    private fun transform(data: ByteArray, nonce: String): ByteArray {
        val pad = (key + "|" + nonce).toByteArray(Charsets.UTF_8)
        return ByteArray(data.size) { i -> (data[i].toInt() xor pad[i % pad.size].toInt()).toByte() }
    }

    private fun tag(body: ByteArray): Int {
        var h = 17
        for (b in body) h = h * 31 + b
        for (c in key.toByteArray(Charsets.UTF_8)) h = h * 31 + c
        return h
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun unhex(s: String): ByteArray {
        require(s.length % 2 == 0) { "odd-length hex" }
        return ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
