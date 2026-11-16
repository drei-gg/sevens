@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package gg.seven.cleaner

import android.Manifest
import android.app.Activity
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.seven.cleaner.core.MediaKind
import gg.seven.kit.LocalSevenGate
import gg.seven.kit.SevenTopBar
import gg.seven.kit.Space

/** The five peer areas, as on iOS. */
enum class Area(val title: String, val icon: ImageVector) {
    Overview("Overview", Icons.Outlined.GridView),
    Photos("Photos", Icons.Outlined.PhotoLibrary),
    Videos("Videos", Icons.Outlined.VideoLibrary),
    Files("Files", Icons.Outlined.Folder),
    Contacts("Contacts", Icons.Outlined.Contacts),
}

/** What screens can ask of the root: navigation and the actions that need the activity. */
class CleanerActions(
    val open: (String) -> Unit,
    val back: () -> Unit,
    val switchTo: (Area) -> Unit,
    val scanPhotos: () -> Unit,
    val scanContacts: (String?) -> Unit,
    val chooseFolder: () -> Unit,
)

val LocalActions = staticCompositionLocalOf<CleanerActions> { error("Inside CleanerApp only") }

/** Filters for one media library, kept by the root so the top bar's menus can change them. */
class LibraryFilters(category: String = "All", order: String = "Newest", query: String = "") {
    var category by mutableStateOf(category)
    var order by mutableStateOf(order)
    val search = androidx.compose.foundation.text.input.TextFieldState(query)
    val query: String
        get() = search.text.toString()
}

@Composable
private fun rememberFilters(key: String) =
    rememberSaveable(key, saver = listSaver(save = { listOf(it.category, it.order, it.query) }, restore = { LibraryFilters(it[0], it[1], it[2]) })) {
        LibraryFilters()
    }

@Composable
fun CleanerApp(model: CleanerModel) {
    val busy by model.busy.collectAsStateWithLifecycle()
    val progress by model.progress.collectAsStateWithLifecycle()
    val canCancel by model.canCancel.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val offersUnlock by model.errorOffersUnlock.collectAsStateWithLifecycle()
    val candidate by model.candidate.collectAsStateWithLifecycle()
    val request by model.systemRequest.collectAsStateWithLifecycle()
    val gate = LocalSevenGate.current
    var tab by rememberSaveable { mutableStateOf(Area.Overview) }
    val stacks = Area.entries.map { rememberSaveable(it.name) { mutableStateOf(emptyList<String>()) } }
    var stack by stacks[tab.ordinal]
    val photoFilters = rememberFilters("photo")
    val videoFilters = rememberFilters("video")

    val system = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val pending = model.systemRequest.value
        model.systemRequest.value = null
        pending?.onResult?.invoke(result.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(request) { request?.let { system.launch(IntentSenderRequest.Builder(it.intent).build()) } }
    val mediaPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { model.scanPhotos() }
    var contactRegion by rememberSaveable { mutableStateOf<String?>(null) }
    val contactPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.scanContacts(contactRegion) }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(model::scanFolder) }

    val actions =
        CleanerActions(
            open = { stack = stack + it },
            back = { stack = stack.dropLast(1) },
            switchTo = { tab = it },
            scanPhotos = {
                // The gate first, so a locked install never sees a permission prompt it can't use.
                if (!model.store.paidFeaturesAvailable) model.scanPhotos()
                else
                    mediaPermissions.launch(
                        when {
                            Build.VERSION.SDK_INT >= 34 ->
                                arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
                            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                        }
                    )
            },
            scanContacts = { region ->
                contactRegion = region
                if (!model.store.paidFeaturesAvailable) model.scanContacts(region) else contactPermission.launch(Manifest.permission.READ_CONTACTS)
            },
            chooseFolder = { if (!model.store.paidFeaturesAvailable) model.scanFolder(android.net.Uri.EMPTY) else folder.launch(null) },
        )
    BackHandler(stack.isNotEmpty()) { actions.back() }

    CompositionLocalProvider(LocalActions provides actions, LocalPhotos provides model.photos) {
        BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            val wide = maxWidth >= 700.dp && !largeText()
            val labelStyle = tabLabelStyle(maxWidth)
            Row(Modifier.fillMaxSize()) {
                if (wide)
                    NavigationRail(
                        Modifier.fillMaxHeight().windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Start + WindowInsetsSides.Top)),
                        containerColor = MaterialTheme.colorScheme.background,
                    ) {
                        Spacer(Modifier.height(Space.l))
                        Area.entries.forEach { area ->
                            NavigationRailItem(
                                selected = tab == area,
                                onClick = { if (tab == area) stack = emptyList() else tab = area },
                                icon = { Icon(area.icon, null) },
                                label = { Text(area.title) },
                                modifier = Modifier.testTag("nav-${area.title}"),
                            )
                        }
                    }
                Scaffold(
                    modifier = Modifier.weight(1f),
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = {
                        val route = stack.lastOrNull()
                        SevenTopBar(title(tab, route), onBack = if (route != null) actions.back else null) {
                            when {
                                route == null && tab == Area.Overview ->
                                    IconButton(onClick = { actions.open("settings") }) { Icon(Icons.Outlined.Settings, "Settings") }
                                route == null && tab == Area.Photos -> LibraryMenus(model, MediaKind.PHOTO, photoFilters)
                                route == null && tab == Area.Videos -> LibraryMenus(model, MediaKind.VIDEO, videoFilters)
                            }
                        }
                    },
                    bottomBar = {
                        Column {
                            if (busy) BusyBar(progress, canCancel, model::cancel)
                            if (!wide)
                                ShortNavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                                    Area.entries.forEach { area ->
                                        ShortNavigationBarItem(
                                            selected = tab == area,
                                            onClick = { if (tab == area) stack = emptyList() else tab = area },
                                            icon = { Icon(area.icon, null) },
                                            label = { Text(area.title, style = labelStyle, maxLines = 1, softWrap = false) },
                                            modifier = Modifier.testTag("nav-${area.title}"),
                                        )
                                    }
                                }
                            else Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
                        }
                    },
                ) { padding ->
                    Box(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
                        Screen(model, tab, stack.lastOrNull(), if (tab == Area.Videos) videoFilters else photoFilters)
                    }
                }
            }
        }
        candidate?.let { CompressionScreen(model, it) }
        error?.let { message ->
            AlertDialog(
                onDismissRequest = { model.error.value = null },
                title = { Text("Could not complete") },
                text = { Text(message) },
                confirmButton = { TextButton(onClick = { model.error.value = null }) { Text("OK") } },
                dismissButton =
                    if (offersUnlock) {
                        {
                            TextButton(onClick = {
                                model.error.value = null
                                gate.offer()
                            }) { Text("See the unlock") }
                        }
                    } else null,
            )
        }
    }
}

private fun title(tab: Area, route: String?): String =
    when {
        route == null -> if (tab == Area.Overview) "Cleaner" else tab.title
        route == "groups" -> "Compare groups"
        route == "pending" -> "Pending removal"
        route == "activity" -> "Activity"
        route == "settings" -> "Settings"
        route.startsWith("detail:") -> "Review"
        route == "contactGroups" -> "Contact candidates"
        route.startsWith("comparison:") -> "Compare contacts"
        route.startsWith("fields:") -> "Contact fields"
        else -> tab.title
    }

@Composable
private fun Screen(model: CleanerModel, tab: Area, route: String?, filters: LibraryFilters) {
    when {
        route == null ->
            when (tab) {
                Area.Overview -> Dashboard(model)
                Area.Photos -> MediaLibrary(model, MediaKind.PHOTO, filters)
                Area.Videos -> MediaLibrary(model, MediaKind.VIDEO, filters)
                Area.Files -> FilesScreen(model)
                Area.Contacts -> ContactsScreen(model)
            }
        route == "groups" -> GroupsScreen(model)
        route == "pending" -> PendingScreen(model)
        route == "activity" -> ActivityScreen(model)
        route == "settings" -> CleanerSettings()
        route.startsWith("detail:") -> MediaDetail(model, route.removePrefix("detail:"))
        route == "contactGroups" -> ContactGroupsScreen(model)
        route.startsWith("comparison:") -> ContactComparisonScreen(model, route.removePrefix("comparison:"))
        route.startsWith("fields:") -> ContactFieldsScreen(model, route.removePrefix("fields:"))
    }
}

/** iOS's bottom progress inset: what's running, and Cancel when it can stop. */
@Composable
private fun BusyBar(progress: String, canCancel: Boolean, cancel: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.m, vertical = Space.s).testTag("busy"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            CircularProgressIndicator(Modifier.size(Space.l), strokeWidth = 3.dp)
            Text(progress, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = cancel, enabled = canCancel) { Text("Cancel") }
        }
    }
}

/**
 * One label size for every tab: the largest, up to Material's, at which the longest label fits
 * its share of the bar on one line, so large font sizes shrink all five alike instead of breaking
 * a word.
 */
@Composable
private fun tabLabelStyle(barWidth: androidx.compose.ui.unit.Dp): androidx.compose.ui.text.TextStyle {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val base = MaterialTheme.typography.labelMedium
    return remember(barWidth, density, base) {
        val room = with(density) { (barWidth / Area.entries.size - Space.s).toPx() }
        var size = base.fontSize.value
        while (size > 9f && Area.entries.maxOf { measurer.measure(it.title, base.copy(fontSize = size.sp), maxLines = 1, softWrap = false).size.width } > room) size -= 0.5f
        base.copy(fontSize = size.sp)
    }
}
