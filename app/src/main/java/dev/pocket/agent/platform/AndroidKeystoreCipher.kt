package dev.pocket.agent.platform

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.pocket.agent.core.platform.CipherException
import dev.pocket.agent.core.platform.SecretCipher
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore 支撑的 [SecretCipher]：AES-256/GCM，密钥材料永不离开 TEE/StrongBox。
 *
 * 落盘格式（Base64 文本）：`iv(12B) || ciphertext || tag(16B)`。
 * - 加密时**不传 IV**，由 Keystore 生成随机 IV（`setRandomizedEncryptionRequired(true)`
 *   会拒绝调用方自带 IV，这正是我们想要的：杜绝 IV 复用导致的 GCM 灾难性失效）。
 * - 解密时用落盘的 IV；tag 校验失败抛 [CipherException]（篡改/换设备/换密钥）。
 *
 * 密钥在首次使用时惰性生成，不在构造期触碰 Keystore。
 */
class AndroidKeystoreCipher(
    private val alias: String = DEFAULT_ALIAS,
) : SecretCipher {

    private val lock = Any()

    @Volatile
    private var cached: SecretKey? = null

    override fun encrypt(plaintext: String): String = synchronized(lock) {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            val body = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv + body, Base64.NO_WRAP)
        } catch (e: Exception) {
            throw CipherException("encrypt failed (${e.javaClass.simpleName})", e)
        }
    }

    override fun decrypt(blob: String): String = synchronized(lock) {
        val bytes = try {
            Base64.decode(blob, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            throw CipherException("credential blob is not valid Base64", e)
        }
        if (bytes.size <= IV_LENGTH) {
            throw CipherException("credential blob is truncated (${bytes.size} bytes)")
        }
        val iv = bytes.copyOfRange(0, IV_LENGTH)
        val body = bytes.copyOfRange(IV_LENGTH, bytes.size)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw CipherException("keystore key was permanently invalidated", e)
        } catch (e: Exception) {
            // AEADBadTagException / BadPaddingException 等都归为「密文不可用」，
            // 上层语义是「没配密钥」，而不是崩溃。
            throw CipherException("decrypt failed (${e.javaClass.simpleName})", e)
        }
    }

    /** 取出或惰性生成 Keystore 里的 AES 密钥。 */
    private fun key(): SecretKey {
        cached?.let { return it }
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let {
            cached = it.secretKey
            return it.secretKey
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        val generated = generator.generateKey()
        cached = generated
        return generated
    }

    companion object {
        const val DEFAULT_ALIAS = "pocket.agent.credential.v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256
        private const val IV_LENGTH = 12
        private const val TAG_BITS = 128
    }
}
