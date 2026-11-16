@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package gg.seven.qr

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.github.skydoves.colorpicker.compose.BrightnessSlider
import com.github.skydoves.colorpicker.compose.HsvColorPicker
import com.github.skydoves.colorpicker.compose.rememberColorPickerController
import gg.seven.kit.ConnectedChoice
import gg.seven.kit.DateTimeField
import gg.seven.kit.DropdownField
import gg.seven.kit.Space
import gg.seven.kit.numeric
import gg.seven.qr.core.BitMatrix
import gg.seven.qr.core.ContrastWarning
import gg.seven.qr.core.HistoryKind
import gg.seven.qr.core.MakeRequest
import gg.seven.qr.core.PaperSize
import gg.seven.qr.core.RgbColor
import gg.seven.qr.core.SheetGrid
import gg.seven.qr.core.contrastWarnings
import gg.seven.qr.core.linkCompacted
import gg.seven.qr.core.linkRemovingTracking
import gg.seven.qr.core.linkTrackingParameters
import gg.seven.qr.core.payloadEncode
import gg.seven.qr.core.rgbHex
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Make (SPEC §8): a code from one of nine kinds of content, live as you type, styled and exported.
 * The caller gates this screen (Make and export are paid). The code is the screen's hero; Export is
 * its one primary action, pinned within reach once there is a code, out of the way while typing.
 */

/** What generation produced for one input. */
private data class Generated(
    val matrix: BitMatrix,
    val preview: Bitmap?,
    /** The text actually encoded. */
    val text: String,
    val typed: String,
    /** The capitalised link makes a smaller QR version than the typed one. */
    val smallerAvailable: Boolean,
    val trackingTags: List<String>,
    val trackingFree: String?,
)

private data class InputKey(val kind: MakeFormKind, val form: MakeFormState, val symbology: MakeSymbology, val correction: Correction, val fg: RgbColor, val bg: RgbColor, val smaller: Boolean)

/** Generates the code for one input off the main thread. Throws MakeException. */
private fun generate(request: MakeRequest, key: InputKey): Generated {
    val typed = payloadEncode(request)
    var matrix = CodeGenerator.generate(typed, key.symbology, key.correction)
    var text = typed
    var smaller = false
    val isLink = key.kind == MakeFormKind.LINK
    if (isLink && key.symbology == MakeSymbology.QR) {
        val compact = linkCompacted(typed)
        if (compact != null && compact != typed) {
            val c = runCatching { CodeGenerator.generate(compact, key.symbology, key.correction) }.getOrNull()
            val before = CodeGenerator.qrVersion(matrix)
            val after = c?.let(CodeGenerator::qrVersion)
            if (c != null && before != null && after != null && after < before) {
                smaller = true
                if (key.smaller) {
                    matrix = c
                    text = compact
                }
            }
        }
    }
    var tags = emptyList<String>()
    var free: String? = null
    if (isLink) {
        val params = QrLists.shared().trackingParamList()
        tags = linkTrackingParameters(typed, params)
        free = linkRemovingTracking(typed, params)
    }
    return Generated(matrix, PngRenderer.bitmap(matrix, 512, key.fg, key.bg), text, typed, smaller, tags, free)
}

private fun Color.rgb() = RgbColor(red.toDouble(), green.toDouble(), blue.toDouble())

private fun RgbColor.color() = Color(red.toFloat(), green.toFloat(), blue.toFloat())

@Composable
fun MakeScreen(model: QrModel, nav: QrNav, prefill: MakeRequest?) {
    var kind by remember { mutableStateOf(prefill?.let { MakeFormKind.of(it) } ?: MakeFormKind.LINK) }
    var form by remember { mutableStateOf(prefill?.let { MakeFormState.prefill(it) } ?: MakeFormState()) }
    var symbology by remember { mutableStateOf(MakeSymbology.QR) }
    var correction by remember { mutableStateOf(Correction.AUTO) }
    var foreground by remember { mutableStateOf(BLACK) }
    var background by remember { mutableStateOf(WHITE) }
    var useSmaller by remember { mutableStateOf(true) }
    var generated by remember { mutableStateOf<Generated?>(null) }
    var error by remember { mutableStateOf<MakeError?>(null) }
    var generatedKey by remember { mutableStateOf<InputKey?>(null) }
    var showExport by remember { mutableStateOf(false) }
    val recorded = remember { mutableSetOf<String>() }
    val key = InputKey(kind, form, symbology, correction, foreground, background, useSmaller)
    val outcome = form.result(kind)
    val issue = (outcome as? MakeOutcome.Invalid)?.issue
    val isBlank = issue?.isBlank == true
    val canExport = generated != null && error == null && generatedKey == key
    val largeText = LocalDensity.current.fontScale >= 1.5f
    val imeVisible = WindowInsets.isImeVisible

    // Regenerates after a short pause in typing.
    LaunchedEffect(key) {
        delay(200)
        val request = (outcome as? MakeOutcome.Request)?.request
        if (request == null) {
            generated = null
            error = null
            generatedKey = key
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.Default) { runCatching { generate(request, key) } }
        generatedKey = key
        result
            .onSuccess {
                generated = it
                error = null
            }
            .onFailure {
                generated = null
                error = (it as? MakeException)?.error ?: MakeError.GENERATOR_FAILED
            }
    }

    /** Saves the code to History once per payload and symbology; the model checks Keep history and the lock. */
    fun recordUse() {
        val g = generated ?: return
        if (!canExport) return
        if (!recorded.add(symbology.raw + "\u001F" + g.text)) return
        model.recordMade(textCode(g.text, symbology.symbology))
    }

    fun done() {
        recordUse()
        nav.pop()
    }
    BackHandler { done() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Make a code") },
                navigationIcon = { IconButton(onClick = ::done, Modifier.testTag("make.done")) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Done") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (canExport && !imeVisible)
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Space.m).padding(top = Space.ms, bottom = Space.s), contentAlignment = Alignment.Center) {
                        Button(onClick = { showExport = true }, modifier = Modifier.widthIn(max = 672.dp).fillMaxWidth().heightIn(min = 56.dp).testTag("make.export")) {
                            Icon(Icons.Rounded.Share, null, Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            Text("Export")
                        }
                    }
                }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            val preview: @Composable () -> Unit = {
                Preview(generated?.takeIf { error == null }, symbology, isBlank, issue, Modifier.padding(horizontal = Space.m))
            }
            // At the largest text sizes the code would push the fields off screen: it follows them.
            if (!largeText) preview()
            KindChips(kind) { kind = it }
            Column(Modifier.padding(horizontal = Space.m), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                Text(kind.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                MakeFields(kind, form, issue) { form = it }
                error?.let { InlineMessage(it.message, isError = true) }
            }
            if (largeText) preview()
            val g = generated
            if (kind == MakeFormKind.LINK && g != null && (g.smallerAvailable || g.trackingTags.isNotEmpty()))
                LinkOptions(g, useSmaller, { useSmaller = it }) { g.trackingFree?.let { form = form.copy(link = it) } }
            CodeOptions(symbology, correction, { symbology = it }, { correction = it })
            ColorsSection(foreground, background, { foreground = it }, { background = it })
        }
    }
    val g = generated
    if (showExport && g != null)
        ExportSheet(g.matrix, kind, symbology, foreground, background, onUse = ::recordUse) { showExport = false }
}

/** The hero: the code itself, big on a card, updating as you type. */
@Composable
private fun Preview(generated: Generated?, symbology: MakeSymbology, isBlank: Boolean, issue: MakeFormIssue?, modifier: Modifier) {
    QrCard(modifier) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.m)) {
            val oneRow = generated != null && generated.matrix.height == 1L
            Box(
                Modifier.widthIn(max = 240.dp).fillMaxWidth().aspectRatio(if (oneRow) 3f else 1f).background(MaterialTheme.colorScheme.background, MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center,
            ) {
                val bitmap = generated?.preview
                if (bitmap != null)
                    Image(
                        bitmap.asImageBitmap(),
                        contentDescription = symbology.accessibilityLabel(generated.typed),
                        modifier = Modifier.fillMaxSize().padding(Space.s).testTag("make.code"),
                        filterQuality = FilterQuality.None,
                    )
                else Icon(Icons.Rounded.QrCode2, null, Modifier.fillMaxSize().padding(Space.xl), tint = MaterialTheme.colorScheme.outlineVariant)
            }
            when {
                isBlank -> InlineMessage("Fill in the fields below and your code appears here.", isError = false)
                issue != null -> InlineMessage(issue.message, isError = true)
            }
        }
    }
}

/** The message under a field: icon plus words, never colour alone. */
@Composable
fun InlineMessage(text: String, isError: Boolean) {
    Row(
        Modifier.semantics(mergeDescendants = true) {}.testTag(if (isError) "make.error" else "make.hint"),
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(if (isError) Icons.Rounded.ErrorOutline else Icons.Rounded.Info, null, Modifier.size(18.dp), tint = if (isError) QrColors.danger else QrColors.muted)
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

/** The kinds as one row of chips, the selected one filled: one tap to switch. */
@Composable
private fun KindChips(kind: MakeFormKind, onSelect: (MakeFormKind) -> Unit) {
    val view = LocalView.current
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.m).testTag("make.type").semantics { contentDescription = "What to put in the code" },
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        MakeFormKind.entries.forEach { option ->
            FilterChip(
                selected = option == kind,
                onClick = {
                    if (option != kind) view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
                    onSelect(option)
                },
                label = { Text(option.title) },
                leadingIcon = { Icon(VerdictIcons.type(option.typeId), null, Modifier.size(FilterChipDefaults.IconSize)) },
                modifier = Modifier.heightIn(min = 48.dp).semantics { role = Role.RadioButton },
            )
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    tag: String,
    issue: MakeFormIssue? = null,
    field: MakeFormField? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    caps: KeyboardCapitalization = KeyboardCapitalization.None,
    autocorrect: Boolean = false,
    multiline: Boolean = false,
) {
    val shown = issue?.takeIf { field != null && it.field == field && !it.isBlank }
    OutlinedTextField(
        value,
        onChange,
        modifier = Modifier.fillMaxWidth().testTag(tag),
        label = { Text(label) },
        isError = shown != null,
        supportingText = shown?.let { { Text(it.message) } },
        singleLine = !multiline,
        minLines = if (multiline) 2 else 1,
        maxLines = if (multiline) 8 else 3,
        keyboardOptions = KeyboardOptions(capitalization = caps, autoCorrectEnabled = autocorrect, keyboardType = keyboard, imeAction = if (multiline) ImeAction.Default else ImeAction.Next),
    )
}

/** The fields of whichever form is chosen. Pure input: MakeFormState validates. */
@Composable
private fun MakeFields(kind: MakeFormKind, form: MakeFormState, issue: MakeFormIssue?, onChange: (MakeFormState) -> Unit) {
    val sentences = KeyboardCapitalization.Sentences
    val words = KeyboardCapitalization.Words
    when (kind) {
        MakeFormKind.LINK -> Field("Web address", form.link, { onChange(form.copy(link = it)) }, "make.field.link", issue, MakeFormField.LINK, KeyboardType.Uri)
        MakeFormKind.TEXT -> Field("Text", form.text, { onChange(form.copy(text = it)) }, "make.field.text", issue, MakeFormField.TEXT, caps = sentences, autocorrect = true, multiline = true)
        MakeFormKind.WIFI -> {
            Field("Network name", form.ssid, { onChange(form.copy(ssid = it)) }, "make.field.ssid", issue, MakeFormField.SSID)
            DropdownField("Security", form.wifiSecurity, MakeWiFiSecurity.entries.map { it to it.title }, Modifier.fillMaxWidth().testTag("make.field.security")) { onChange(form.copy(wifiSecurity = it)) }
            if (form.wifiSecurity != MakeWiFiSecurity.NONE)
                Field("Password", form.wifiPassword, { onChange(form.copy(wifiPassword = it)) }, "make.field.wifiPassword", issue, MakeFormField.WIFI_PASSWORD, KeyboardType.Password)
            SwitchRow("Hidden network", form.wifiHidden, "make.field.hidden") { onChange(form.copy(wifiHidden = it)) }
        }
        MakeFormKind.CONTACT -> {
            Field("First name", form.givenName, { onChange(form.copy(givenName = it)) }, "make.field.givenName", issue, MakeFormField.CONTACT_NAME, caps = words, autocorrect = true)
            Field("Last name", form.familyName, { onChange(form.copy(familyName = it)) }, "make.field.familyName", caps = words, autocorrect = true)
            Field("Company", form.organization, { onChange(form.copy(organization = it)) }, "make.field.organization", caps = words, autocorrect = true)
            Field("Job title", form.jobTitle, { onChange(form.copy(jobTitle = it)) }, "make.field.jobTitle", caps = words, autocorrect = true)
            Field("Phone", form.contactPhone, { onChange(form.copy(contactPhone = it)) }, "make.field.contactPhone", issue, MakeFormField.CONTACT_PHONE, KeyboardType.Phone)
            Field("Email", form.contactEmail, { onChange(form.copy(contactEmail = it)) }, "make.field.contactEmail", issue, MakeFormField.CONTACT_EMAIL, KeyboardType.Email)
            Field("Website", form.contactUrl, { onChange(form.copy(contactUrl = it)) }, "make.field.contactURL", keyboard = KeyboardType.Uri)
            Field("Address", form.contactAddress, { onChange(form.copy(contactAddress = it)) }, "make.field.address", caps = words, autocorrect = true, multiline = true)
            Field("Note", form.contactNote, { onChange(form.copy(contactNote = it)) }, "make.field.note", caps = sentences, autocorrect = true, multiline = true)
        }
        MakeFormKind.EVENT -> {
            Field("Title", form.eventTitle, { onChange(form.copy(eventTitle = it)) }, "make.field.eventTitle", issue, MakeFormField.EVENT_TITLE, caps = sentences, autocorrect = true)
            SwitchRow("All day", form.eventAllDay, "make.field.allDay") { onChange(form.copy(eventAllDay = it)) }
            DateTimeField("Starts", form.eventStart.toDouble(), withTime = !form.eventAllDay) { onChange(form.copy(eventStart = it.toLong())) }
            DateTimeField("Ends", form.eventEnd.toDouble(), withTime = !form.eventAllDay) { onChange(form.copy(eventEnd = it.toLong())) }
            issue?.takeIf { it.field == MakeFormField.EVENT_END }?.let { InlineMessage(it.message, isError = true) }
            Field("Location", form.eventLocation, { onChange(form.copy(eventLocation = it)) }, "make.field.eventLocation", caps = words, autocorrect = true)
            Field("Web address", form.eventUrl, { onChange(form.copy(eventUrl = it)) }, "make.field.eventURL", keyboard = KeyboardType.Uri)
            Field("Notes", form.eventDescription, { onChange(form.copy(eventDescription = it)) }, "make.field.eventNotes", caps = sentences, autocorrect = true, multiline = true)
        }
        MakeFormKind.EMAIL -> {
            Field("Email address", form.emailAddress, { onChange(form.copy(emailAddress = it)) }, "make.field.email", issue, MakeFormField.EMAIL_ADDRESS, KeyboardType.Email)
            Field("Subject (optional)", form.emailSubject, { onChange(form.copy(emailSubject = it)) }, "make.field.subject", caps = sentences, autocorrect = true)
            Field("Message (optional)", form.emailBody, { onChange(form.copy(emailBody = it)) }, "make.field.body", caps = sentences, autocorrect = true, multiline = true)
        }
        MakeFormKind.SMS -> {
            Field("Phone number", form.smsNumber, { onChange(form.copy(smsNumber = it)) }, "make.field.smsNumber", issue, MakeFormField.SMS_NUMBER, KeyboardType.Phone)
            Field("Message (optional)", form.smsBody, { onChange(form.copy(smsBody = it)) }, "make.field.smsBody", caps = sentences, autocorrect = true, multiline = true)
        }
        MakeFormKind.PHONE -> Field("Phone number", form.phone, { onChange(form.copy(phone = it)) }, "make.field.phone", issue, MakeFormField.PHONE, KeyboardType.Phone)
        MakeFormKind.LOCATION -> {
            Field("Latitude", form.latitude, { onChange(form.copy(latitude = it)) }, "make.field.latitude", issue, MakeFormField.LATITUDE, KeyboardType.Decimal)
            Field("Longitude", form.longitude, { onChange(form.copy(longitude = it)) }, "make.field.longitude", issue, MakeFormField.LONGITUDE, KeyboardType.Decimal)
            Field("Place name (optional)", form.locationQuery, { onChange(form.copy(locationQuery = it)) }, "make.field.place", caps = words, autocorrect = true)
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.semantics { contentDescription = label })
    }
}

/** "Same link, smaller code" and the offer to remove tracking tags. Nothing changes the link without a tap. */
@Composable
private fun LinkOptions(g: Generated, useSmaller: Boolean, onSmaller: (Boolean) -> Unit, onRemoveTracking: () -> Unit) {
    Column(Modifier.padding(horizontal = Space.m), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        if (g.smallerAvailable)
            Row(Modifier.fillMaxWidth().testTag("make.link.smaller"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text("Same link, smaller code", style = MaterialTheme.typography.bodyLarge)
                    Caption("The web address is written in capitals, which fit a smaller code. It opens the same place.")
                }
                Switch(checked = useSmaller, onCheckedChange = onSmaller, modifier = Modifier.semantics { contentDescription = "Same link, smaller code" })
            }
        val free = g.trackingFree
        if (free != null && g.trackingTags.isNotEmpty())
            QrCard(Modifier.testTag("make.link.tracking")) {
                Text("This link carries tracking tags. Remove them?", style = MaterialTheme.typography.bodyLarge)
                Caption(g.trackingTags.joinToString(", "))
                SelectionContainer {
                    Text(free, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.testTag("make.link.tracking.preview"))
                }
                OutlinedButton(onClick = onRemoveTracking, modifier = Modifier.testTag("make.link.tracking.remove")) { Text("Remove tracking tags") }
            }
    }
}

@Composable
private fun CodeOptions(symbology: MakeSymbology, correction: Correction, onSymbology: (MakeSymbology) -> Unit, onCorrection: (Correction) -> Unit) {
    Column(Modifier.padding(horizontal = Space.m), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Text("Code options", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        DropdownField("Code type", symbology, MakeSymbology.entries.map { it to it.title }, Modifier.fillMaxWidth().testTag("make.symbology"), onSymbology)
        if (symbology.supportsCorrection) {
            DropdownField("Error correction", correction, Correction.entries.map { it to it.title }, Modifier.fillMaxWidth().testTag("make.correction"), onCorrection)
            Caption("Auto keeps the code as small as it can. Higher levels survive more damage but make a denser code.")
        }
    }
}

private fun warningText(warning: ContrastWarning): String =
    when (warning) {
        is ContrastWarning.LowContrast -> "Low contrast (${String.format(java.util.Locale.getDefault(), "%.1f", warning.ratio)} to 1). Phones may not be able to scan this code."
        ContrastWarning.Inverted -> "The code is lighter than its background. Many scanners can’t read light-on-dark codes."
    }

/** Two colour pickers and the contrast warning. */
@Composable
private fun ColorsSection(foreground: RgbColor, background: RgbColor, onForeground: (RgbColor) -> Unit, onBackground: (RgbColor) -> Unit) {
    var picking by remember { mutableStateOf<Boolean?>(null) }
    val warnings = remember(foreground, background) { contrastWarnings(foreground, background) }
    Column(Modifier.padding(horizontal = Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Text("Colors", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        ColorRow("Code color", foreground, "make.style.foreground") { picking = true }
        ColorRow("Background color", background, "make.style.background") { picking = false }
        warnings.forEach { warning ->
            Row(Modifier.semantics(mergeDescendants = true) {}.testTag("make.style.warning"), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Icon(Icons.Rounded.Warning, null, Modifier.size(20.dp), tint = QrColors.caution)
                Text(warningText(warning), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (foreground != BLACK || background != WHITE)
            TextButton(onClick = {
                onForeground(BLACK)
                onBackground(WHITE)
            }) {
                Text("Back to black on white")
            }
    }
    picking?.let { fg ->
        ColorDialog(if (fg) "Code color" else "Background color", if (fg) foreground else background, onDismiss = { picking = null }) {
            if (fg) onForeground(it) else onBackground(it)
            picking = null
        }
    }
}

@Composable
private fun ColorRow(label: String, color: RgbColor, tag: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Row(Modifier.padding(Space.m), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(rgbHex(color).uppercase(), style = MaterialTheme.typography.bodyMedium.numeric(), color = QrColors.muted)
            Box(Modifier.size(32.dp).background(color.color(), CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
        }
    }
}

/** A colour wheel with brightness and a hex field (SwiftUI ColorPicker's counterpart). */
@Composable
private fun ColorDialog(title: String, initial: RgbColor, onDismiss: () -> Unit, onPick: (RgbColor) -> Unit) {
    val controller = rememberColorPickerController()
    var chosen by remember { mutableStateOf(initial.color()) }
    var hex by remember { mutableStateOf(rgbHex(initial).removePrefix("#").uppercase()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                HsvColorPicker(
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    controller = controller,
                    initialColor = initial.color(),
                    onColorChanged = { envelope ->
                        if (envelope.fromUser) {
                            chosen = envelope.color.copy(alpha = 1f)
                            hex = String.format(java.util.Locale.ROOT, "%06X", chosen.toArgb() and 0xFFFFFF)
                        }
                    },
                )
                BrightnessSlider(modifier = Modifier.fillMaxWidth().height(32.dp), controller = controller, initialColor = initial.color())
                OutlinedTextField(
                    hex,
                    { text ->
                        hex = text.filter { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }.take(6).uppercase()
                        if (hex.length == 6)
                            hex.toIntOrNull(16)?.let {
                                chosen = Color(0xFF000000.toInt() or it)
                                controller.selectByColor(chosen, fromUser = false)
                            }
                    },
                    label = { Text("Hex") },
                    prefix = { Text("#") },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.numeric(),
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = { TextButton(onClick = { onPick(chosen.rgb()) }) { Text("Done") } },
    )
}

/**
 * PNG, SVG or PDF of the code on screen, handed to the share sheet (Files and the gallery live
 * there; no storage permission). The caller has already checked the gate.
 */
@Composable
private fun ExportSheet(matrix: BitMatrix, kind: MakeFormKind, symbology: MakeSymbology, foreground: RgbColor, background: RgbColor, onUse: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var options by remember { mutableStateOf(MakeExportOptions()) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val stamp = remember { LocalDateTime.now() }
    val fileName = MakeExportOptions.fileName(symbology, kind, stamp, options.format, options.pdfLayout == PdfLayout.SHEET)
    val state = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))
    ModalBottomSheet(
        onDismissRequest = {
            onUse()
            onDismiss()
        },
        sheetState = state,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Space.m).padding(bottom = Space.l).imePadding(),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Text("Export", style = MaterialTheme.typography.titleLarge)
            ConnectedChoice(MakeExportFormat.entries.map { it to it.title }, options.format, Modifier.testTag("make.export.format")) { options = options.copy(format = it) }
            when (options.format) {
                MakeExportFormat.PNG -> {
                    DropdownField("Size", options.preset ?: 0, MakeExportOptions.PRESETS.map { it to "$it pixels" } + (0 to "Custom"), Modifier.fillMaxWidth().testTag("make.export.size")) {
                        options = options.copy(preset = it.takeIf { size -> size != 0 })
                    }
                    if (options.preset == null) {
                        OutlinedTextField(
                            options.customSize,
                            { options = options.copy(customSize = it.filter(Char::isDigit).take(6)) },
                            modifier = Modifier.fillMaxWidth().testTag("make.export.customSize"),
                            label = { Text("Width in pixels (up to 4096)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            textStyle = LocalTextStyle.current.numeric(),
                        )
                        if (options.customSizeWasClamped)
                            InlineMessage("Sizes run from ${MakeExportOptions.MIN_PIXEL_SIZE} to ${PngRenderer.MAX_PIXEL_SIZE}. This will be ${options.pixelSize}.", isError = false)
                    }
                    Caption("The image is a whole number of pixels per square, so it can be a little smaller than the size you pick.")
                }
                MakeExportFormat.SVG -> Text("A vector file that stays sharp at any size. Good for print and design tools.", style = MaterialTheme.typography.bodyLarge)
                MakeExportFormat.PDF -> {
                    ConnectedChoice(listOf(PdfLayout.SINGLE to "One code", PdfLayout.SHEET to "Sheet of labels"), options.pdfLayout, Modifier.testTag("make.export.layout")) {
                        options = options.copy(pdfLayout = it)
                    }
                    if (options.pdfLayout == PdfLayout.SHEET)
                        DropdownField("Labels per page", options.grid, SheetGrid.entries.map { it to it.title }, Modifier.fillMaxWidth().testTag("make.export.grid")) { options = options.copy(grid = it) }
                    DropdownField("Paper", options.paper, PaperSize.entries.map { it to it.title }, Modifier.fillMaxWidth().testTag("make.export.paper")) { options = options.copy(paper = it) }
                    OutlinedTextField(
                        options.caption,
                        { options = options.copy(caption = it) },
                        modifier = Modifier.fillMaxWidth().testTag("make.export.captionField"),
                        label = { Text("Caption (optional)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    )
                }
            }
            if (failed) InlineMessage("The file couldn’t be created. Try a smaller size.", isError = true)
            Button(
                onClick = {
                    busy = true
                    failed = false
                    scope.launch {
                        val file =
                            withContext(Dispatchers.Default) {
                                runCatching {
                                        val bytes = MakeExportWriter.data(options, matrix, foreground, background)
                                        if (bytes.isEmpty()) null else FileShare.write(context, fileName, bytes)
                                    }
                                    .getOrNull()
                            }
                        busy = false
                        if (file == null) failed = true
                        else {
                            onUse()
                            FileShare.share(context, file, options.format.mime, fileName)
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("make.export.share"),
            ) {
                if (busy) LoadingIndicator(Modifier.size(24.dp)) else Icon(Icons.Rounded.Share, null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text("Share or save file")
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val png = options.copy(format = MakeExportFormat.PNG, preset = if (options.format == MakeExportFormat.PNG) options.preset else 1024)
                        val name = MakeExportOptions.fileName(symbology, kind, stamp, MakeExportFormat.PNG, false)
                        val file = withContext(Dispatchers.Default) { runCatching { FileShare.write(context, name, MakeExportWriter.data(png, matrix, foreground, background)) }.getOrNull() } ?: return@launch
                        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newUri(context.contentResolver, "QR code", uri))
                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        onUse()
                        copied = true
                        delay(2_000)
                        copied = false
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("make.export.copy"),
            ) {
                Icon(if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy, null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(if (copied) "Copied" else "Copy image")
            }
            Caption(fileName)
        }
    }
}
