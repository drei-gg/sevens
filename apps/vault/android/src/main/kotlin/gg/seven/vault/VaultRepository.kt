package gg.seven.vault

import gg.seven.vault.core.*
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** iOS `CreatedVault`: the open library and the recovery key to show once. */
class CreatedVault(val library: VaultLibrary, val recoveryKey: String)

/**
 * Port of iOS `VaultRepository` onto format version 2: every cryptographic step is the core's (age,
 * apps/vault/core). This class keeps the storage around it: vault directories, staging, atomic
 * replacement, the session, and temporary plaintext. Calls are serialized like the Swift actor.
 * [lock] wipes the session's identity at once, without waiting; an operation already running works
 * on its own [VaultIdentity.fork], may finish committing encrypted data with it, and never puts its
 * result back into a locked session.
 */
class VaultRepository(val root: File, val temporary: File) {
    private val mutex = Mutex()
    @Volatile private var current: Session? = null

    private suspend fun <T> serial(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { mutex.withLock { block() } }

    suspend fun prepare() = serial {
        protectedDirectory(root)
        clearTemporaryNow()
        // Staging is never treated as a committed vault. Failed work does not overwrite older data.
        root.listFiles()?.forEach { entry ->
            if (entry.name.startsWith(".stage-")) entry.deleteRecursively()
            else if (entry.isDirectory)
                entry
                    .listFiles()
                    ?.filter { it.name.startsWith(STAGING) || it.name.startsWith(".index-") }
                    ?.forEach { it.delete() }
        }
    }

    suspend fun clearTemporary() = serial { clearTemporaryNow() }

    private fun clearTemporaryNow() {
        if (temporary.exists()) temporary.deleteRecursively()
        protectedDirectory(temporary)
    }

    /**
     * Wipes the session's identity now, then (after any operation in flight) the temporary
     * plaintext.
     */
    suspend fun lock() {
        wipeSession()
        serial { runCatching { clearTemporaryNow() } }
    }

    /** Immediate: never waits for an operation, which keeps its own copy until it ends. */
    fun wipeSession() {
        val live = synchronized(this) { current.also { current = null } } ?: return
        live.identity.wipe()
        live.identity.destroy()
    }

    suspend fun snapshot(): VaultLibrary = serial {
        current?.library ?: throw VaultException.Locked()
    }

    suspend fun isUnlocked(): Boolean = current != null

    /** The open vault; an operation's copy also names the [origin] it was taken from. */
    private class Session(
        val identity: VaultIdentity,
        val header: VaultHeaderV2,
        val library: VaultLibrary,
        val directory: File,
        val origin: Session? = null,
    )

    /** One operation on the open vault, with its own copy of the identity, destroyed afterwards. */
    private inline fun <T> operation(block: (Session) -> T): T {
        val live = current ?: throw VaultException.Locked()
        val own =
            try {
                live.identity.fork()
            } catch (_: IllegalStateException) {
                throw VaultException.Locked() // destroyed by a lock that just happened
            }
        try {
            return block(Session(own, live.header, live.library, live.directory, live))
        } finally {
            own.wipe()
            own.destroy()
        }
    }

    /**
     * Publish an operation's committed library and header, unless the session it came from has
     * locked.
     */
    private fun commit(s: Session, library: VaultLibrary = s.library, header: VaultHeaderV2 = s.header) =
        synchronized(this) {
            val origin = s.origin ?: return@synchronized
            if (current === origin)
                current = Session(origin.identity, header, library, origin.directory)
        }

    private fun commitLibrary(s: Session, changed: VaultLibrary) = commit(s, library = changed)

    private fun commitHeader(s: Session, changed: VaultHeaderV2) = commit(s, header = changed)

    private fun folders(): List<File> =
        (root.listFiles() ?: emptyArray())
            .filter {
                runCatching { UUID.fromString(it.name) }.isSuccess &&
                    it.name.length == 36 &&
                    it.isDirectory &&
                    !isSymlink(it)
            }
            .sortedBy { it.name }

    private fun readHeader(folder: File): VaultHeaderV2 =
        headerV2Decode(boundedRead(File(folder, HEADER), FORMAT.headerMaxBytes))

    private fun writeHeader(header: VaultHeaderV2, folder: File) =
        atomicWrite(File(folder, HEADER), headerV2Encode(header))

    private fun load(folder: File, header: VaultHeaderV2, identity: VaultIdentity): VaultLibrary =
        indexOpen(boundedRead(File(folder, INDEX), FORMAT.sealedIndexMaxBytes), identity, header.id)

    /**
     * The index is authenticated with the identity, so saving needs one (an operation's own copy).
     */
    private fun save(changed: VaultLibrary, identity: VaultIdentity, folder: File) =
        atomicWrite(File(folder, INDEX), indexSeal(changed, identity))

    private fun blob(id: UUID, folder: File) = File(folder, blobNameV2(id))

    /** Whether `secret` as `kind` already opens the vault in `folder`. */
    private fun opens(folder: File, secret: String, kind: VaultCredentialKind) =
        headerV2Opens(readHeader(folder), secret, kind)

    private fun publish(
        identity: VaultIdentity,
        header: VaultHeaderV2,
        library: VaultLibrary,
        folder: File,
    ) {
        wipeSession()
        current = Session(identity, header, library, folder)
    }

    suspend fun create(name: String, secret: String, kind: VaultCredentialKind): CreatedVault =
        serial {
            libraryValidateName(name)
            credentialKindValidate(kind, secret)
            // A credential must select exactly one vault. Check without changing the existing
            // session.
            for (folder in folders()) if (opens(folder, secret, kind))
                throw VaultException.DuplicateVault()
            // The recovery key is shown next; until the person confirms saving it, the index says so.
            val value = librarySetRecoveryUnconfirmed(newVaultLibrary(name), true)
            val newIdentity = vaultIdentityGenerate()
            val recovery = generateRecoveryKey()
            val newHeader = headerV2New(value.id, newIdentity, kind, secret, recovery)
            val staging = File(root, ".stage-" + UUID.randomUUID())
            try {
                protectedDirectory(staging)
                writeHeader(newHeader, staging)
                save(value, newIdentity, staging)
                val folder = File(root, value.id.upper())
                if (!staging.renameTo(folder)) throw VaultException.Damaged()
                publish(newIdentity, newHeader, value, folder)
                CreatedVault(value, recovery)
            } finally {
                staging.deleteRecursively()
            }
        }

    suspend fun unlock(
        credential: String,
        recovery: Boolean,
        kind: VaultCredentialKind,
    ): VaultLibrary = serial {
        wipeSession()
        var unreadable = false
        for (folder in folders()) {
            val found = runCatching { readHeader(folder) }.getOrNull()
            if (found == null) {
                unreadable = true
                continue
            }
            if (!recovery && found.credentialKind != kind) continue
            val opened =
                runCatching { headerV2Open(found, credential, kind, recovery) }.getOrNull()
                    ?: continue
            val value =
                try {
                    confirmedByRecovery(load(folder, found, opened), recovery, opened, folder)
                } catch (e: Exception) {
                    opened.destroy()
                    throw e
                }
            publish(opened, found, value, folder)
            return@serial value
        }
        if (unreadable) throw VaultException.Damaged()
        throw VaultException.InvalidCredential()
    }

    /**
     * A vault whose recovery key was never confirmed as saved, just opened by that very key: the
     * person evidently has it, so the reminder is cleared (in the index, with [identity]).
     */
    private fun confirmedByRecovery(value: VaultLibrary, recovery: Boolean, identity: VaultIdentity, folder: File): VaultLibrary {
        if (!recovery || !value.recoveryUnconfirmed) return value
        val confirmed = librarySetRecoveryUnconfirmed(value, false)
        save(confirmed, identity, folder)
        return confirmed
    }

    /** The open vault's id and its identity's secret, solely to wrap for biometric custody. */
    suspend fun custodyMaterial(): Pair<UUID, ByteArray> = serial {
        operation { s -> s.library.id to s.identity.exportForCustody() }
    }

    suspend fun unlock(id: UUID, custody: ByteArray): VaultLibrary = serial {
        wipeSession()
        val folder = File(root, id.upper())
        val found = readHeader(folder)
        val opened = vaultIdentityFromCustody(custody, found)
        val value = load(folder, found, opened)
        publish(opened, found, value, folder)
        value
    }

    suspend fun changeCredential(secret: String, kind: VaultCredentialKind) = serial {
        credentialKindValidate(kind, secret)
        operation { s ->
            for (other in folders()) if (other != s.directory && opens(other, secret, kind))
                throw VaultException.DuplicateVault()
            val changed = headerV2RewrapPassword(readHeader(s.directory), s.identity, kind, secret)
            writeHeader(changed, s.directory)
            // The required new secret (after a recovery-key restore) is now set.
            if (s.library.secretChangeRequired) {
                val cleared = librarySetSecretChangeRequired(s.library, false)
                save(cleared, s.identity, s.directory)
                commit(s, cleared, changed)
            } else commitHeader(s, changed)
        }
    }

    /**
     * A new recovery key, returned to show once. The index records it as unconfirmed first, so an
     * interruption (or a lock before the person saves it) is followed by another replacement
     * prompt at the next unlock, never by a key nobody saw.
     */
    suspend fun replaceRecoveryKey(): String = serial {
        operation { s ->
            val recovery = generateRecoveryKey()
            val flagged = librarySetRecoveryUnconfirmed(s.library, true)
            save(flagged, s.identity, s.directory)
            val changed = headerV2RewrapRecovery(readHeader(s.directory), s.identity, recovery)
            writeHeader(changed, s.directory)
            commit(s, flagged, changed)
            recovery
        }
    }

    /** The person confirmed saving the current recovery key. */
    suspend fun confirmRecoveryKey() = serial {
        operation { s ->
            if (s.library.recoveryUnconfirmed)
                edit(s) { librarySetRecoveryUnconfirmed(it, false) }
        }
    }

    /**
     * Encrypt `source` (a file path) into the vault: the core streams and measures it, the copy is
     * verified by decrypting it, and only then is it named and the index committed. The source is
     * never changed or deleted.
     */
    suspend fun importFile(
        source: String,
        name: String,
        type: String,
        folder: UUID? = null,
    ): VaultItem =
        importWith(name, type, folder) { recipient, staging ->
            fileEncrypt(recipient, source, staging)
        }

    /**
     * [importFile] from an open descriptor (a document or photo-picker item), kept open by the
     * caller.
     */
    suspend fun importDescriptor(
        fd: Int,
        name: String,
        type: String,
        folder: UUID? = null,
    ): VaultItem =
        importWith(name, type, folder) { recipient, staging ->
            fileEncryptFd(recipient, fd, staging)
        }

    private suspend fun importWith(
        name: String,
        type: String,
        folder: UUID?,
        encrypt: (String, String) -> PlainDigest,
    ): VaultItem = serial {
        operation { s ->
            libraryCheckImport(s.library, name, folder)
            val staging = File(s.directory, STAGING + UUID.randomUUID())
            try {
                val measured = encrypt(s.header.recipient, staging.path)
                commitImport(
                    s,
                    staging,
                    libraryImportItem(
                        s.library,
                        name,
                        type,
                        measured.bytes,
                        measured.digest,
                        folder,
                    ),
                )
            } finally {
                staging.delete()
            }
        }
    }

    private fun commitImport(s: Session, staging: File, item: VaultItem): VaultItem {
        // Verify the actual encrypted copy before committing.
        fileVerify(s.identity, staging.path, item.bytes, item.digest)
        val target = blob(item.id, s.directory)
        if (!staging.renameTo(target)) throw VaultException.Damaged()
        val changed = libraryAppendItem(s.library, item)
        try {
            save(changed, s.identity, s.directory)
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        commitLibrary(s, changed)
        return item
    }

    /** Notes never touch disk as plaintext: the text is encrypted from memory. */
    suspend fun addNote(name: String, text: String, folder: UUID?) = serial {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size.toULong() > FORMAT.noteMaxBytes) throw VaultException.NoSpace()
        val stored = noteName(name, "")
        operation { s ->
            libraryCheckImport(s.library, stored, folder)
            val staging = File(s.directory, STAGING + UUID.randomUUID())
            try {
                val measured = dataEncrypt(s.header.recipient, bytes, staging.path)
                commitImport(
                    s,
                    staging,
                    libraryImportItem(
                        s.library,
                        stored,
                        NOTE_TYPE,
                        measured.bytes,
                        measured.digest,
                        folder,
                    ),
                )
            } finally {
                staging.delete()
            }
        }
        Unit
    }

    suspend fun editNote(id: UUID, text: String) = serial {
        operation { s -> editNote(s, id, text) }
    }

    private fun editNote(s: Session, id: UUID, text: String) {
        val old = libraryCheckNoteEdit(s.library, id, "")
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size.toULong() > FORMAT.noteMaxBytes) throw VaultException.Unsupported()
        val staging = File(s.directory, STAGING + UUID.randomUUID())
        try {
            val measured = dataEncrypt(s.header.recipient, bytes, staging.path)
            val replacement = noteReplacement(old, measured.bytes, measured.digest)
            fileVerify(s.identity, staging.path, replacement.bytes, replacement.digest)
            val target = blob(replacement.id, s.directory)
            if (!staging.renameTo(target)) throw VaultException.Damaged()
            val changed = libraryReplaceItem(s.library, id, replacement)
            try {
                save(changed, s.identity, s.directory)
            } catch (e: Exception) {
                target.delete()
                throw e
            }
            commitLibrary(s, changed)
            blob(id, s.directory).delete()
        } finally {
            staging.delete()
        }
    }

    suspend fun readNote(id: UUID): String = serial {
        operation { s ->
            val item = libraryCheckNoteRead(s.library, id)
            dataDecrypt(s.identity, blob(id, s.directory).path, item.bytes, item.digest)
                .toString(Charsets.UTF_8)
        }
    }

    /** Never materializes plaintext files or retains note bodies between queries. */
    suspend fun searchNotes(
        query: String,
        trash: Boolean = false,
        favorites: Boolean = false,
    ): NoteSearchResult = serial {
        val origin = current ?: throw VaultException.Locked()
        val plan = noteSearchPlan(origin.library.items, query, trash, favorites)
        val matches = mutableListOf<UUID>()
        var searched = 0u
        var unavailable = 0u
        for (id in plan.ids) {
            coroutineContext.ensureActive()
            if (current !== origin) throw VaultException.Locked()
            try {
                val match = operation { session ->
                    val item = libraryCheckNoteRead(session.library, id)
                    val data =
                        dataDecrypt(
                            session.identity,
                            blob(id, session.directory).path,
                            item.bytes,
                            item.digest,
                        )
                    try {
                        noteSearchMatches(data, query)
                    } finally {
                        data.fill(0)
                    }
                }
                if (match) matches += id
                searched++
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (locked: VaultException.Locked) {
                throw locked
            } catch (_: Exception) {
                unavailable++
            }
        }
        coroutineContext.ensureActive()
        if (current !== origin) throw VaultException.Locked()
        NoteSearchResult(matches, searched, unavailable, plan.omitted, plan.issue)
    }

    private fun edit(s: Session, change: (VaultLibrary) -> VaultLibrary) {
        val changed = change(s.library)
        save(changed, s.identity, s.directory)
        commitLibrary(s, changed)
    }

    private fun edit(change: (VaultLibrary) -> VaultLibrary) = operation { s -> edit(s, change) }

    suspend fun updateItem(
        id: UUID,
        name: String? = null,
        favorite: Boolean? = null,
        trashed: Boolean? = null,
    ) = serial { edit { libraryUpdateItem(it, id, name, favorite, trashed, Instant.now()) } }

    suspend fun move(ids: Set<UUID>, parent: UUID?) = serial {
        edit { libraryMove(it, ids.toList(), parent) }
    }

    suspend fun addFolder(name: String, parent: UUID?) = serial {
        edit { libraryAddFolder(it, name, parent) }
    }

    suspend fun renameFolder(id: UUID, name: String) = serial {
        edit { libraryRenameFolder(it, id, name) }
    }

    suspend fun preferences(sort: VaultOrder, grid: Boolean) = serial {
        edit { librarySetPreferences(it, sort, grid) }
    }

    suspend fun reorder(ids: List<UUID>) = serial { edit { libraryReorder(it, ids) } }

    suspend fun permanentlyDelete(ids: Set<UUID>) = serial {
        operation { s ->
            edit(s) { libraryPermanentlyDelete(it, ids.toList()) }
            for (id in ids) blob(id, s.directory).delete()
        }
    }

    /** A verified plaintext copy in temporary storage (or under `into`), named as the item. */
    suspend fun export(id: UUID, into: File? = null): File = serial {
        operation { s -> export(s, id, into) }
    }

    private fun export(s: Session, id: UUID, into: File?): File {
        val item = s.library.items.firstOrNull { it.id == id } ?: throw VaultException.MissingFile()
        val folder = File(into ?: temporary, id.upper())
        protectedDirectory(folder)
        val output = File(folder, item.name)
        val partial = File(folder, ".partial-" + UUID.randomUUID())
        try {
            fileDecrypt(
                s.identity,
                blob(id, s.directory).path,
                partial.path,
                item.bytes,
                item.digest,
            )
            if (output.exists()) output.delete()
            if (!partial.renameTo(output)) throw VaultException.Damaged()
        } finally {
            partial.delete()
        }
        return output
    }

    /** A manual backup: every original verified, then the whole vault framed as `.vault7`. */
    suspend fun backup(): File = serial {
        operation { s ->
            for (item in s.library.items) fileVerify(
                s.identity,
                blob(item.id, s.directory).path,
                item.bytes,
                item.digest,
            )
            val output = File(temporary, backupFileName(UUID.randomUUID()))
            backupV2Write(s.directory.path, s.library, output.path)
            edit(s) { libraryMarkBackupPrepared(it, Instant.now()) }
            output
        }
    }

    suspend fun backupRevision(): String = serial {
        vaultRevisionV2((current ?: throw VaultException.Locked()).directory.path)
    }

    /** Automatic backup's artifact: the vault id and the revision it holds. */
    suspend fun automaticBackup(output: File): Pair<UUID, String> = serial {
        operation { s ->
            val revision = vaultRevisionV2(s.directory.path)
            for (item in s.library.items) fileVerify(
                s.identity,
                blob(item.id, s.directory).path,
                item.bytes,
                item.digest,
            )
            backupV2Write(s.directory.path, s.library, output.path)
            s.library.id to revision
        }
    }

    /**
     * Restore: the whole backup is read into staging, opened, matched against its index and every
     * original verified before the directory is published. An existing vault is never replaced.
     */
    suspend fun restore(
        input: String,
        credential: String,
        recovery: Boolean,
        kind: VaultCredentialKind,
    ): VaultLibrary = restoreWith(credential, recovery, kind) { backupV2Read(input, it) }

    /** [restore] from an open descriptor (a chosen document), kept open by the caller. */
    suspend fun restoreDescriptor(
        fd: Int,
        credential: String,
        recovery: Boolean,
        kind: VaultCredentialKind,
    ): VaultLibrary = restoreWith(credential, recovery, kind) { backupV2ReadFd(fd, it) }

    private suspend fun restoreWith(
        credential: String,
        recovery: Boolean,
        kind: VaultCredentialKind,
        read: (String) -> List<String>,
    ): VaultLibrary = serial {
        val staging = File(root, ".stage-" + UUID.randomUUID())
        try {
            protectedDirectory(staging)
            val names = read(staging.path)
            val found = readHeader(staging)
            if (!recovery && found.credentialKind != kind) throw VaultException.InvalidCredential()
            val opened = headerV2Open(found, credential, kind, recovery)
            try {
                val loaded = load(staging, found, opened)
                backupV2NamesMatch(names, loaded)
                val target = File(root, loaded.id.upper())
                if (target.exists()) throw VaultException.DuplicateVault()
                // As at creation and change: one unlock secret opens one vault. (A recovery-key
                // restore can't check the backup's secret; the person can change it afterwards.)
                if (!recovery)
                    for (folder in folders()) if (opens(folder, credential, kind))
                        throw VaultException.SecretInUse()
                for (item in loaded.items) fileVerify(
                    opened,
                    blob(item.id, staging).path,
                    item.bytes,
                    item.digest,
                )
                // A recovery-key restore can't check the backup's own secret against this device's
                // vaults, so the index requires a new one before the library opens. The recovery key
                // has just proved itself, so its reminder is cleared.
                val value =
                    if (!recovery) loaded
                    else
                        librarySetSecretChangeRequired(librarySetRecoveryUnconfirmed(loaded, false), true)
                            .also { save(it, opened, staging) }
                if (!staging.renameTo(target)) throw VaultException.Damaged()
                publish(opened, found, value, target)
                value
            } catch (e: Exception) {
                opened.destroy()
                throw e
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    companion object {
        const val HEADER = "header.json"
        const val INDEX = "index.age"
        const val NOTE_TYPE = "public.plain-text"
        private const val STAGING = ".import-"
        val FORMAT by lazy { vaultFormatV2() }

        fun protectedDirectory(dir: File) {
            if (!dir.isDirectory && !dir.mkdirs()) throw VaultException.NoSpace()
            dir.setReadable(false, false)
            dir.setReadable(true, true)
            dir.setWritable(true, true)
            dir.setExecutable(true, true)
        }

        fun boundedRead(file: File, max: ULong): ByteArray {
            val size = file.length()
            if (!file.isFile || size <= 0 || size.toULong() > max) throw VaultException.Damaged()
            return file.readBytes()
        }

        /** Write then rename, so a reader sees the old file or the new one, never half of one. */
        fun atomicWrite(target: File, data: ByteArray) {
            val temp = File(target.parentFile, ".index-" + UUID.randomUUID())
            try {
                java.io.FileOutputStream(temp).use {
                    it.write(data)
                    it.fd.sync()
                }
                if (!temp.renameTo(target)) throw VaultException.Damaged()
            } finally {
                temp.delete()
            }
        }

        fun isSymlink(file: File) = java.nio.file.Files.isSymbolicLink(file.toPath())
    }
}

/** Foundation's `uuidString`: the vault's directory and file names are upper-case. */
fun UUID.upper(): String = toString().uppercase()
