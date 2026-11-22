package gg.seven.cleaner

import java.io.File
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Kotlin-side platform pieces: phone keys, media traits, type mapping, hashing, PNG frames. */
class PlatformTest {
    private val phones = PhoneNormalizer()

    @Test
    fun internationalNumbersNeedNoCountry() {
        assertEquals("+639175550123", phones.normalized(" +63 917 555 0123 ", null))
    }

    @Test
    fun localNumbersNeedACountry() {
        assertNull(phones.normalized("0917 555 0123", null))
        assertEquals("+639175550123", phones.normalized("0917 555 0123", "PH"))
    }

    @Test
    fun extensionsStayPartOfTheKey() {
        assertEquals("+12025550143;ext=12", phones.normalized("+1 202-555-0143 ext. 12", null))
    }

    @Test
    fun invalidNumbersGetNoKey() {
        assertNull(phones.normalized("+1 123", null))
        assertNull(phones.normalized("not a number", "US"))
    }

    @Test
    fun screenshotsAndRecordingsComeFromTheirFolders() {
        assertTrue(mediaTraits(false, "Pictures/Screenshots/", "Screenshot_20261113.png", null).screenshot)
        assertFalse(mediaTraits(true, "Pictures/Screenshots/", "clip.mp4", null).screenshot)
        assertTrue(mediaTraits(true, "Movies/Screen recordings/", "Screen_recording_1.mp4", null).screenRecording)
        assertFalse(mediaTraits(false, "DCIM/Camera/", "PXL_1.jpg", null).screenshot)
    }

    @Test
    fun motionPhotosBurstsAndSlowMotion() {
        assertTrue(mediaTraits(false, "DCIM/Camera/", "PXL_20261113_101010.MP.jpg", null).live)
        assertTrue(mediaTraits(false, "DCIM/Camera/", "00001IMG_00001_BURST20261113.jpg", null).burst)
        assertTrue(mediaTraits(true, "DCIM/Camera/", "PXL_1.mp4", 240f).highFrameRate)
        assertFalse(mediaTraits(true, "DCIM/Camera/", "PXL_1.mp4", 30f).highFrameRate)
        assertFalse(mediaTraits(false, "DCIM/Camera/", "PXL_1.jpg", null).hdr)
    }

    @Test
    fun typeIdentifiersForTheCompressionGate() {
        assertEquals("public.jpeg", typeIdentifier("image/jpeg"))
        assertEquals("public.png", typeIdentifier("image/png"))
        assertEquals("image/heic", typeIdentifier("image/heic"))
        assertEquals("", typeIdentifier(null))
        assertEquals("Motion photos", collectionLabel("Live Photos"))
        assertEquals("Screenshots", collectionLabel("Screenshots"))
    }

    @Test
    fun sha256OfAStream() = runBlocking {
        val (hash, bytes) = sha256(ByteArrayInputStream("abc".toByteArray()))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hash)
        assertEquals(3L, bytes)
    }

    @Test
    fun animatedPngsAreMoreThanOneFrame() {
        fun png(vararg chunks: String): File {
            val file = File.createTempFile("frames", ".png")
            file.outputStream().use { out ->
                out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
                for (name in chunks) {
                    out.write(byteArrayOf(0, 0, 0, 1))
                    out.write(name.toByteArray())
                    out.write(byteArrayOf(0, 0, 0, 0, 0))
                }
            }
            return file
        }
        assertEquals(1u, ImageCompression.frameCount(png("IHDR", "IDAT"), "public.png"))
        assertEquals(2u, ImageCompression.frameCount(png("IHDR", "acTL", "IDAT"), "public.png"))
        assertEquals(1u, ImageCompression.frameCount(png(), "public.jpeg"))
        assertEquals(0u, ImageCompression.frameCount(png(), ""))
    }

    @Test
    fun exifOrientationsMapToRotationThenMirror() {
        assertEquals(ExifTransform(0, false), exifTransform(1))
        assertEquals(ExifTransform(0, true), exifTransform(2))
        assertEquals(ExifTransform(180, false), exifTransform(3))
        assertEquals(ExifTransform(180, true), exifTransform(4))
        assertEquals(ExifTransform(90, true), exifTransform(5))
        assertEquals(ExifTransform(90, false), exifTransform(6))
        assertEquals(ExifTransform(270, true), exifTransform(7))
        assertEquals(ExifTransform(270, false), exifTransform(8))
        // Undefined (0) and out-of-range values are shown as stored.
        assertEquals(ExifTransform(0, false), exifTransform(0))
        assertEquals(ExifTransform(0, false), exifTransform(9))
    }

    @Test
    fun previewSamplingKeepsTheLongerSideAtLeastTheTarget() {
        assertEquals(1, sampleFor(3000, 4000, 3072))
        assertEquals(2, sampleFor(4000, 6144, 3072))
        assertEquals(4, sampleFor(12288, 9000, 3072))
        assertEquals(1, sampleFor(0, 0, 3072))
    }
}
