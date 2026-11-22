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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.seven.cleaner.core.MediaDateFilter
import gg.seven.cleaner.core.MediaKind
import gg.seven.kit.LocalSevenGate
import gg.seven.kit.SevenTopBar
import gg.seven.kit.Space
import kotlinx.coroutines.launch

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
    var dateMode by mutableStateOf(MediaDateFilter.ANY)
    var date by mutableStateOf(java.time.LocalDate.now())
    var size by mutableStateOf(gg.seven.cleaner.core.FileSizeFilter.ANY)
    var resolution by mutableStateOf(gg.seven.cleaner.core.MediaResolutionFilter.ANY)
    var duration by mutableStateOf(gg.seven.cleaner.core.MediaDurationFilter.ANY)
    var category by mutableStateOf(category)
    var order by mutableStateOf(order)
    val search = androidx.compose.foundation.text.input.TextFieldState(query)
    val query: String
        get() = search.text.toString()
}

@Composable
private fun rememberFilters(key: String) =
    rememberSaveable(
        key,
        saver =
            listSaver(
                save = {
                    listOf(
                        it.category,
                        it.order,
                        it.query,
                        it.dateMode.name,
                        it.date.toString(),
                        it.size.name,
                        it.resolution.name,
                        it.duration.name,
                    )
                },
                restore = { values ->
                    LibraryFilters(values[0], values[1], values[2]).apply {
                        dateMode =
                            runCatching { MediaDateFilter.valueOf(values.getOrNull(3).orEmpty()) }
                                .getOrDefault(MediaDateFilter.ANY)
                        date =
                            runCatching { java.time.LocalDate.parse(values.getOrNull(4)) }
                                .getOrDefault(java.time.LocalDate.now())
                        size =
                            runCatching {
                                    gg.seven.cleaner.core.FileSizeFilter.valueOf(
                                        values.getOrNull(5).orEmpty()
                                    )
                                }
                                .getOrDefault(gg.seven.cleaner.core.FileSizeFilter.ANY)
                        resolution =
                            runCatching {
                                    gg.seven.cleaner.core.MediaResolutionFilter.valueOf(
                                        values.getOrNull(6).orEmpty()
                                    )
                                }
                                .getOrDefault(gg.seven.cleaner.core.MediaResolutionFilter.ANY)
                        duration =
                            runCatching {
                                    gg.seven.cleaner.core.MediaDurationFilter.valueOf(
                                        values.getOrNull(7).orEmpty()
                                    )
                                }
                                .getOrDefault(gg.seven.cleaner.core.MediaDurationFilter.ANY)
                    }
                },
            ),
    ) {
        LibraryFilters()
    }

@Composable
fun CleanerApp(model: CleanerModel) {
    val busy by model.busy.collectAsStateWithLifecycle()
    val progress by model.progress.collectAsStateWithLifecycle()
    val fraction by model.comparisonFraction.collectAsStateWithLifecycle()
    val canCancel by model.canCancel.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val offersUnlock by model.errorOffersUnlock.collectAsStateWithLifecycle()
    val candidate by model.candidate.collectAsStateWithLifecycle()
    val compressionBatch by model.compressionBatch.collectAsStateWithLifecycle()
    val videoReview by model.videoReview.collectAsStateWithLifecycle()
    val request by model.systemRequest.collectAsStateWithLifecycle()
    val gate = LocalSevenGate.current
    var tab by rememberSaveable { mutableStateOf(Area.Overview) }
    val stacks =
        Area.entries.map { rememberSaveable(it.name) { mutableStateOf(emptyList<String>()) } }
    var stack by stacks[tab.ordinal]
    val photoFilters = rememberFilters("photo")
    val videoFilters = rememberFilters("video")
    val folderFilters = rememberFolderFilters()

    val system =
        rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            result ->
            val pending = model.systemRequest.value
            model.systemRequest.value = null
            pending?.onResult?.invoke(result.resultCode == Activity.RESULT_OK)
        }
    LaunchedEffect(request) {
        request?.let { system.launch(IntentSenderRequest.Builder(it.intent).build()) }
    }
    val mediaPermissions =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            model.scanPhotos()
        }
    var contactRegion by rememberSaveable { mutableStateOf<String?>(null) }
    val contactPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            model.scanContacts(contactRegion)
        }
    val folder =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let(model::scanFolder)
        }

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
                                arrayOf(
                                    Manifest.permission.READ_MEDIA_IMAGES,
                                    Manifest.permission.READ_MEDIA_VIDEO,
                                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                                )
                            Build.VERSION.SDK_INT >= 33 ->
                                arrayOf(
                                    Manifest.permission.READ_MEDIA_IMAGES,
                                    Manifest.permission.READ_MEDIA_VIDEO,
                                )
                            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                        }
                    )
            },
            scanContacts = { region ->
                contactRegion = region
                if (!model.store.paidFeaturesAvailable) model.scanContacts(region)
                else contactPermission.launch(Manifest.permission.READ_CONTACTS)
            },
            chooseFolder = {
                if (!model.store.paidFeaturesAvailable) model.scanFolder(android.net.Uri.EMPTY)
                else folder.launch(null)
            },
        )
    BackHandler(stack.isNotEmpty()) { actions.back() }

    CompositionLocalProvider(LocalActions provides actions, LocalPhotos provides model.photos) {
        BoxWithConstraints(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        ) {
            val wide = maxWidth >= 700.dp && !largeText()
            val useDrawer = !wide && LocalDensity.current.fontScale >= 1.5f
            val drawer = rememberDrawerState(DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            LaunchedEffect(useDrawer) { if (!useDrawer) drawer.close() }
            ModalNavigationDrawer(
                drawerState = drawer,
                gesturesEnabled = useDrawer,
                drawerContent = {
                    if (useDrawer)
                        ModalDrawerSheet {
                            Column(
                                Modifier.verticalScroll(
                                    androidx.compose.foundation.rememberScrollState()
                                )
                            ) {
                                Text(
                                    "Cleaner",
                                    Modifier.padding(Space.l),
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                Area.entries.forEach { area ->
                                    NavigationDrawerItem(
                                        label = { Text(area.title) },
                                        selected = tab == area,
                                        icon = { Icon(area.icon, null) },
                                        onClick = {
                                            if (tab == area) stack = emptyList() else tab = area
                                            scope.launch { drawer.close() }
                                        },
                                        modifier =
                                            Modifier.padding(horizontal = Space.s)
                                                .testTag("nav-${area.title}"),
                                    )
                                }
                            }
                        }
                },
            ) {
                Row(Modifier.fillMaxSize()) {
                    if (wide)
                        NavigationRail(
                            Modifier.fillMaxHeight()
                                .windowInsetsPadding(
                                    WindowInsets.systemBars.only(
                                        WindowInsetsSides.Start + WindowInsetsSides.Top
                                    )
                                ),
                            containerColor = MaterialTheme.colorScheme.background,
                        ) {
                            Spacer(Modifier.height(Space.l))
                            Area.entries.forEach { area ->
                                NavigationRailItem(
                                    selected = tab == area,
                                    onClick = {
                                        if (tab == area) stack = emptyList() else tab = area
                                    },
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
                            val menuActions: @Composable RowScope.() -> Unit = {
                                when {
                                    route == null && tab == Area.Overview ->
                                        IconButton(onClick = { actions.open("settings") }) {
                                            Icon(Icons.Outlined.Settings, "Settings")
                                        }
                                    route == null && tab == Area.Photos ->
                                        LibraryMenus(model, MediaKind.PHOTO, photoFilters)
                                    route == null && tab == Area.Videos ->
                                        LibraryMenus(model, MediaKind.VIDEO, videoFilters)
                                }
                            }
                            if (useDrawer && route == null) {
                                TopAppBar(
                                    title = { Text(title(tab, route)) },
                                    navigationIcon = {
                                        IconButton(onClick = { scope.launch { drawer.open() } }) {
                                            Icon(Icons.Outlined.Menu, "Navigation menu")
                                        }
                                    },
                                    actions = menuActions,
                                    colors =
                                        TopAppBarDefaults.topAppBarColors(
                                            containerColor = MaterialTheme.colorScheme.background
                                        ),
                                )
                            } else
                                SevenTopBar(
                                    title(tab, route),
                                    onBack = if (route != null) actions.back else null,
                                    actions = menuActions,
                                )
                        },
                        bottomBar = {
                            Column {
                                if (busy) BusyBar(progress, fraction, canCancel, model::cancel)
                                if (!wide && !useDrawer)
                                    ShortNavigationBar(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                                    ) {
                                        Area.entries.forEach { area ->
                                            ShortNavigationBarItem(
                                                selected = tab == area,
                                                onClick = {
                                                    if (tab == area) stack = emptyList()
                                                    else tab = area
                                                },
                                                icon = { Icon(area.icon, null) },
                                                label = {
                                                    Text(
                                                        area.title,
                                                        style = MaterialTheme.typography.labelMedium,
                                                    )
                                                },
                                                modifier = Modifier.testTag("nav-${area.title}"),
                                            )
                                        }
                                    }
                                else
                                    Spacer(
                                        Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                                    )
                            }
                        },
                    ) { padding ->
                        Box(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
                            Screen(
                                model,
                                tab,
                                stack.lastOrNull(),
                                if (tab == Area.Videos) videoFilters else photoFilters,
                                folderFilters,
                            )
                        }
                    }
                }
            }
        }
        candidate?.let { CompressionScreen(model, it) }
        videoReview?.let { VideoReviewScreen(model, it) }
        if (candidate == null && compressionBatch != null)
            CompressionBatchScreen(model, compressionBatch!!)
        error
            ?.takeIf { candidate == null && compressionBatch == null && videoReview == null }
            ?.let { message ->
                AlertDialog(
                    onDismissRequest = { model.error.value = null },
                    title = { Text("Could not complete") },
                    text = { Text(message) },
                    confirmButton = {
                        TextButton(onClick = { model.error.value = null }) { Text("OK") }
                    },
                    dismissButton =
                        if (offersUnlock) {
                            {
                                TextButton(
                                    onClick = {
                                        model.error.value = null
                                        gate.offer()
                                    }
                                ) {
                                    Text("See the unlock")
                                }
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
private fun Screen(
    model: CleanerModel,
    tab: Area,
    route: String?,
    filters: LibraryFilters,
    folderFilters: FolderFilters,
) {
    when {
        route == null ->
            when (tab) {
                Area.Overview -> Dashboard(model)
                Area.Photos -> MediaLibrary(model, MediaKind.PHOTO, filters)
                Area.Videos -> MediaLibrary(model, MediaKind.VIDEO, filters)
                Area.Files -> FilesScreen(model, folderFilters)
                Area.Contacts -> ContactsScreen(model)
            }
        route == "groups" -> GroupsScreen(model)
        route == "pending" -> PendingScreen(model)
        route == "activity" -> ActivityScreen(model)
        route == "settings" -> CleanerSettings(model)
        route.startsWith("detail:") -> MediaDetail(model, route.removePrefix("detail:"))
        route == "contactGroups" -> ContactGroupsScreen(model)
        route.startsWith("comparison:") ->
            ContactComparisonScreen(model, route.removePrefix("comparison:"))
        route.startsWith("fields:") -> ContactFieldsScreen(model, route.removePrefix("fields:"))
    }
}

/** Determinate comparison progress; other library/provider operations remain indeterminate. */
@Composable
private fun BusyBar(progress: String, fraction: Float?, canCancel: Boolean, cancel: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(
            Modifier.fillMaxWidth()
                .padding(horizontal = Space.m, vertical = Space.s)
                .testTag("busy"),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            if (fraction != null)
                LinearWavyProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().testTag("comparison-progress"),
                )
            else LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                Text(progress, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = cancel, enabled = canCancel) { Text("Cancel") }
            }
        }
    }
}
