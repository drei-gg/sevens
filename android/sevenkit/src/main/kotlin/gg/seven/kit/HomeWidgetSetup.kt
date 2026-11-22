package gg.seven.kit

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

/** Ask the launcher to pin a widget; the system owns placement and its confirmation. */
@Composable
fun HomeWidgetSetup(
    provider: ComponentName,
    title: String,
    description: String,
    enabled: Boolean = true,
) {
    val context = LocalContext.current
    val manager = remember(context) { AppWidgetManager.getInstance(context) }
    var message by remember { mutableStateOf<String?>(null) }
    Section(title = "Home-screen widget", footer = description) {
        if (manager.isRequestPinAppWidgetSupported) {
            action("Add $title widget", Icons.Outlined.Widgets, enabled = enabled) {
                message =
                    try {
                        if (manager.requestPinAppWidget(provider, null, null))
                            "Confirm placement in your launcher's widget prompt."
                        else "Long-press an empty space on your home screen, then choose Widgets."
                    } catch (_: RuntimeException) {
                        "Long-press an empty space on your home screen, then choose Widgets."
                    }
            }
        } else
            content {
                Text(
                    "Long-press an empty space on your home screen, then choose Widgets and $title."
                )
            }
        message?.let { text -> content { Text(text) } }
    }
}
