package org.kde.plasmawave.ui.components

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import androidx.annotation.DrawableRes
import java.io.File
import org.kde.plasmawave.MainActivity
import org.kde.plasmawave.R

enum class AppLauncherIcon(
    val title: String,
    val subtitle: String,
    @DrawableRes val previewRes: Int,
    val aliasClassName: String
) {
    DEFAULT(
        title = "Klasik (Orijinal)",
        subtitle = "Varsayılan uygulama simgesi",
        previewRes = R.drawable.ic_launcher_foreground,
        aliasClassName = "org.kde.plasmawave.MainActivityDefault"
    ),
    CUSTOM(
        title = "Özel PNG İkonu",
        subtitle = "Kendi yükleyip kırptığınız özel telefon uygulama simgesi",
        previewRes = R.drawable.ic_launcher_foreground,
        aliasClassName = "org.kde.plasmawave.MainActivityDefault"
    )
}

object LauncherIconManager {
    private const val TAG = "LauncherIconManager"
    private const val PREFS = "launcher_icon_prefs"
    private const val KEY_ICON = "selected_launcher_icon"
    private const val MAIN_ACTIVITY_CLASS = "org.kde.plasmawave.MainActivity"
    private val LEGACY_ALIASES = listOf(
        "org.kde.plasmawave.MainActivityTwilight",
        "org.kde.plasmawave.MainActivityPinkie"
    )
    const val CUSTOM_LAUNCHER_ICON_FILE = "custom_launcher_icon.png"
    private const val CUSTOM_SHORTCUT_ID = "custom_launcher_icon_shortcut"

    fun getSelectedIcon(context: Context): AppLauncherIcon {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_ICON, AppLauncherIcon.DEFAULT.name)
        return runCatching { AppLauncherIcon.valueOf(name ?: AppLauncherIcon.DEFAULT.name) }
            .getOrDefault(AppLauncherIcon.DEFAULT)
    }

    fun syncLauncherIcon(context: Context) {
        val selected = getSelectedIcon(context)
        setLauncherIcon(context, selected, requestPin = false)
    }

    fun setLauncherIcon(context: Context, icon: AppLauncherIcon, requestPin: Boolean = false) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ICON, icon.name).apply()

        val pm = context.packageManager
        val pkg = context.packageName

        // Always keep target MainActivity enabled so intents & running task are never killed
        try {
            val mainComp = ComponentName(pkg, MAIN_ACTIVITY_CLASS)
            pm.setComponentEnabledSetting(
                mainComp,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure MainActivity enabled", e)
        }

        // Enable MainActivityDefault alias
        val targetComponent = ComponentName(pkg, icon.aliasClassName)
        try {
            pm.setComponentEnabledSetting(
                targetComponent,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable launcher alias $targetComponent", e)
        }

        // Disable legacy aliases if they were previously enabled
        for (legacyAlias in LEGACY_ALIASES) {
            val comp = ComponentName(pkg, legacyAlias)
            try {
                pm.setComponentEnabledSetting(
                    comp,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            } catch (_: Exception) {}
        }

        if (icon == AppLauncherIcon.CUSTOM) {
            pinOrUpdateCustomLauncherShortcut(context, requestPin = requestPin)
        }
    }

    fun pinOrUpdateCustomLauncherShortcut(context: Context, requestPin: Boolean = true): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val file = File(context.filesDir, CUSTOM_LAUNCHER_ICON_FILE)
        if (!file.exists()) return false
        return try {
            val rawBmp = BitmapFactory.decodeFile(file.absolutePath) ?: return false
            // Create a 432x432 adaptive icon canvas with safe-zone inset so Android's circle mask doesn't clip edges
            val canvasSize = 432
            val inset = 64
            val adaptiveBmp = Bitmap.createBitmap(canvasSize, canvasSize, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(adaptiveBmp)
            canvas.drawColor(Color.parseColor("#181528"))
            val dstRect = Rect(inset, inset, canvasSize - inset, canvasSize - inset)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(rawBmp, null, dstRect, paint)

            val shortcutManager = context.getSystemService(ShortcutManager::class.java) ?: return false
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val shortcutInfo = ShortcutInfo.Builder(context, CUSTOM_SHORTCUT_ID)
                .setShortLabel("projekt_b-d")
                .setLongLabel("projekt_b-d")
                .setIcon(Icon.createWithAdaptiveBitmap(adaptiveBmp))
                .setIntent(launchIntent)
                .build()

            shortcutManager.setDynamicShortcuts(listOf(shortcutInfo))
            shortcutManager.updateShortcuts(listOf(shortcutInfo))

            if (requestPin && shortcutManager.isRequestPinShortcutSupported) {
                shortcutManager.requestPinShortcut(shortcutInfo, null)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pin or update custom launcher shortcut", e)
            false
        }
    }
}
