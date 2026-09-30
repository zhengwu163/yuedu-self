package io.legado.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.help.readaloud.offline.canDecodeNovelAudio
import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NovelAudioDecoderDeviceTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "novel-audio-decoder-fixture"
        ).also {
            check(it.mkdirs() || it.isDirectory)
        }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun validAudioMustProduceDecodedSamples() {
        val valid = File(root, "valid.mp3")
        InstrumentationRegistry.getInstrumentation().targetContext.resources
            .openRawResource(io.legado.app.R.raw.silent_sound)
            .use { input ->
                valid.outputStream().use { output -> input.copyTo(output) }
            }

        assertTrue(valid.length() > 0L)
        assertTrue(canDecodeNovelAudio(valid))
    }

    @Test
    fun truncatedAudioMustNotPassDecoderGate() {
        val invalid = File(root, "truncated.mp3")
        val source = InstrumentationRegistry.getInstrumentation().targetContext.resources
            .openRawResource(io.legado.app.R.raw.silent_sound)
            .use { it.readBytes() }
        invalid.writeBytes(source.copyOf((source.size / 3).coerceAtLeast(1)))

        assertTrue(invalid.length() > 0L)
        assertFalse(canDecodeNovelAudio(invalid))
    }
}
