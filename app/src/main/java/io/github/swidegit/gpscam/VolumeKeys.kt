package io.github.swidegit.gpscam

import android.view.KeyEvent

/**
 * Volume keys as a shutter on the Camera tab: up starts or stops recording, down takes a photo.
 * The camera screen installs [handler] while it is visible; the activity forwards key events
 * (onKeyDown and onKeyUp, so the system never sees either half of the press).
 */
object VolumeKeys {
    @Volatile var handler: ((up: Boolean) -> Unit)? = null

    /** True when the event was used (the volume does not change then). */
    fun onKey(event: KeyEvent): Boolean {
        val h = handler ?: return false
        if (!AppSettings.state.value.volumeKeys) return false
        val up = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> true
            KeyEvent.KEYCODE_VOLUME_DOWN -> false
            else -> return false
        }
        // Act once per press; also swallow the repeats and the key-up so the volume stays put.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) h(up)
        return true
    }
}
