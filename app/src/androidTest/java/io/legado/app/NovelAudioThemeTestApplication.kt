package io.legado.app

import android.app.Application
import android.content.Context
import androidx.annotation.Keep
import io.legado.app.base.AppContextWrapper
import splitties.init.injectAsAppCtx

/**
 * Application used only by the explicitly selected four-state theme device test.
 *
 * It keeps the production manifest/resources and real UI code, but deliberately does not
 * inherit [App.onCreate], which starts unrelated background work and preference listeners.
 */
@Keep
class NovelAudioThemeTestApplication : Application() {
    override fun attachBaseContext(base: Context) {
        // AppContextWrapper only reads locale/theme/font-scale preferences and does not touch
        // AppConfig, so it is safe before the Application is fully initialized.
        super.attachBaseContext(AppContextWrapper.wrap(base))
        // Inject before manifest ContentProviders run. The merged manifest contains Splitties'
        // AppCtxInitializer, but providers are not a safe place to depend on provider ordering.
        injectAsAppCtx()
    }
}
