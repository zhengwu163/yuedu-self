package io.legado.app.help.readaloud.offline

import io.legado.app.help.readaloud.server.SynthesizedAudio
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException

/**
 * Commits a synthesized segment as a verified local artifact.
 *
 * A database READY row is only meaningful after this store has atomically
 * committed a real, non-empty and decodable file below [rootDirectory].
 */
class NovelAudioArtifactStore(
    private val rootDirectory: File,
    private val decoder: (File) -> Boolean,
    private val writePart: (File, ByteArray) -> Unit = ::writeArtifactPart,
    private val commitFile: (File, File) -> Unit = ::commitArtifactPart
) {
    /** 保留常用的尾随 lambda decoder 调用，避免被可选文件提交回调捕获。 */
    constructor(
        rootDirectory: File,
        decoder: (File) -> Boolean
    ) : this(rootDirectory, decoder, ::writeArtifactPart, ::commitArtifactPart)

    fun commit(
        serverScope: String,
        workKey: String,
        physicalBookUrl: String,
        chapterIndex: Int,
        chapterUrl: String,
        segmentId: String,
        voiceAssetId: String,
        bindingRevision: Long,
        language: String,
        speed: Double,
        audio: SynthesizedAudio,
        isCurrent: () -> Boolean = { true }
    ): StoredArtifact {
        checkCurrent(isCurrent)

        val contentType = audio.contentType.substringBefore(';').trim().lowercase(Locale.ROOT)
        requireInput(audio.contentType.isNotBlank() && audio.contentType.length <= MAX_CONTENT_TYPE_CHARS)
        requireInput(audio.bytes.isNotEmpty() && audio.bytes.size.toLong() <= MAX_ARTIFACT_BYTES)
        requireInput(serverScope.isBoundedIdentity())
        requireInput(workKey.isBoundedIdentity())
        requireInput(physicalBookUrl.isBoundedIdentity())
        requireInput(chapterIndex >= 0)
        requireInput(chapterUrl.isBoundedIdentity())
        requireInput(segmentId.isBoundedIdentity())
        requireInput(voiceAssetId.isBoundedIdentity())
        requireInput(bindingRevision >= 0L)
        requireInput(language.isBoundedIdentity(MAX_LANGUAGE_CHARS))
        requireInput(speed.isFinite() && speed > 0.0 && speed <= MAX_SPEED)
        val ttsProfile = audio.ttsProfile.trim()
        requireInput(ttsProfile.isBoundedIdentity(MAX_PROFILE_CHARS))

        val extension = NovelAudioPathCodec.extensionFor(contentType)
        val checksum = sha256(audio.bytes)
        val physicalChapterKey = NovelAudioPathCodec.chapterDirectory(
            physicalBookUrl,
            chapterIndex,
            chapterUrl
        )
        val cacheKey = NovelAudioPathCodec.cacheKey(
            workKey = workKey,
            physicalChapterKey = physicalChapterKey,
            segmentId = segmentId,
            voiceAssetId = voiceAssetId,
            bindingRevision = bindingRevision,
            language = language,
            speed = speed,
            ttsProfile = ttsProfile
        )
        val targetName = "$cacheKey-$checksum.$extension"

        var part: File? = null
        try {
            val canonicalRoot = prepareRoot()
            val directory = File(
                rootDirectory,
                listOf(
                    NovelAudioPathCodec.scopeDirectory(serverScope),
                    NovelAudioPathCodec.workDirectory(workKey),
                    physicalChapterKey
                ).joinToString(File.separator)
            )
            ensureContained(canonicalRoot, directory)
            ensureDirectory(directory, canonicalRoot)

            val target = File(directory, targetName)
            ensureContained(canonicalRoot, target)
            val targetLock = publicationLocks.getOrPut(target.canonicalPath) { Any() }

            return synchronized(targetLock) {
                checkCurrent(isCurrent)
                if (target.exists() && isValidTarget(target, checksum, audio.bytes.size.toLong())) {
                    checkCurrent(isCurrent)
                    return@synchronized storedArtifact(
                        target = target,
                        canonicalRoot = canonicalRoot,
                        contentType = contentType,
                        checksum = checksum,
                        size = audio.bytes.size.toLong(),
                        ttsProfile = ttsProfile
                    )
                }

                part = try {
                    File.createTempFile("$cacheKey-", ".$extension.part", directory)
                } catch (_: Throwable) {
                    throw ArtifactStoreException(ERROR_WRITE)
                }
                ensureContained(canonicalRoot, part!!)
                checkCurrent(isCurrent)

                try {
                    writePart(part!!, audio.bytes)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    throw ArtifactStoreException(ERROR_WRITE)
                }
                verifyPart(part!!, checksum, audio.bytes.size.toLong())
                checkCurrent(isCurrent)

                if (!decode(part!!)) {
                    throw ArtifactStoreException(ERROR_DECODE)
                }
                checkCurrent(isCurrent)

                // Reuse a valid concurrent publication. A corrupt file remains untouched
                // until the replacement has passed its own checksum and decoder checks.
                if (target.exists() && isValidTarget(target, checksum, audio.bytes.size.toLong())) {
                    checkCurrent(isCurrent)
                    return@synchronized storedArtifact(
                        target = target,
                        canonicalRoot = canonicalRoot,
                        contentType = contentType,
                        checksum = checksum,
                        size = audio.bytes.size.toLong(),
                        ttsProfile = ttsProfile
                    )
                }

                checkCurrent(isCurrent)
                try {
                    commitFile(part!!, target)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    throw ArtifactStoreException(ERROR_COMMIT)
                }
                verifyPublishedTarget(target, checksum, audio.bytes.size.toLong())
                // Publication makes these verified bytes reusable by other plans. Cancellation
                // still prevents a READY return, but must not unlink a shared content address.
                checkCurrent(isCurrent)
                storedArtifact(
                    target = target,
                    canonicalRoot = canonicalRoot,
                    contentType = contentType,
                    checksum = checksum,
                    size = audio.bytes.size.toLong(),
                    ttsProfile = ttsProfile
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: ArtifactStoreException) {
            throw error
        } catch (_: Throwable) {
            throw ArtifactStoreException(ERROR_COMMIT)
        } finally {
            part?.let(::deleteQuietly)
        }
    }

    private fun prepareRoot(): File {
        try {
            if (!rootDirectory.exists() && !rootDirectory.mkdirs() && !rootDirectory.isDirectory) {
                throw ArtifactStoreException(ERROR_ROOT)
            }
            if (!rootDirectory.isDirectory) {
                throw ArtifactStoreException(ERROR_ROOT)
            }
            return rootDirectory.canonicalFile
        } catch (error: ArtifactStoreException) {
            throw error
        } catch (_: Throwable) {
            throw ArtifactStoreException(ERROR_ROOT)
        }
    }

    private fun ensureDirectory(directory: File, canonicalRoot: File) {
        try {
            if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
                throw ArtifactStoreException(ERROR_PATH)
            }
            if (!directory.isDirectory) {
                throw ArtifactStoreException(ERROR_PATH)
            }
            ensureContained(canonicalRoot, directory)
        } catch (error: ArtifactStoreException) {
            throw error
        } catch (_: Throwable) {
            throw ArtifactStoreException(ERROR_PATH)
        }
    }

    private fun ensureContained(canonicalRoot: File, candidate: File) {
        try {
            val rootAbsolute = rootDirectory.absoluteFile
            val candidateAbsolute = candidate.absoluteFile
            val rootPrefix = rootAbsolute.path.trimEnd(File.separatorChar) + File.separator
            if (candidateAbsolute != rootAbsolute &&
                !candidateAbsolute.path.startsWith(rootPrefix)
            ) {
                throw ArtifactStoreException(ERROR_PATH)
            }

            var current: File? = candidateAbsolute
            while (current != null && current.path != rootAbsolute.path) {
                if (Files.isSymbolicLink(current.toPath())) {
                    throw ArtifactStoreException(ERROR_PATH)
                }
                current = current.parentFile
            }

            val canonicalCandidate = candidate.canonicalFile
            val canonicalPrefix =
                canonicalRoot.path.trimEnd(File.separatorChar) + File.separator
            if (canonicalCandidate != canonicalRoot &&
                !canonicalCandidate.path.startsWith(canonicalPrefix)
            ) {
                throw ArtifactStoreException(ERROR_PATH)
            }
        } catch (error: ArtifactStoreException) {
            throw error
        } catch (_: Throwable) {
            throw ArtifactStoreException(ERROR_PATH)
        }
    }

    private fun isValidTarget(target: File, checksum: String, expectedSize: Long): Boolean {
        if (!target.isFile) {
            throw ArtifactStoreException(ERROR_TARGET)
        }
        return target.length() == expectedSize && sha256(target) == checksum && decode(target)
    }

    private fun verifyPart(part: File, checksum: String, expectedSize: Long) {
        if (!part.isFile || part.length() != expectedSize || sha256(part) != checksum) {
            throw ArtifactStoreException(ERROR_WRITE)
        }
    }

    private fun verifyPublishedTarget(target: File, checksum: String, expectedSize: Long) {
        if (!target.isFile || target.length() != expectedSize || sha256(target) != checksum) {
            throw ArtifactStoreException(ERROR_COMMIT)
        }
    }

    private fun decode(file: File): Boolean {
        return try {
            decoder(file)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            false
        }
    }

    private fun storedArtifact(
        target: File,
        canonicalRoot: File,
        contentType: String,
        checksum: String,
        size: Long,
        ttsProfile: String
    ): StoredArtifact {
        return StoredArtifact(
            path = target.canonicalFile.relativeTo(canonicalRoot).path,
            contentType = contentType,
            sha256 = checksum,
            size = size,
            ttsProfile = ttsProfile
        )
    }

    private fun deleteQuietly(file: File) {
        kotlin.runCatching {
            Files.deleteIfExists(file.toPath())
        }
    }

    private fun checkCurrent(isCurrent: () -> Boolean) {
        if (!isCurrent()) {
            throw CancellationException(ERROR_STALE)
        }
    }

    private fun requireInput(condition: Boolean) {
        require(condition) { ERROR_INVALID_INPUT }
    }

    private fun String.isBoundedIdentity(maxLength: Int = MAX_IDENTITY_CHARS): Boolean {
        return isNotBlank() && length <= maxLength
    }

    class ArtifactStoreException(message: String) : IOException(message)

    data class StoredArtifact(
        val path: String,
        val contentType: String,
        val sha256: String,
        val size: Long,
        val ttsProfile: String
    )

    companion object {
        const val MAX_ARTIFACT_BYTES = 16L * 1024L * 1024L

        private const val MAX_IDENTITY_CHARS = 1024
        private const val MAX_CONTENT_TYPE_CHARS = 128
        private const val MAX_PROFILE_CHARS = 256
        private const val MAX_LANGUAGE_CHARS = 64
        private const val MAX_SPEED = 8.0

        private const val ERROR_INVALID_INPUT = "audio artifact input is invalid"
        private const val ERROR_ROOT = "audio artifact root is invalid"
        private const val ERROR_PATH = "audio artifact path is invalid"
        private const val ERROR_WRITE = "audio artifact write failed"
        private const val ERROR_DECODE = "audio artifact failed to decode"
        private const val ERROR_COMMIT = "audio artifact commit failed"
        private const val ERROR_TARGET = "audio artifact target is invalid"
        private const val ERROR_STALE = "audio artifact is stale"

        private val publicationLocks = ConcurrentHashMap<String, Any>()

        private fun sha256(bytes: ByteArray): String {
            return java.security.MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(Locale.ROOT, it) }
        }

        private fun sha256(file: File): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
        }
    }
}

private fun writeArtifactPart(part: File, bytes: ByteArray) {
    val parent = part.parentFile
    if (parent == null || !parent.isDirectory) {
        throw IOException("artifact parent is unavailable")
    }
    FileOutputStream(part).use { output ->
        output.write(bytes)
        output.fd.sync()
    }
}

private fun commitArtifactPart(part: File, target: File) {
    try {
        Files.move(
            part.toPath(), target.toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
        )
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        // Never delete a corrupt target first: repair must publish in a single atomic step.
        if (target.exists()) throw IOException(ERROR_ATOMIC_REPLACE)
        Files.move(part.toPath(), target.toPath())
    } catch (_: UnsupportedOperationException) {
        if (target.exists()) throw IOException(ERROR_ATOMIC_REPLACE)
        Files.move(part.toPath(), target.toPath())
    }
}

private const val ERROR_ATOMIC_REPLACE = "atomic audio artifact replacement is unavailable"
