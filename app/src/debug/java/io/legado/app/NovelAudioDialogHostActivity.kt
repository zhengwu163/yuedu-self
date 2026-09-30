package io.legado.app

import android.os.Bundle
import androidx.annotation.Keep
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentFactory

/** Internal Debug-only host: no reader, server configuration, or playback is opened automatically. */
@Keep
class NovelAudioDialogHostActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        factory?.let { supportFragmentManager.fragmentFactory = it }
        super.onCreate(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        inspectSavedState(outState)
    }

    @Keep
    companion object {
        var factory: FragmentFactory? = null
        var inspectSavedState: (Bundle) -> Unit = {}
    }
}
