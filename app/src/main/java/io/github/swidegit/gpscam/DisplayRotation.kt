package io.github.swidegit.gpscam

import android.hardware.display.DisplayManager
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView

/**
 * The display rotation, updated live. A configuration change is not enough: turning the phone
 * by 180 degrees (left landscape to right landscape) changes the rotation but not the configuration.
 */
@Composable
fun rememberDisplayRotation(): Int {
    val view = LocalView.current
    var rotation by remember { mutableIntStateOf(view.display?.rotation ?: Surface.ROTATION_0) }
    DisposableEffect(view) {
        val dm = view.context.getSystemService(DisplayManager::class.java)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                val d = view.display ?: return
                if (d.displayId == displayId) rotation = d.rotation
            }
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
        }
        dm.registerDisplayListener(listener, view.handler)
        onDispose { dm.unregisterDisplayListener(listener) }
    }
    return rotation
}
