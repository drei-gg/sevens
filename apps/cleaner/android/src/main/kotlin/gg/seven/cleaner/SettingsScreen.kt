package gg.seven.cleaner

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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Readable(Modifier.padding(vertical = Space.s)) {
            Section(
                title = "Media library",
                footer =
                    "Cleaner never downloads photos or videos. Items that live only in a gallery app’s cloud aren’t part of this list.",
            ) {
                item(
                    "On-device media",
                    supporting =
                        "Photos and videos come from Android’s media library on this phone.",
                )
            }
            Section(
                title = "Saved scan progress",
                footer =
                    "On Android 11 or later, completed comparisons stay on this device. Scan library checks access and changes before continuing. Closing Cleaner pauses work; it does not scan in the background.",
            ) {
                item(
                    "Saved measurements: $saved",
                    supporting =
                        "Clearing progress keeps your media, review decisions and activity history.",
                )
                content {
                    TextButton(
                        onClick = model::clearScanProgress,
                        enabled = !busy,
                        modifier = Modifier.testTag("clear-scan-progress"),
                    ) {
                        Text("Clear scan progress")
                    }
                }
            }
            Section(title = "Privacy") {
                content {
                    androidx.compose.material3.Text(
                        "No Cleaner account, analytics, ads or servers. Your chosen file provider may fetch cloud content when you open a folder."
                    )
                }
            }
            Section(title = "Preservation") {
                content {
                    androidx.compose.material3.Text(
                        "Compression saves a separate copy. The current still-image profile excludes HDR, RAW, motion photos, transparency and unverified formats."
                    )
                }
                content {
                    androidx.compose.material3.Text(
                        "Contact archives contain accessible fields. Notes aren’t read, so archives are labelled partial and destructive merges are not enabled."
                    )
                }
            }
            Section(
                title = "Open-source work",
                footer =
                    "Built with MediaStore, the Contacts Provider, the Storage Access Framework, ExifInterface and SevenKit. Full texts are under Open-source licenses.",
            ) {
                item("libphonenumber 9.0.43", supporting = "Google · Apache-2.0")
                item("Telephoto zoomable 0.18.0", supporting = "Saket Narayan · Apache-2.0")
            }
            SevenAbout(coreVersion = remember { coreVersion() })
        }
    }
}
