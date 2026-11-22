package gg.seven.kit

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource

/** Ask the launcher to pin a widget; the system owns placement and its confirmation. */
@Composable
fun HomeWidgetSetup(
    provider: ComponentName,
    title: String,
    description: String,
    enabled: Boolean = true,
) {
    val context = LocalContext.current
    val res = LocalResources.current
    val manager = remember(context) { AppWidgetManager.getInstance(context) }
    var message by remember { mutableStateOf<String?>(null) }
    val addLabel = stringResource(R.string.seven_widget_add, title)
    val manualNamed = stringResource(R.string.seven_widget_manual_named, title)
    Section(title = stringResource(R.string.seven_widget_section), footer = description) {
        if (manager.isRequestPinAppWidgetSupported) {
            action(addLabel, Icons.Outlined.Widgets, enabled = enabled) {
                message =
                    res.getString(
                        try {
                            if (manager.requestPinAppWidget(provider, null, null))
                                R.string.seven_widget_confirm
                            else R.string.seven_widget_manual
                        } catch (_: RuntimeException) {
                            R.string.seven_widget_manual
                        }
                    )
            }
        } else content { Text(manualNamed) }
        message?.let { text -> content { Text(text) } }
    }
}
