package gg.seven.scanner

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import gg.seven.kit.EmptyState
import gg.seven.kit.SevenAbout
import gg.seven.kit.SevenTopBar
import gg.seven.kit.Space
import gg.seven.scanner.core.coreVersion

/** The shell every port starts from: the home screen and Settings with the shared sections. */
@Composable
fun ScannerApp() {
    var settings by rememberSaveable { mutableStateOf(false) }
    BackHandler(settings) { settings = false }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            SevenTopBar(
                if (settings) "Settings" else stringResource(R.string.app_name),
                onBack = if (settings) { { settings = false } } else null,
            ) {
                if (!settings)
                    IconButton(onClick = { settings = true }) {
                        Icon(Icons.Outlined.Settings, "Settings")
                    }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.m, vertical = Space.s),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            if (settings) SevenAbout(coreVersion = remember { coreVersion() })
            else
                EmptyState(
                    "Coming to Android",
                    "Document Scanner runs on its shared core here; its screens are being ported from iOS.",
                    icon = Icons.Outlined.DocumentScanner,
                )
        }
    }
}
