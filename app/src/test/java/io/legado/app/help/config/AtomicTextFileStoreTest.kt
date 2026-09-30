package io.legado.app.help.config

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class AtomicTextFileStoreTest {
    @Test
    fun failedVerificationPreservesCommittedTextAndCleansStaging() {
        val directory = Files.createTempDirectory("atomic-text-store-test").toFile()
        val target = directory.resolve("config.json")
        try {
            target.writeText("committed")

            assertThrows(IllegalStateException::class.java) {
                AtomicTextFileStore(target).writeVerified("replacement") { false }
            }

            assertEquals("committed", target.readText())
            assertFalse(directory.resolve(".config.json.staging").exists())
            assertFalse(directory.resolve(".config.json.backup").exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
