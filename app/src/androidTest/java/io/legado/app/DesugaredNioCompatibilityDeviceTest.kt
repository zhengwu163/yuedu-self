package io.legado.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the shared host/test instrumentation process against an incomplete
 * test-side desugared NIO Path interface shadowing the host runtime.
 */
@RunWith(AndroidJUnit4::class)
class DesugaredNioCompatibilityDeviceTest {

    @Test
    fun instrumentationPathExposesFileSystem() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val path = File(context.cacheDir, "desugared-nio-compatibility").toPath()

        assertNotNull(path.fileSystem)
    }

    @Test
    fun instrumentationFilesMoveExposesHostAtomicExchangeApi() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File(context.cacheDir, "desugared-nio-source")
        val target = File(context.cacheDir, "desugared-nio-target")
        source.writeText("compatibility")
        target.delete()

        try {
            Files.move(source.toPath(), target.toPath())

            assertEquals("compatibility", target.readText())
        } finally {
            source.delete()
            target.delete()
        }
    }
}
