package io.legado.app

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.annotation.Keep
import androidx.test.runner.AndroidJUnitRunner

/**
 * Opt-in runner for the isolated four-state theme test.
 *
 * The class selector is intentionally required. Selecting this runner for a package, suite, or
 * another test would otherwise silently replace the Application for unrelated instrumentation.
 */
@Keep
class NovelAudioThemeTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle) {
        val selector = arguments.getString(CLASS_ARGUMENT)
        check(selector == TARGET_TEST || selector == "$TARGET_TEST#$TARGET_METHOD") {
            "NovelAudioThemeTestRunner requires an explicit class selector for $TARGET_TEST"
        }
        check(!arguments.containsKey("testFile") && !arguments.containsKey("package")) {
            "The theme runner cannot be combined with a test file or package selector"
        }
        super.onCreate(arguments)
    }

    override fun newApplication(
        cl: ClassLoader,
        className: String,
        context: Context
    ): Application {
        // Providers still run before Application.onCreate. Firebase's provider returns without
        // creating a FirebaseApp when this resource is absent; fail before providers if a future
        // build enables it. Only check resource existence, never read configuration/credentials.
        check(context.resources.getIdentifier("google_app_id", "string", context.packageName) == 0) {
            "Theme runner requires a build without Firebase auto-initialization configuration"
        }
        return super.newApplication(cl, NovelAudioThemeTestApplication::class.java.name, context)
    }

    private companion object {
        const val CLASS_ARGUMENT = "class"
        const val TARGET_TEST = "io.legado.app.NovelAudioThemeDeviceTest"
        const val TARGET_METHOD =
            "sameConfigFragmentRefreshesDefaultAccentPackageAndNightAndRestoresState"
    }
}
