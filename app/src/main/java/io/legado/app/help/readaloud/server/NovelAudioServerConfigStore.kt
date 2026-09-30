package io.legado.app.help.readaloud.server

import kotlinx.coroutines.CancellationException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** write 失败必须保留上一次完整值；Android 实现使用同目录原子 rename。 */
internal interface NovelAudioCredentialBlob {
    fun read(): ByteArray?
    fun write(value: ByteArray)
}

internal class NovelAudioServerConfigStore(
    private val blob: NovelAudioCredentialBlob,
    private val cipher: NovelAudioServerCredentialCipher
) {
    /** IO 线程调用。损坏或密钥丢失必须显式报错，不删除原记录或降级明文。 */
    fun load(): NovelAudioServerCredentials? = synchronized(globalLock) {
        credentialStorageOperation {
            val encrypted = blob.read() ?: return@credentialStorageOperation null
            if (encrypted.contentEquals(CLEARED)) return@credentialStorageOperation null
            require(encrypted.size <= MAX_BLOB_BYTES)
            val plain = cipher.decrypt(encrypted)
            try {
                DataInputStream(ByteArrayInputStream(plain)).use { input ->
                    require(input.readInt() == 1)
                    val url = input.readUTF()
                    val token = input.readUTF()
                    require(input.available() == 0)
                    // 明文 HTTP 只能由 replace 的显式确认入口写入；这里读取已认证密文。
                    NovelAudioServerCredentials.create(url, token, allowInsecureHttp = true)
                }
            } finally {
                plain.fill(0)
            }
        }
    }

    fun replace(baseUrl: String, token: String, allowInsecureHttp: Boolean = false) = synchronized(globalLock) {
        val snapshot = NovelAudioServerCredentials.create(baseUrl, token, allowInsecureHttp)
        credentialStorageOperation {
            val output = ByteArrayOutputStream()
            DataOutputStream(output).use {
                it.writeInt(1)
                it.writeUTF(snapshot.baseUrl)
                it.writeUTF(snapshot.token)
            }
            val plain = output.toByteArray()
            try {
                val encrypted = cipher.encrypt(plain)
                require(encrypted.size <= MAX_BLOB_BYTES)
                blob.write(encrypted)
            } finally {
                plain.fill(0)
            }
        }
    }

    fun clear() = synchronized(globalLock) {
        // 原子写无凭据标记；即使旧密钥失效，也可明确清除，失败则保留上次配置。
        credentialStorageOperation { blob.write(CLEARED.clone()) }
    }

    companion object {
        internal const val MAX_BLOB_BYTES = 16 * 1024
        private val CLEARED = byteArrayOf(0)
        private val globalLock = Any()
    }
}

internal inline fun <T> credentialStorageOperation(action: () -> T): T =
    kotlin.runCatching(action).getOrElse {
        if (it is CancellationException || it is Error) throw it
        throw NovelAudioServerException("STORAGE")
    }
