package eu.einhq.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import java.util.UUID

/**
 * What the app says of the phone it runs on when it pairs: its installation, which EiN keeps on the
 * phone it pairs as so that signing in again finds it, and what the phone is called and is, for a
 * new phone EiN makes.
 */
data class ThisPhone(val install: String, val label: String, val manufacturer: String, val model: String) {
    companion object {
        /**
         * Android's `ANDROID_ID` is this app's own on this phone (it differs for each app signing key
         * and stays through a reinstall), so it names the installation; it is sent to the paired EiN
         * and nowhere else. A phone that has none gets a random one, kept until the app is removed.
         */
        @SuppressLint("HardwareIds")
        fun of(context: Context): ThisPhone {
            val resolver = context.contentResolver
            val install = Settings.Secure.getString(resolver, Settings.Secure.ANDROID_ID)?.takeIf { it.isNotBlank() } ?: keptInstall(context)
            val model = Build.MODEL.orEmpty()
            val manufacturer = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.titlecase() }
            // What the phone's owner named it in its settings, else its model.
            val name = Settings.Global.getString(resolver, Settings.Global.DEVICE_NAME)?.trim()?.takeIf { it.isNotEmpty() }
            return ThisPhone(install, name ?: model, manufacturer, model)
        }

        private fun keptInstall(context: Context): String {
            val prefs = context.getSharedPreferences("install", Context.MODE_PRIVATE)
            prefs.getString("id", null)?.let { return it }
            val id = UUID.randomUUID().toString()
            prefs.edit { putString("id", id) }
            return id
        }
    }
}
