package gg.seven.cleaner

import androidx.compose.ui.res.stringResource
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.seven.cleaner.core.ContactRecord
import gg.seven.cleaner.core.contactCrowdedIdentifierCount
import gg.seven.cleaner.core.contactIsIncomplete
import gg.seven.cleaner.core.newContactMergePlan
import gg.seven.kit.EmptyState
import gg.seven.kit.SearchInput
import gg.seven.kit.Space
import java.io.File
import java.util.Locale

/** Contact records, possible duplicates and the partial archive (iOS ContactsScreen). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ContactsScreen(model: CleanerModel) {
    val actions = LocalActions.current
    val snapshot by model.contactSnapshot.collectAsStateWithLifecycle()
    val matches by model.contactMatches.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val exportFile by model.exportFile.collectAsStateWithLifecycle()
    val savedArchive by model.savedContactArchive.collectAsStateWithLifecycle()
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    var region by rememberSaveable { mutableStateOf("") }
    var incomplete by rememberSaveable { mutableStateOf(false) }
    val search = rememberTextFieldState()
    val query = search.text.toString()
    val records = snapshot?.records.orEmpty()
    val shown =
        records.filter {
            (!incomplete || contactIsIncomplete(it)) &&
                (query.isEmpty() || it.name.contains(query, ignoreCase = true))
        }
    val notSpecified = stringResource(R.string.contacts_country_not_specified)
    val countries = remember(notSpecified) {
        listOf("" to notSpecified) +
            Locale.getISOCountries()
                .map { it to Locale("", it).displayCountry }
                .sortedBy { it.second }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Space.l)) {
        item {
            Readable {
                CleanerHero(
                    if (records.isEmpty()) stringResource(R.string.contacts_hero_empty_label)
                    else stringResource(R.string.contacts_possible_matches),
                    if (records.isEmpty()) stringResource(R.string.contacts_hero_empty_value)
                    else "%,d".format(matches.size),
                    stringResource(R.string.contacts_hero_detail),
                    Icons.Outlined.Contacts,
                    big = records.isNotEmpty(),
                )
                if (records.isEmpty())
                    PrimaryButton(stringResource(R.string.contacts_scan), enabled = !busy) {
                        actions.scanContacts(region.ifEmpty { null })
                    }
                else {
                    PrimaryButton(stringResource(R.string.contacts_compare)) { actions.open("contactGroups") }
                    CleanerTiles(
                        { m ->
                            CleanerStat(
                                stringResource(R.string.contacts_records),
                                "%,d".format(records.size),
                                Icons.Outlined.Badge,
                                m,
                            )
                        },
                        { m ->
                            CleanerStat(
                                stringResource(R.string.contacts_missing_details),
                                "%,d".format(records.count(::contactIsIncomplete)),
                                Icons.Outlined.PersonSearch,
                                m,
                                detail =
                                    if (incomplete) stringResource(R.string.contacts_showing_incomplete)
                                    else stringResource(R.string.contacts_worth_a_look),
                                selected = incomplete,
                            ) {
                                incomplete = !incomplete
                            }
                        },
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        QuickAction(
                            stringResource(R.string.contacts_scan),
                            Icons.Outlined.Refresh,
                            enabled = !busy,
                        ) {
                            actions.scanContacts(region.ifEmpty { null })
                        }
                        QuickAction(
                            stringResource(R.string.contacts_export_fields),
                            Icons.Outlined.Share,
                            enabled = !busy,
                        ) {
                            model.exportContacts()
                        }
                    }
                }
                exportFile?.let { file ->
                    OutlinedButton(onClick = { share(context, file) }, Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Share, null)
                        Spacer(Modifier.width(Space.s))
                        Text(stringResource(R.string.contacts_share_partial))
                    }
                }
                savedArchive?.let { archive ->
                    SavedArchiveCard(
                        archive,
                        enabled = !busy,
                        share = { model.shareSavedContacts { share(context, it) } },
                        delete = { confirmDelete = true },
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    CountryField(region, countries) { region = it }
                    Text(
                        stringResource(R.string.contacts_country_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val current = snapshot
                if (current == null)
                    CleanerNotice(
                        stringResource(R.string.contacts_permission_note),
                        Icons.Outlined.PanTool,
                    )
                else {
                    if (current.limited)
                        CleanerNotice(
                            stringResource(R.string.contacts_limited),
                            Icons.Outlined.HowToReg,
                        )
                    if (contactCrowdedIdentifierCount(current.records) > 0)
                        CleanerNotice(
                            stringResource(R.string.contacts_crowded)
                        )
                    SearchInput(stringResource(R.string.contacts_search), state = search)
                    FilterChip(
                        selected = incomplete,
                        onClick = { incomplete = !incomplete },
                        label = { Text(stringResource(R.string.contacts_missing_only)) },
                    )
                    SectionRule(
                        if (incomplete) stringResource(R.string.contacts_missing_details)
                        else stringResource(R.string.contacts_yours)
                    ) {
                        if (incomplete)
                            TextButton(onClick = { incomplete = false }) {
                                Text(stringResource(R.string.contacts_show_all))
                            }
                    }
                }
            }
        }
        items(shown, key = { it.id }) { contact ->
            Readable(Modifier.padding(top = Space.s)) {
                ListItem(
                    content = {
                        Text(contact.name.ifEmpty { stringResource(R.string.contacts_unnamed) })
                    },
                    supportingContent = {
                        Text(
                            contact.fields
                                .firstOrNull { it.key == "email" || it.key == "phone" }
                                ?.value ?: stringResource(R.string.contacts_review_missing)
                        )
                    },
                    leadingContent = { Icon(Icons.Outlined.AccountCircle, null, tint = Hues.cool) },
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null)
                    },
                    colors =
                        ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        ),
                    onClick = { actions.open("fields:${contact.id}") },
                )
            }
        }
        if (snapshot != null)
            item {
                Readable(Modifier.padding(top = Space.l)) {
                    CleanerNotice(
                        stringResource(R.string.contacts_archive_note),
                        Icons.Outlined.Inventory2,
                    )
                }
            }
    }
    if (confirmDelete)
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.contacts_delete_archive_title)) },
            text = {
                Text(stringResource(R.string.contacts_delete_archive_text))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        model.deleteSavedContacts()
                    },
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                ) {
                    Text(stringResource(R.string.contacts_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
}

/** The kept archive: what it holds, when it was made, Share and Delete. Local, never backed up. */
@Composable
private fun SavedArchiveCard(
    archive: SavedContactArchive,
    enabled: Boolean,
    share: () -> Unit,
    delete: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(stringResource(R.string.contacts_saved_archive), style = MaterialTheme.typography.titleMedium)
            Text(
                archive.records?.let { count(R.plurals.archived, it.size) }
                    ?: stringResource(R.string.contacts_saved_unreadable),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                gg.seven.kit.SevenFormat.date(
                    archive.saved.atZone(java.time.ZoneId.systemDefault())
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.contacts_saved_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                OutlinedButton(onClick = share, enabled = enabled && archive.records != null) {
                    Icon(Icons.Outlined.Share, null)
                    Spacer(Modifier.width(Space.s))
                    Text(stringResource(R.string.contacts_share))
                }
                TextButton(
                    onClick = delete,
                    enabled = enabled,
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                ) {
                    Text(stringResource(R.string.contacts_delete))
                }
            }
        }
    }
}

private fun share(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
    val send =
        Intent(Intent.ACTION_SEND)
            .setType("application/json")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TITLE, context.getString(R.string.contacts_partial_archive_title))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(
        Intent.createChooser(send, context.getString(R.string.contacts_share_partial))
    )
}

@Composable
fun ContactGroupsScreen(model: CleanerModel) {
    val actions = LocalActions.current
    val matches by model.contactMatches.collectAsStateWithLifecycle()
    val snapshot by model.contactSnapshot.collectAsStateWithLifecycle()
    val byId = snapshot?.records.orEmpty().associateBy { it.id }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Space.l)) {
        item {
            Readable {
                CleanerHero(
                    stringResource(R.string.contacts_possible_matches),
                    "%,d".format(matches.size),
                    stringResource(R.string.contacts_clue_not_proof),
                    Icons.Outlined.People,
                )
            }
        }
        items(matches, key = { it.id }) { match ->
            val a = byId[match.left]
            val b = byId[match.right]
            if (a != null && b != null)
                Readable(Modifier.padding(top = Space.s)) {
                    ListItem(
                        content = {
                            Text(
                                a.name.ifEmpty { stringResource(R.string.contacts_unnamed) },
                                style = MaterialTheme.typography.titleMedium,
                            )
                        },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                                Text(
                                    b.name.ifEmpty { stringResource(R.string.contacts_unnamed) },
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    match.reasons.joinToString("; "),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        },
                        leadingContent = { Icon(Icons.Outlined.People, null, tint = Hues.cool) },
                        colors =
                            ListItemDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            ),
                        onClick = { actions.open("comparison:${match.id}") },
                    )
                }
        }
        if (matches.isEmpty())
            item {
                EmptyState(
                    stringResource(R.string.contacts_no_matches),
                    stringResource(R.string.contacts_no_matches_detail),
                    icon = Icons.Outlined.People,
                )
            }
    }
}

@Composable
fun ContactComparisonScreen(model: CleanerModel, matchId: String) {
    val matches by model.contactMatches.collectAsStateWithLifecycle()
    val snapshot by model.contactSnapshot.collectAsStateWithLifecycle()
    val match = matches.firstOrNull { it.id == matchId }
    val records = snapshot?.records.orEmpty()
    val a = records.firstOrNull { it.id == match?.left }
    val b = records.firstOrNull { it.id == match?.right }
    if (match == null || a == null || b == null) {
        EmptyState(
            stringResource(R.string.contacts_no_longer_candidate),
            stringResource(R.string.contacts_scan_again_compare),
            icon = Icons.Outlined.People,
        )
        return
    }
    val saved by model.savedContactArchive.collectAsStateWithLifecycle()
    // Backed up only when the kept, read-back archive holds these exact record versions.
    val backedUp = saved?.covers(a, b) == true
    val plan =
        remember(a, b, backedUp) {
            newContactMergePlan(listOf(a, b), backupVerified = backedUp, revisionUnchanged = true)
        }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Readable(Modifier.padding(vertical = Space.l)) {
            Text(
                stringResource(R.string.contacts_compare_details),
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                match.reasons.joinToString("; "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BoxWithConstraints {
                if (maxWidth >= 560.dp && !largeText())
                    Row(
                        Modifier.height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(Space.m),
                    ) {
                        ContactFieldList(a, Modifier.weight(1f).fillMaxHeight())
                        ContactFieldList(b, Modifier.weight(1f).fillMaxHeight())
                    }
                else
                    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                        ContactFieldList(a)
                        ContactFieldList(b)
                    }
            }
            SectionRule(stringResource(R.string.contacts_preservation_check))
            plan.blockers.forEach { CleanerNotice(it, Icons.Outlined.Warning, caution = true) }
            if (plan.conflicts.isNotEmpty())
                Text(
                    stringResource(
                        R.string.contacts_conflicting_fields,
                        plan.conflicts.map { fieldTitle(it) }.joinToString(", "),
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
            CleanerNotice(
                stringResource(R.string.contacts_merge_not_enabled),
                Icons.Outlined.HowToReg,
            )
        }
    }
}

@Composable
fun ContactFieldsScreen(model: CleanerModel, id: String) {
    val snapshot by model.contactSnapshot.collectAsStateWithLifecycle()
    val record = snapshot?.records?.firstOrNull { it.id == id }
    if (record == null) {
        EmptyState(
            stringResource(R.string.contacts_not_found),
            stringResource(R.string.contacts_scan_again_review),
            icon = Icons.Outlined.AccountCircle,
        )
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Readable(Modifier.padding(vertical = Space.m)) { ContactFieldList(record) }
    }
}

/** A field key's name on screen; the keys themselves are the archive's and stay as they are. */
private val fieldTitles =
    mapOf(
        "givenName" to R.string.contacts_field_given_name,
        "familyName" to R.string.contacts_field_family_name,
        "middleName" to R.string.contacts_field_middle_name,
        "previousFamilyName" to R.string.contacts_field_previous_family_name,
        "prefix" to R.string.contacts_field_prefix,
        "suffix" to R.string.contacts_field_suffix,
        "nickname" to R.string.contacts_field_nickname,
        "phoneticGiven" to R.string.contacts_field_phonetic_given,
        "phoneticMiddle" to R.string.contacts_field_phonetic_middle,
        "phoneticFamily" to R.string.contacts_field_phonetic_family,
        "phoneticOrganization" to R.string.contacts_field_phonetic_organization,
        "phone" to R.string.contacts_field_phone,
        "email" to R.string.contacts_field_email,
        "organization" to R.string.contacts_field_organization,
        "department" to R.string.contacts_field_department,
        "birthday" to R.string.contacts_field_birthday,
        "date" to R.string.contacts_field_date,
        "address" to R.string.contacts_field_address,
        "url" to R.string.contacts_field_url,
        "relation" to R.string.contacts_field_relation,
        "social" to R.string.contacts_field_social,
        "instantMessage" to R.string.contacts_field_instant_message,
        "jobTitle" to R.string.contacts_field_job_title,
        "contactType" to R.string.contacts_field_contact_type,
        "photo" to R.string.contacts_field_photo,
    )

@Composable
@ReadOnlyComposable
internal fun fieldTitle(key: String): String = fieldTitles[key]?.let { stringResource(it) } ?: key

/** One record's fields in a card (iOS ContactFieldList); values can be selected and copied. */
@Composable
fun ContactFieldList(record: ContactRecord, modifier: Modifier = Modifier) {
    Card(
        modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Icon(Icons.Outlined.AccountCircle, null, Modifier.size(48.dp), tint = Hues.cool)
            Text(
                record.name.ifEmpty { stringResource(R.string.contacts_unnamed) },
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                if (record.container == LOCAL_CONTAINER) stringResource(R.string.contacts_this_phone)
                else record.container,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            record.fields.forEach { field ->
                Column(
                    Modifier.semantics(mergeDescendants = true) {},
                    verticalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    Text(
                        if (field.label.isEmpty()) fieldTitle(field.key)
                        else
                            stringResource(
                                R.string.contacts_field_with_label,
                                fieldTitle(field.key),
                                field.label,
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SelectionContainer {
                        Text(field.value, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            CleanerNotice(
                stringResource(R.string.contacts_notes_not_read),
                Icons.Outlined.StickyNote2,
            )
        }
    }
}

/**
 * The phone number country (iOS's navigation-link picker of every region): Material's editable
 * exposed dropdown, so typing a few letters narrows some 250 countries.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun CountryField(
    selected: String,
    countries: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val name = countries.firstOrNull { it.first == selected }?.second.orEmpty()
    var text by remember(selected) { mutableStateOf(if (selected.isEmpty()) "" else name) }
    val shown =
        if (text.isEmpty() || text == name) countries
        else countries.filter { it.second.contains(text, ignoreCase = true) }
    ExposedDropdownMenuBox(expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                expanded = true
            },
            label = { Text(stringResource(R.string.contacts_country_label)) },
            placeholder = { Text(stringResource(R.string.contacts_country_not_specified)) },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier =
                Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
        )
        ExposedDropdownMenu(
            expanded,
            onDismissRequest = {
                expanded = false
                text = if (selected.isEmpty()) "" else name
            },
        ) {
            shown.forEach { (code, country) ->
                DropdownMenuItem(
                    text = { Text(country) },
                    onClick = {
                        onSelect(code)
                        text = if (code.isEmpty()) "" else country
                        expanded = false
                    },
                )
            }
        }
    }
}
