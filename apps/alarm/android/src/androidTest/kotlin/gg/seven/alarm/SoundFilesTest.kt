package gg.seven.alarm

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import gg.seven.alarm.core.*
import java.io.File
import java.time.Instant
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SoundFilesTest {
    private val context = ApplicationProvider.getApplicationContext<AlarmApplication>()

    @Test
    fun providerImportOwnsItsCopyAndFailureDoesNotReplaceExistingAudio() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 29)
        val directory = File(context.cacheDir, "provider-sound-${UUID.randomUUID()}")
        val resolver = context.contentResolver
        val bytes = context.assets.open("alarm-chime.wav").use { it.readBytes() }
        val values =
            android.content.ContentValues().apply {
                put(
                    android.provider.MediaStore.Audio.Media.DISPLAY_NAME,
                    "Sevens sound test ${UUID.randomUUID()}.wav",
                )
                put(android.provider.MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                put(android.provider.MediaStore.Audio.Media.IS_PENDING, 1)
            }
        val uri =
            resolver.insert(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)!!
        var sourceDeleted = false
        try {
            resolver.openOutputStream(uri)!!.use { it.write(bytes) }
            resolver.update(
                uri,
                android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Audio.Media.IS_PENDING, 0)
                },
                null,
                null,
            )
            val store = SoundFiles(context, directory)
            val sound = kotlinx.coroutines.runBlocking { store.import(uri) }
            assertTrue(sound.name.startsWith("Sevens sound test"))
            assertArrayEquals(bytes, resolver.openInputStream(uri)!!.use { it.readBytes() })
            resolver.delete(uri, null, null)
            sourceDeleted = true
            assertTrue(store.available(sound))
            val blocked = File(directory, "blocked")
            blocked.writeText("existing")
            assertThrows(Exception::class.java) {
                bytes.inputStream().use { SoundFiles(context, blocked).import(it, "failure.wav") }
            }
            assertEquals("existing", blocked.readText())
            assertArrayEquals(importedSoundPrepare(bytes).wav, store.file(sound).readBytes())
        } finally {
            if (!sourceDeleted) resolver.delete(uri, null, null)
            directory.deleteRecursively()
        }
    }

    @Test
    fun ownedCopySurvivesReopenAndPruningPreservesSavedAndDraftReferences() {
        val directory = File(context.cacheDir, "sound-test-${UUID.randomUUID()}")
        try {
            val store = SoundFiles(context, directory)
            val bytes = context.assets.open("alarm-chime.wav").use { it.readBytes() }
            val sound = bytes.inputStream().use { store.import(it, "My morning.wav") }
            val orphan = bytes.inputStream().use { store.import(it, "Unused.wav") }
            assertTrue(store.available(sound))
            assertEquals("My morning.wav", sound.name)
            val saved = newRoutine(RoutineKind.ALARM, Instant.now()).copy(importedSound = sound)
            val draft = saved.copy(importedSound = orphan)
            val archive = newLoopArchive().copy(routines = listOf(saved))
            store.prune(archive, draft)
            assertTrue(store.available(orphan))
            store.prune(archive)
            assertFalse(store.available(orphan))
            assertTrue(SoundFiles(context, directory).available(sound))
            val canonical = importedSoundPrepare(bytes).wav
            assertArrayEquals(canonical, store.file(sound).readBytes())
            val before = store.file(sound).readBytes()
            assertThrows(Exception::class.java) {
                "not audio".byteInputStream().use { store.import(it, "bad.wav") }
            }
            assertArrayEquals(before, store.file(sound).readBytes())
            assertEquals(1, directory.listFiles()!!.size)
            store.prune(
                newLoopArchive().copy(installed = listOf(newRegistration(UUID.randomUUID(), saved)))
            )
            assertTrue(store.available(sound))
            store.prune(newLoopArchive().copy(pending = newRegistration(UUID.randomUUID(), saved)))
            assertTrue(store.available(sound))
            store.prune(newLoopArchive())
            assertFalse(store.available(sound))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun playbackUsesOwnedCopyAndMissingAudioFallsBackOnlyForRinging() {
        val store = SoundFiles(context)
        val sound = context.assets.open("alarm-chime.wav").use { store.import(it, "Preview.wav") }
        try {
            Tones.player(context, AlarmTone.PULSE, false, sound).also {
                assertTrue(it.duration > 0)
                it.release()
            }
            store.file(sound).writeText("damaged")
            assertThrows(IllegalStateException::class.java) {
                Tones.player(context, AlarmTone.PULSE, false, sound)
            }
            Tones.player(context, AlarmTone.PULSE, true, sound).also {
                assertTrue(it.isLooping)
                it.release()
            }
            store.file(sound).delete()
            assertThrows(IllegalStateException::class.java) {
                Tones.player(context, AlarmTone.PULSE, false, sound)
            }
            Tones.player(context, AlarmTone.PULSE, true, sound).also {
                assertTrue(it.isLooping)
                assertTrue(it.duration > 0)
                it.release()
            }
        } finally {
            store.file(sound).delete()
        }
    }
}
