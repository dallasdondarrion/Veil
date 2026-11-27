package dev.veil.launcher.data

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Makes Veil Ambient the TV's own screensaver, and sets how long the TV waits before starting it.
 * Choosing the screensaver needs WRITE_SECURE_SETTINGS (Veil already has it); the wait time is a plain system
 * setting that also needs "Modify system settings", allowed once over adb.
 */
class ScreensaverControl(private val context: Context) {
    private val resolver get() = context.contentResolver
    private val veilDream = ComponentName(context, "dev.veil.launcher.VeilDream").flattenToString()

    val canChooseScreensaver: Boolean
        get() = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    val canSetTimeout: Boolean get() = Settings.System.canWrite(context)

    /** The screensaver the TV is set to, as "package/class", or null for the TV's own default. */
    val current: String? get() = runCatching { Settings.Secure.getString(resolver, COMPONENTS) }.getOrNull()

    val isVeil: Boolean get() = current?.split(',')?.any { it.trim() == veilDream } == true

    /** Makes Veil Ambient the screensaver, or puts [previous] (null = the TV's default) back. */
    fun setVeil(on: Boolean, previous: String?): Boolean = runCatching {
        Settings.Secure.putString(resolver, COMPONENTS, if (on) veilDream else previous)
    }.getOrDefault(false)

    /** Minutes of inactivity before the screensaver starts, or null if unknown. */
    val timeoutMinutes: Int?
        get() = runCatching { Settings.System.getInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT) }
            .getOrNull()?.takeIf { it > 0 }?.let { (it / 60_000) }

    fun setTimeoutMinutes(minutes: Int): Boolean = runCatching {
        Settings.System.putInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT, minutes * 60_000)
    }.getOrDefault(false)

    private companion object {
        // Not in the public SDK, but the same setting the TV's own screensaver picker writes.
        const val COMPONENTS = "screensaver_components"
    }
}
