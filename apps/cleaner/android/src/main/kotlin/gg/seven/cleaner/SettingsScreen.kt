package gg.seven.cleaner

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.seven.cleaner.core.coreVersion
import gg.seven.kit.Section
import gg.seven.kit.SevenAbout
import gg.seven.kit.Space
import gg.seven.kit.content
import gg.seven.kit.item

/**
 * Settings (iOS CleanerSettings): Cleaner's own sections, then SevenKit's. iOS's "Allow iCloud
 * downloads" has no counterpart: Android's media library lists files already on this phone, so
 * there is nothing to download; the section explains that instead.
 */
@Composable
fun CleanerSettings(model: CleanerModel) {
    val saved by model.savedMeasurements.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    // Section bodies are builders, not composables: their words are read here first.
    val onDevice = stringResource(R.string.settings_on_device)
    val onDeviceDetail = stringResource(R.string.settings_on_device_detail)
    val savedText = stringResource(R.string.settings_saved_measurements, saved)
    val clearDetail = stringResource(R.string.settings_clear_progress_detail)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Readable(Modifier.padding(vertical = Space.s)) {
            Section(
                title = stringResource(R.string.settings_media_library),
                footer = stringResource(R.string.settings_media_footer),
            ) {
                item(onDevice, supporting = onDeviceDetail)
            }
            Section(
                title = stringResource(R.string.settings_scan_progress),
                footer = stringResource(R.string.settings_scan_progress_footer),
            ) {
                item(savedText, supporting = clearDetail)
                content {
                    TextButton(
                        onClick = model::clearScanProgress,
                        enabled = !busy,
                        modifier = Modifier.testTag("clear-scan-progress"),
                    ) {
                        Text(stringResource(R.string.settings_clear_progress))
                    }
                }
            }
            Section(title = stringResource(R.string.settings_privacy)) {
                content {
                    androidx.compose.material3.Text(stringResource(R.string.settings_privacy_detail))
                }
            }
            Section(title = stringResource(R.string.settings_preservation)) {
                content {
                    androidx.compose.material3.Text(
                        stringResource(R.string.settings_preservation_compression)
                    )
                }
                content {
                    androidx.compose.material3.Text(
                        stringResource(R.string.settings_preservation_contacts)
                    )
                }
            }
            Section(
                title = stringResource(R.string.settings_open_source),
                footer = stringResource(R.string.settings_open_source_footer),
            ) {
                // Library names, versions and licences are names, not translatable copy.
                item("libphonenumber 9.0.43", supporting = "Google · Apache-2.0")
                item("Telephoto zoomable 0.18.0", supporting = "Saket Narayan · Apache-2.0")
            }
            SevenAbout(coreVersion = remember { coreVersion() })
        }
    }
}
