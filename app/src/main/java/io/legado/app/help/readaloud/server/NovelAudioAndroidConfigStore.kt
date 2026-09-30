package io.legado.app.help.readaloud.server

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

internal class AndroidNovelAudioCredentialBlob(private val file: File) : NovelAudioCredentialBlob {
    private val pending = File(file.path + ".new")

    override fun read(): ByteArray? = credentialStorageOperation {
        if (!file.exists()) {
            // 第一次写入中断后不可假装从未配置；保留孤立暂存供显式替换/清除。
            check(!pending.exists())
            return@credentialStorageOperation null
        }
        file.inputStream().use { input ->
            val buffer = ByteArray(NovelAudioServerConfigStore.MAX_BLOB_BYTES + 1)
            var size = 0
            while (size < buffer.size) {
                val count = input.read(buffer, size, buffer.size - size)
                if (count < 0) break
                size += count
            }
            require(size in 1..NovelAudioServerConfigStore.MAX_BLOB_BYTES)
            buffer.copyOf(size)
        }
    }

    override fun write(value: ByteArray): Unit = credentialStorageOperation {
        require(value.size in 1..NovelAudioServerConfigStore.MAX_BLOB_BYTES)
        // 同目录暂存+fsync+rename；Android 私有文件系统中 rename 是唯一提交点。
        // 不能在成功 rename 后再执行可能失败的校验并声称旧值已回滚。
        var committed = false
        try {
            FileOutputStream(pending).use {
                it.write(value)
                it.fd.sync()
            }
            check(pending.renameTo(file))
            committed = true
        } finally {
            // 未提交时旧文件未触碰。只清除此配置自己的暂存，不删除上次完整配置。
            if (!committed) kotlin.runCatching { pending.delete() }
        }
    }
}

/** 仅供本 App 的服务器连接使用；云厂商 API Key 不属于这份配置。IO 线程调用。 */
internal object NovelAudioAndroidConfigStore {
    fun open(context: Context): NovelAudioServerConfigStore = credentialStorageOperation {
        NovelAudioServerConfigStore(
            AndroidNovelAudioCredentialBlob(File(
                context.applicationContext.noBackupFilesDir, "novel_audio_server.credentials"
            )),
            cipher("legado.novel_audio_server.v1")
        )
    }

    internal fun cipher(alias: String): NovelAudioServerCredentialCipher =
        NovelAudioServerCredentialCipher { create -> synchronized(keyLock) {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = keyStore.getKey(alias, null) as? SecretKey
            existing ?: run {
                check(create)
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                    init(KeyGenParameterSpec.Builder(
                        alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setKeySize(256)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build())
                    generateKey()
                }
            }
        } }

    private val keyLock = Any()
}
