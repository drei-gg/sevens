package gg.seven.vault

import gg.seven.vault.core.*
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** iOS `CreatedVault`: the open library and the recovery key to show once. */
class CreatedVault(val library: VaultLibrary, val recoveryKey: String)

/**
 * Port of iOS `VaultRepository` onto format version 2: every cryptographic step is the core's
 * (age, apps/vault/core). This class keeps the storage around it: vault directories, staging,
 * atomic replacement, the session, and temporary plaintext. Calls are serialized like the Swift
 * actor, so [lock] waits for an operation in flight to finish committing, and that operation
 * commits only encrypted data (encrypting needs the recipient, not the secret).
 */
class VaultRepository(val root: File, val temporary: File) {
    private val mutex = Mutex()
    private var identity: VaultIdentity? = null
    private var header: VaultHeaderV2? = null
    private var library: VaultLibrary? = null
    private var directory: File? = null

    private suspend fun <T> serial(block: suspend () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }

    suspend fun prepare() = serial {
        protectedDirectory(root)
        clearTemporaryNow()
        // Staging is never treated as a committed vault. Failed work does not overwrite older data.
        root.listFiles()?.forEach { entry ->
            if (entry.name.startsWith(".stage-")) entry.deleteRecursively()
            else if (entry.isDirectory)
                entry.listFiles()?.filter { it.name.startsWith(STAGING) || it.name.startsWith(".index-") }?.forEach { it.delete() }
        }
    }

    suspend fun clearTemporary() = serial { clearTemporaryNow() }

    private fun clearTemporaryNow() {
        if (temporary.exists()) temporary.deleteRecursively()
        protectedDirectory(temporary)
    }

    suspend fun lock() = serial { lockNow() }

    private fun lockNow() {
        identity?.wipe()
        identity?.destroy()
        identity = null
        header = null
        library = null
        directory = null
        runCatching { clearTemporaryNow() }
    }

    suspend fun snapshot(): VaultLibrary = serial { library ?: throw VaultException.Locked() }

    suspend fun isUnlocked(): Boolean = serial { library != null }

    private class Session(val identity: VaultIdentity, val header: VaultHeaderV2, val library: VaultLibrary, val directory: File)

    private fun session(): Session {
        val identity = identity
        val header = header
        val library = library
        val directory = directory
        if (identity == null || header == null || library == null || directory == null) throw VaultException.Locked()
        return Session(identity, header, library, directory)
    }

    private fun folders(): List<File> =
        (root.listFiles() ?: emptyArray())
            .filter { runCatching { UUID.fromString(it.name) }.isSuccess && it.name.length == 36 && it.isDirectory && !isSymlink(it) }
            .sortedBy { it.name }

    private fun readHeader(folder: File): VaultHeaderV2 = headerV2Decode(boundedRead(File(folder, HEADER), FORMAT.headerMaxBytes))

    private fun writeHeader(header: VaultHeaderV2, folder: File) = atomicWrite(File(folder, HEADER), headerV2Encode(header))

    private fun load(folder: File, header: VaultHeaderV2, identity: VaultIdentity): VaultLibrary =
        indexOpen(boundedRead(File(folder, INDEX), FORMAT.sealedIndexMaxBytes), identity, header.id)

    private fun save(changed: VaultLibrary, header: VaultHeaderV2, folder: File) = atomicWrite(File(folder, INDEX), indexSeal(changed, header.recipient))

    private fun blob(id: UUID, folder: File) = File(folder, blobNameV2(id))

    /** Whether `secret` as `kind` already opens the vault in `folder`. */
    private fun opens(folder: File, secret: String, kind: VaultCredentialKind) = headerV2Opens(readHeader(folder), secret, kind)

    private fun publish(identity: VaultIdentity, header: VaultHeaderV2, library: VaultLibrary, folder: File) {
        this.identity = identity
        this.header = header
        this.library = library
        this.directory = folder
    }

    suspend fun create(name: String, secret: String, kind: VaultCredentialKind): CreatedVault = serial {
        libraryValidateName(name)
        credentialKindValidate(kind, secret)
        // A credential must select exactly one vault. Check without changing the existing session.
        for (folder in folders()) if (opens(folder, secret, kind)) throw VaultException.DuplicateVault()
        val value = newVaultLibrary(name)
        val newIdentity = vaultIdentityGenerate()
        val recovery = generateRecoveryKey()
        val newHeader = headerV2New(value.id, newIdentity, kind, secret, recovery)
        val staging = File(root, ".stage-" + UUID.randomUUID())
        try {
            protectedDirectory(staging)
            writeHeader(newHeader, staging)
            save(value, newHeader, staging)
            val folder = File(root, value.id.upper())
            if (!staging.renameTo(folder)) throw VaultException.Damaged()
            lockNow()
            publish(newIdentity, newHeader, value, folder)
            CreatedVault(value, recovery)
        } finally {
            staging.deleteRecursively()
        }
    }

    suspend fun unlock(credential: String, recovery: Boolean, kind: VaultCredentialKind): VaultLibrary = serial {
        lockNow()
        var unreadable = false
        for (folder in folders()) {
            val found = runCatching { readHeader(folder) }.getOrNull()
            if (found == null) {
                unreadable = true
                continue
            }
            if (!recovery && found.credentialKind != kind) continue
            val opened = runCatching { headerV2Open(found, credential, kind, recovery) }.getOrNull() ?: continue
            val value =
                try {
                    load(folder, found, opened)
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

    /** The open vault's id and its identity's secret, solely to wrap for biometric custody. */
    suspend fun custodyMaterial(): Pair<UUID, ByteArray> = serial {
        val s = session()
        s.library.id to s.identity.exportForCustody()
    }

    suspend fun unlock(id: UUID, custody: ByteArray): VaultLibrary = serial {
        lockNow()
        val folder = File(root, id.upper())
        val found = readHeader(folder)
        val opened = vaultIdentityFromCustody(custody, found)
        val value = load(folder, found, opened)
        publish(opened, found, value, folder)
        value
    }

    suspend fun changeCredential(secret: String, kind: VaultCredentialKind) = serial {
        credentialKindValidate(kind, secret)
        val s = session()
        for (other in folders()) if (other != s.directory && opens(other, secret, kind)) throw VaultException.DuplicateVault()
        val changed = headerV2RewrapPassword(readHeader(s.directory), s.identity, kind, secret)
        writeHeader(changed, s.directory)
        header = changed
    }

    suspend fun replaceRecoveryKey(): String = serial {
        val s = session()
        val recovery = generateRecoveryKey()
        val changed = headerV2RewrapRecovery(readHeader(s.directory), s.identity, recovery)
        writeHeader(changed, s.directory)
        header = changed
        recovery
    }

    /**
     * Encrypt `source` (a file path, or `/proc/self/fd/N` for a document) into the vault: the
     * core streams and measures it, the copy is verified by decrypting it, and only then is it
     * named and the index committed. The source is never changed or deleted.
     */
    suspend fun importFile(source: String, name: String, type: String, folder: UUID? = null): VaultItem = serial {
        val s = session()
        libraryCheckImport(s.library, name, folder)
        val staging = File(s.directory, STAGING + UUID.randomUUID())
        try {
            val measured = fileEncrypt(s.header.recipient, source, staging.path)
            commitImport(s, staging, libraryImportItem(s.library, name, type, measured.bytes, measured.digest, folder))
        } finally {
            staging.delete()
        }
    }

    private fun commitImport(s: Session, staging: File, item: VaultItem): VaultItem {
        // Verify the actual encrypted copy before committing.
        fileVerify(s.identity, staging.path, item.bytes, item.digest)
        val target = blob(item.id, s.directory)
        if (!staging.renameTo(target)) throw VaultException.Damaged()
        val changed = libraryAppendItem(s.library, item)
        try {
            save(changed, s.header, s.directory)
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        library = changed
        return item
    }

    /** Notes never touch disk as plaintext: the text is encrypted from memory. */
    suspend fun addNote(name: String, text: String, folder: UUID?) = serial {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size.toULong() > FORMAT.noteMaxBytes) throw VaultException.NoSpace()
        val stored = noteName(name, "")
        val s = session()
        libraryCheckImport(s.library, stored, folder)
        val staging = File(s.directory, STAGING + UUID.randomUUID())
        try {
            val measured = dataEncrypt(s.header.recipient, bytes, staging.path)
            commitImport(s, staging, libraryImportItem(s.library, stored, NOTE_TYPE, measured.bytes, measured.digest, folder))
        } finally {
            staging.delete()
        }
        Unit
    }

    suspend fun editNote(id: UUID, text: String) = serial {
        val s = session()
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
                save(changed, s.header, s.directory)
            } catch (e: Exception) {
                target.delete()
                throw e
            }
            library = changed
            blob(id, s.directory).delete()
        } finally {
            staging.delete()
        }
        Unit
    }

    suspend fun readNote(id: UUID): String = serial {
        val s = session()
        val item = libraryCheckNoteRead(s.library, id)
        dataDecrypt(s.identity, blob(id, s.directory).path, item.bytes, item.digest).toString(Charsets.UTF_8)
    }

    private fun edit(change: (VaultLibrary) -> VaultLibrary) {
        val s = session()
        val changed = change(s.library)
        save(changed, s.header, s.directory)
        library = changed
    }

    suspend fun updateItem(id: UUID, name: String? = null, favorite: Boolean? = null, trashed: Boolean? = null) = serial {
        edit { libraryUpdateItem(it, id, name, favorite, trashed, Instant.now()) }
    }

    suspend fun move(ids: Set<UUID>, parent: UUID?) = serial { edit { libraryMove(it, ids.toList(), parent) } }

    suspend fun addFolder(name: String, parent: UUID?) = serial { edit { libraryAddFolder(it, name, parent) } }

    suspend fun renameFolder(id: UUID, name: String) = serial { edit { libraryRenameFolder(it, id, name) } }

    suspend fun preferences(sort: VaultOrder, grid: Boolean) = serial { edit { librarySetPreferences(it, sort, grid) } }

    suspend fun reorder(ids: List<UUID>) = serial { edit { libraryReorder(it, ids) } }

    suspend fun permanentlyDelete(ids: Set<UUID>) = serial {
        val s = session()
        edit { libraryPermanentlyDelete(it, ids.toList()) }
        for (id in ids) blob(id, s.directory).delete()
    }

    /** A verified plaintext copy in temporary storage (or under `into`), named as the item. */
    suspend fun export(id: UUID, into: File? = null): File = serial {
        val s = session()
        val item = s.library.items.firstOrNull { it.id == id } ?: throw VaultException.MissingFile()
        val folder = File(into ?: temporary, id.upper())
        protectedDirectory(folder)
        val output = File(folder, item.name)
        val partial = File(folder, ".partial-" + UUID.randomUUID())
        try {
            fileDecrypt(s.identity, blob(id, s.directory).path, partial.path, item.bytes, item.digest)
            if (output.exists()) output.delete()
            if (!partial.renameTo(output)) throw VaultException.Damaged()
        } finally {
            partial.delete()
        }
        output
    }

    /** A manual backup: every original verified, then the whole vault framed as `.vault7`. */
    suspend fun backup(): File = serial {
        val s = session()
        for (item in s.library.items) fileVerify(s.identity, blob(item.id, s.directory).path, item.bytes, item.digest)
        val output = File(temporary, backupFileName(UUID.randomUUID()))
        backupV2Write(s.directory.path, s.library, output.path)
        edit { libraryMarkBackupPrepared(it, Instant.now()) }
        output
    }

    suspend fun backupRevision(): String = serial { vaultRevisionV2(session().directory.path) }

    /** Automatic backup's artifact: the vault id and the revision it holds. */
    suspend fun automaticBackup(output: File): Pair<UUID, String> = serial {
        val s = session()
        val revision = vaultRevisionV2(s.directory.path)
        for (item in s.library.items) fileVerify(s.identity, blob(item.id, s.directory).path, item.bytes, item.digest)
        backupV2Write(s.directory.path, s.library, output.path)
        s.library.id to revision
    }

    /**
     * Restore: the whole backup is read into staging, opened, matched against its index and every
     * original verified before the directory is published. An existing vault is never replaced.
     */
    suspend fun restore(input: String, credential: String, recovery: Boolean, kind: VaultCredentialKind): VaultLibrary = serial {
        val staging = File(root, ".stage-" + UUID.randomUUID())
        try {
            protectedDirectory(staging)
            val names = backupV2Read(input, staging.path)
            val found = readHeader(staging)
            if (!recovery && found.credentialKind != kind) throw VaultException.InvalidCredential()
            val opened = headerV2Open(found, credential, kind, recovery)
            try {
                val value = load(staging, found, opened)
                backupV2NamesMatch(names, value)
                for (item in value.items) fileVerify(opened, blob(item.id, staging).path, item.bytes, item.digest)
                val target = File(root, value.id.upper())
                if (target.exists()) throw VaultException.DuplicateVault()
                if (!staging.renameTo(target)) throw VaultException.Damaged()
                lockNow()
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
