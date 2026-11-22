package gg.seven.vault

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import gg.seven.vault.core.RecognitionFailure
import gg.seven.vault.core.VaultCredentialKind
import gg.seven.vault.core.VaultException
import gg.seven.vault.core.VaultIssue

/**
 * A sentence from `strings.xml` with its arguments, resolved where a Context is at hand. The core
 * and the platform layers report typed cases; this is where they become words, so every case is
 * translatable and the core's English (`vaultErrorDescription` and friends) stays a log fallback.
 */
class Wording private constructor(val id: Int, val quantity: Int?, val args: List<Any>) {
    fun resolve(context: Context): String =
        if (quantity != null) context.resources.getQuantityString(id, quantity, *args.toTypedArray())
        else context.getString(id, *args.toTypedArray())

    @Composable
    fun text(): String =
        if (quantity != null) pluralStringResource(id, quantity, *args.toTypedArray())
        else stringResource(id, *args.toTypedArray())

    companion object {
        fun of(@StringRes id: Int, vararg args: Any) = Wording(id, null, args.toList())

        /** A count: [quantity] picks the plural form and is the first argument. */
        fun count(@PluralsRes id: Int, quantity: Int, vararg more: Any) = Wording(id, quantity, listOf(quantity) + more)
    }
}

val VaultException.wording: Wording
    get() =
        Wording.of(
            when (this) {
                is VaultException.WeakPin -> R.string.error_weak_pin
                is VaultException.WeakPattern -> R.string.error_weak_pattern
                is VaultException.InvalidCredential -> R.string.error_invalid_credential
                is VaultException.WeakPassword -> R.string.error_weak_password
                is VaultException.Damaged -> R.string.error_damaged
                is VaultException.Unsupported -> R.string.error_unsupported
                is VaultException.Locked -> R.string.error_locked
                is VaultException.DuplicateVault -> R.string.error_duplicate_vault
                is VaultException.InvalidName -> R.string.error_invalid_name
                is VaultException.MissingFile -> R.string.error_missing_file
                is VaultException.NoSpace -> R.string.error_no_space
                is VaultException.OlderBackup -> R.string.error_older_backup
                is VaultException.SecretInUse -> R.string.error_secret_in_use
            }
        )

val RecognitionFailure.wording: Wording
    get() =
        Wording.of(
            when (this) {
                is RecognitionFailure.TooMuchText -> R.string.error_recognition_too_much_text
                is RecognitionFailure.InvalidPage -> R.string.error_recognition_invalid_page
            }
        )

val VaultIssue.wording: Wording
    get() =
        when (this) {
            is VaultIssue.NoReadablePages -> Wording.of(R.string.issue_no_readable_pages)
            is VaultIssue.PageRange -> Wording.of(R.string.issue_page_range, pageCount.toInt())
            is VaultIssue.TooManyPages -> Wording.count(R.plurals.issue_too_many_pages, maximum.toInt())
            is VaultIssue.NothingSelected -> Wording.of(R.string.issue_nothing_selected)
            is VaultIssue.TooManyFiles -> Wording.count(R.plurals.issue_too_many_files, maximum.toInt())
            is VaultIssue.SelectionChanged -> Wording.of(R.string.issue_selection_changed)
            is VaultIssue.UnsupportedSelection -> Wording.of(R.string.issue_unsupported_selection)
            is VaultIssue.QueryTooLong -> Wording.count(R.plurals.issue_query_too_long, maximum.toInt())
        }

val BackupFailure.Reason.wording: Wording
    get() =
        Wording.of(
            when (this) {
                BackupFailure.Reason.DESTINATION -> R.string.backup_failure_destination
                BackupFailure.Reason.INVALID_ARTIFACT -> R.string.backup_failure_invalid_artifact
                BackupFailure.Reason.CHANGED_COPY -> R.string.backup_failure_changed_copy
                BackupFailure.Reason.COPY -> R.string.backup_failure_copy
            }
        )

val BiometricKey.Reason.wording: Wording
    get() =
        Wording.of(
            when (this) {
                BiometricKey.Reason.NOT_ENROLLED -> R.string.biometric_not_enrolled
                BiometricKey.Reason.NO_SENSOR -> R.string.biometric_no_sensor
                BiometricKey.Reason.INCOMPLETE -> R.string.biometric_incomplete
                BiometricKey.Reason.OFF -> R.string.biometric_off
                BiometricKey.Reason.ENROLLMENT_CHANGED -> R.string.biometric_enrollment_changed
            }
        )

/** A message a platform step raised for the person (a PDF too long for the queue, say). */
class VaultMessage(val wording: Wording) : Exception()

/** The sentence to show for a failure (iOS `vaultMessage`). */
fun Throwable.vaultMessage(context: Context): String =
    when (this) {
        is VaultException -> wording.resolve(context)
        is RecognitionFailure -> wording.resolve(context)
        is BackupFailure -> reason.wording.resolve(context)
        is BiometricKey.Unavailable -> reason.wording.resolve(context)
        is VaultMessage -> wording.resolve(context)
        is java.io.FileNotFoundException -> context.getString(R.string.error_file_not_found)
        else -> context.getString(R.string.error_generic)
    }

/** The three unlock methods' names and the field labels built on them ("New PIN", "Repeat pattern"). */
enum class KindLabel {
    TITLE,
    NEW,
    REPEAT,
    BACKUP,
}

@StringRes
fun VaultCredentialKind.label(role: KindLabel = KindLabel.TITLE): Int =
    when (this) {
        VaultCredentialKind.PASSPHRASE ->
            when (role) {
                KindLabel.TITLE -> R.string.kind_passphrase
                KindLabel.NEW -> R.string.kind_new_passphrase
                KindLabel.REPEAT -> R.string.kind_repeat_passphrase
                KindLabel.BACKUP -> R.string.kind_backup_passphrase
            }
        VaultCredentialKind.PIN ->
            when (role) {
                KindLabel.TITLE -> R.string.kind_pin
                KindLabel.NEW -> R.string.kind_new_pin
                KindLabel.REPEAT -> R.string.kind_repeat_pin
                KindLabel.BACKUP -> R.string.kind_backup_pin
            }
        VaultCredentialKind.PATTERN ->
            when (role) {
                KindLabel.TITLE -> R.string.kind_pattern
                KindLabel.NEW -> R.string.kind_new_pattern
                KindLabel.REPEAT -> R.string.kind_repeat_pattern
                KindLabel.BACKUP -> R.string.kind_backup_pattern
            }
    }
