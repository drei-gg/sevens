package gg.seven.cleaner

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
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
import gg.seven.kit.DropdownField
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
    val context = LocalContext.current
    var region by rememberSaveable { mutableStateOf("") }
    var incomplete by rememberSaveable { mutableStateOf(false) }
    val search = rememberTextFieldState()
    val query = search.text.toString()
    val records = snapshot?.records.orEmpty()
    val shown = records.filter { (!incomplete || contactIsIncomplete(it)) && (query.isEmpty() || it.name.contains(query, ignoreCase = true)) }
    val countries = remember {
        listOf("" to "Not specified") + Locale.getISOCountries().map { it to Locale("", it).displayCountry }.sortedBy { it.second }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Space.l)) {
        item {
            Readable {
                CleanerHero(
                    if (records.isEmpty()) "People, neatly kept" else "Possible matches",
                    if (records.isEmpty()) "Familiar faces." else "%,d".format(matches.size),
                    "Compare the details. Keep every person distinct.",
                    Icons.Outlined.Contacts,
                    big = records.isNotEmpty(),
                )
                if (records.isEmpty()) PrimaryButton("Scan contacts", enabled = !busy) { actions.scanContacts(region.ifEmpty { null }) }
                else {
                    PrimaryButton("Compare contacts") { actions.open("contactGroups") }
                    CleanerTiles(
                        { m -> CleanerStat("Contact records", "%,d".format(records.size), Icons.Outlined.Badge, m) },
                        { m ->
                            CleanerStat(
                                "Missing details",
                                "%,d".format(records.count(::contactIsIncomplete)),
                                Icons.Outlined.PersonSearch,
                                m,
                                detail = if (incomplete) "Showing incomplete entries" else "Worth a closer look",
                                selected = incomplete,
                            ) { incomplete = !incomplete }
                        },
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        QuickAction("Scan contacts", Icons.Outlined.Refresh, enabled = !busy) { actions.scanContacts(region.ifEmpty { null }) }
                        QuickAction("Export fields", Icons.Outlined.Share, enabled = !busy) { model.exportContacts() }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    DropdownField("Phone number country", region, countries, Modifier.fillMaxWidth()) { region = it }
                    Text(
                        "Choose a country to compare local numbers. Without one, international numbers and email addresses are compared.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val current = snapshot
                if (current == null) CleanerNotice("Permission is requested when you scan. Contact comparison happens on this phone.", Icons.Outlined.PanTool)
                else {
                    if (current.limited) CleanerNotice("Only the contacts you allowed are included.", Icons.Outlined.HowToReg)
                    if (contactCrowdedIdentifierCount(current.records) > 0)
                        CleanerNotice("Some identifiers are shared by over 100 records. Those groups are not expanded into pairs in this build.")
                    SearchInput("Search contacts", state = search)
                    SectionRule(if (incomplete) "Missing details" else "Your contacts") {
                        if (incomplete) TextButton(onClick = { incomplete = false }) { Text("Show all contacts") }
                    }
                }
            }
        }
        items(shown, key = { it.id }) { contact ->
            Readable(Modifier.padding(top = Space.s)) {
                ListItem(
                    headlineContent = { Text(contact.name.ifEmpty { "Unnamed contact" }) },
                    supportingContent = {
                        Text(contact.fields.firstOrNull { it.key == "email" || it.key == "phone" }?.value ?: "Review missing details")
                    },
                    leadingContent = { Icon(Icons.Outlined.AccountCircle, null, tint = Hues.cool) },
                    trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.clip(MaterialTheme.shapes.medium).clickableRow { actions.open("fields:${contact.id}") },
                )
            }
        }
        if (snapshot != null)
            item {
                Readable(Modifier.padding(top = Space.l)) {
                    exportFile?.let { file ->
                        OutlinedButton(onClick = { share(context, file) }, Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.Share, null)
                            Spacer(Modifier.width(Space.s))
                            Text("Share partial contact archive")
                        }
                    }
                    CleanerNotice(
                        "Archives contain accessible fields. Notes and account relationships are not included. Original contacts are retained.",
                        Icons.Outlined.Inventory2,
                    )
                }
            }
    }
}

private fun Modifier.clickableRow(onClick: () -> Unit) = clickable(onClick = onClick)

private fun share(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
    val send =
        Intent(Intent.ACTION_SEND)
            .setType("application/json")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TITLE, "Partial contact archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Share partial contact archive"))
}

@Composable
fun ContactGroupsScreen(model: CleanerModel) {
    val actions = LocalActions.current
    val matches by model.contactMatches.collectAsStateWithLifecycle()
    val snapshot by model.contactSnapshot.collectAsStateWithLifecycle()
    val byId = snapshot?.records.orEmpty().associateBy { it.id }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Space.l)) {
        item { Readable { CleanerHero("Possible matches", "%,d".format(matches.size), "A shared number is a clue, not proof.", Icons.Outlined.People) } }
        items(matches, key = { it.id }) { match ->
            val a = byId[match.left]
            val b = byId[match.right]
            if (a != null && b != null)
                Readable(Modifier.padding(top = Space.s)) {
                    ListItem(
                        headlineContent = { Text(a.name.ifEmpty { "Unnamed contact" }, style = MaterialTheme.typography.titleMedium) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                                Text(b.name.ifEmpty { "Unnamed contact" }, style = MaterialTheme.typography.bodyLarge)
                                Text(match.reasons.joinToString("; "), style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        leadingContent = { Icon(Icons.Outlined.People, null, tint = Hues.cool) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.clip(MaterialTheme.shapes.medium).clickableRow { actions.open("comparison:${match.id}") },
                    )
                }
        }
        if (matches.isEmpty())
            item { EmptyState("No matches yet", "Scan contacts to compare possible duplicates.", icon = Icons.Outlined.People) }
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
        EmptyState("No longer a candidate", "Scan contacts again to compare.", icon = Icons.Outlined.People)
        return
    }
    val plan = remember(a, b) { newContactMergePlan(listOf(a, b), backupVerified = true, revisionUnchanged = true) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Readable(Modifier.padding(vertical = Space.l)) {
            Text("Compare the details", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
            Text(match.reasons.joinToString("; "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            BoxWithConstraints {
                if (maxWidth >= 560.dp && !largeText())
                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                        ContactFieldList(a, Modifier.weight(1f).fillMaxHeight())
                        ContactFieldList(b, Modifier.weight(1f).fillMaxHeight())
                    }
                else
                    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                        ContactFieldList(a)
                        ContactFieldList(b)
                    }
            }
            SectionRule("Preservation check")
            plan.blockers.forEach { CleanerNotice(it, Icons.Outlined.Warning, caution = true) }
            if (plan.conflicts.isNotEmpty())
                Text("Conflicting fields: " + plan.conflicts.joinToString(", ", transform = ::fieldTitle), style = MaterialTheme.typography.bodyLarge)
            CleanerNotice("Merging and restoration are still under validation. No source contact is removed by this build.", Icons.Outlined.HowToReg)
        }
    }
}

@Composable
fun ContactFieldsScreen(model: CleanerModel, id: String) {
    val snapshot by model.contactSnapshot.collectAsStateWithLifecycle()
    val record = snapshot?.records?.firstOrNull { it.id == id }
    if (record == null) {
        EmptyState("Contact not found", "Scan contacts again to review it.", icon = Icons.Outlined.AccountCircle)
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Readable(Modifier.padding(vertical = Space.m)) { ContactFieldList(record) }
    }
}

internal fun fieldTitle(key: String): String =
    mapOf(
        "givenName" to "First name",
        "familyName" to "Last name",
        "middleName" to "Middle name",
        "previousFamilyName" to "Previous last name",
        "prefix" to "Prefix",
        "suffix" to "Suffix",
        "nickname" to "Nickname",
        "phoneticGiven" to "Phonetic first name",
        "phoneticMiddle" to "Phonetic middle name",
        "phoneticFamily" to "Phonetic last name",
        "phoneticOrganization" to "Phonetic organization",
        "phone" to "Phone",
        "email" to "Email",
        "organization" to "Organization",
        "department" to "Department",
        "birthday" to "Birthday",
        "date" to "Date",
        "address" to "Address",
        "url" to "Website",
        "relation" to "Relationship",
        "social" to "Social profile",
        "instantMessage" to "Messaging",
        "jobTitle" to "Job title",
        "contactType" to "Record type",
        "photo" to "Contact photo",
    )[key] ?: key

/** One record's fields in a card (iOS ContactFieldList); values can be selected and copied. */
@Composable
fun ContactFieldList(record: ContactRecord, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Icon(Icons.Outlined.AccountCircle, null, Modifier.size(48.dp), tint = Hues.cool)
            Text(record.name.ifEmpty { "Unnamed contact" }, style = MaterialTheme.typography.titleLarge)
            Text(record.container, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            record.fields.forEach { field ->
                Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text(
                        if (field.label.isEmpty()) fieldTitle(field.key) else fieldTitle(field.key) + " · " + field.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SelectionContainer { Text(field.value, style = MaterialTheme.typography.bodyLarge) }
                }
            }
            CleanerNotice("Notes are not read in this build.", Icons.Outlined.StickyNote2)
        }
    }
}
