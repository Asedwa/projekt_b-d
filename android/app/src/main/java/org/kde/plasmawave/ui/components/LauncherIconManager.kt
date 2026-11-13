package org.kde.plasmawave.ui.components

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.annotation.DrawableRes
import org.kde.plasmawave.R

enum class AppLauncherIcon(
    val title: String,
    val subtitle: String,
    @DrawableRes val previewRes: Int
) {
    DEFAULT("Klasik (Orijinal)", "Varsayılan uygulama simgesi", R.drawable.ic_launcher_foreground),
    TWILIGHT("Twilight Sparkle", "Mor / lavanta karakter simgesi", R.drawable.ic_header_twilight),
    PINKIE("Pinkie Pie", "Pembe neşeli karakter simgesi", R.drawable.ic_header_pinkie)
}

object LauncherIconManager {
    private const val TAG = "LauncherIconManager"
    private const val PREFS = "launcher_icon_prefs"
    private const val KEY_ICON = "selected_launcher_icon"

    fun getSelectedIcon(context: Context): AppLauncherIcon {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_ICON, AppLauncherIcon.DEFAULT.name)
        return runCatching { AppLauncherIcon.valueOf(name ?: AppLauncherIcon.DEFAULT.name) }
            .getOrDefault(AppLauncherIcon.DEFAULT)
    }

    fun setLauncherIcon(context: Context, icon: AppLauncherIcon) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ICON, icon.name).apply()

        val pm = context.packageManager
        val pkg = context.packageName

        val defaultComponent = ComponentName(pkg, "$pkg.MainActivity")
        val twilightComponent = ComponentName(pkg, "$pkg.MainActivityTwilight")
        val pinkieComponent = ComponentName(pkg, "$pkg.MainActivityPinkie")

        fun setComponentState(comp: ComponentName, enable: Boolean) {
            val state = if (enable) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            try {
                pm.setComponentEnabledSetting(comp, state, PackageManager.DONT_KILL_APP)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set component state for $comp", e)
            }
        }

        when (icon) {
            AppLauncherIcon.DEFAULT -> {
                setComponentState(twilightComponent, false)
                setComponentState(pinkieComponent, false)
                setComponentState(defaultComponent, true)
            }
            AppLauncherIcon.TWILIGHT -> {
                setComponentState(defaultComponent, false)
                setComponentState(pinkieComponent, false)
                setComponentState(twilightComponent, true)
            }
            AppLauncherIcon.PINKIE -> {
                setComponentState(defaultComponent, false)
                setComponentState(twilightComponent, false)
                setComponentState(pinkieComponent, true)
            }
        }
    }
}
