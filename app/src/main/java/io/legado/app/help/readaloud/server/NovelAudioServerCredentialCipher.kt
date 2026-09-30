package io.legado.app.help.readaloud.server

import javax.crypto.SecretKey
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

internal class NovelAudioServerCredentialCipher(private val keyProvider: (Boolean) -> SecretKey) {
    fun encrypt(plain: ByteArray): ByteArray = credentialStorageOperation {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // Keystore 要求由 provider 生成随机 IV，不从配置或正文派生 nonce。
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider(true))
        cipher.updateAAD(AAD)
        val iv = cipher.iv
        require(iv.size == IV_BYTES)
        byteArrayOf(VERSION) + iv + cipher.doFinal(plain)
    }

    fun decrypt(encrypted: ByteArray): ByteArray = credentialStorageOperation {
        require(encrypted.size in (1 + IV_BYTES + 16)..NovelAudioServerConfigStore.MAX_BLOB_BYTES)
        require(encrypted[0] == VERSION)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // 读取绝不自动补建丢失的密钥；失败交给调用方要求重新配置。
        cipher.init(Cipher.DECRYPT_MODE, keyProvider(false),
            GCMParameterSpec(128, encrypted.copyOfRange(1, 1 + IV_BYTES)))
        cipher.updateAAD(AAD)
        cipher.doFinal(encrypted, 1 + IV_BYTES, encrypted.size - 1 - IV_BYTES)
    }

    companion object {
        private const val VERSION: Byte = 1
        private const val IV_BYTES = 12
        private val AAD = "legado.novel_audio_server.credentials.v1".toByteArray(Charsets.UTF_8)
    }
}
