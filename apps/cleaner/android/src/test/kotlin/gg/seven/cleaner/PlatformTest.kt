package gg.seven.cleaner

import gg.seven.cleaner.core.AuxiliaryImage
import java.io.ByteArrayOutputStream
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
        assertEquals("public.heic", typeIdentifier("image/heic"))
        assertEquals("public.heic", typeIdentifier("image/heif"))
        assertEquals("image/avif", typeIdentifier("image/avif"))
        assertEquals("", typeIdentifier(null))
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

    /** A HEIF-shaped file: `ftyp`, a `meta` box holding [meta], then `mdat`. */
    private fun heif(meta: String, largeMdat: Boolean = false): File {
        fun box(type: String, body: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            val size = body.size + 8
            out.write(byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()))
            out.write(type.toByteArray(Charsets.US_ASCII))
            out.write(body)
            return out.toByteArray()
        }
        val file = File.createTempFile("aux", ".heic")
        file.outputStream().use { out ->
            out.write(box("ftyp", "heic\u0000\u0000\u0000\u0000mif1heic".toByteArray(Charsets.US_ASCII)))
            out.write(box("meta", meta.toByteArray(Charsets.ISO_8859_1)))
            if (largeMdat) {
                // A 64-bit box size, as large media data uses.
                out.write(byteArrayOf(0, 0, 0, 1) + "mdat".toByteArray() + byteArrayOf(0, 0, 0, 0, 0, 0, 0, 20, 1, 2, 3, 4))
            } else out.write(box("mdat", ByteArray(16)))
        }
        return file
    }

    @Test
    fun heifAuxiliaryImagesAreNamedOrUnidentified() {
        assertEquals(emptyList<AuxiliaryImage>(), ImageCompression.heifAuxiliary(heif("hdlrpictpitmiinfinfehvc1iprpipcohvcCispecolr")))
        assertEquals(
            listOf(AuxiliaryImage.HDR_GAIN_MAP, AuxiliaryImage.PORTRAIT_MATTE),
            ImageCompression.heifAuxiliary(
                heif("iinfirefauxlipcoauxCurn:com:apple:photo:2020:aux:hdrgainmap\u0000auxCurn:com:apple:photo:2018:aux:portraiteffectsmatte\u0000", largeMdat = true)
            ),
        )
        assertEquals(listOf(AuxiliaryImage.DEPTH), ImageCompression.heifAuxiliary(heif("auxCurn:mpeg:hevc:2015:auxid:2")))
        assertEquals(listOf(AuxiliaryImage.ISO_GAIN_MAP), ImageCompression.heifAuxiliary(heif("iinfinfetmap")))
        // An alpha plane or anything else unnamed is still auxiliary.
        assertEquals(listOf(AuxiliaryImage.UNIDENTIFIED), ImageCompression.heifAuxiliary(heif("auxCurn:mpeg:hevc:2015:auxid:1")))
        // No readable meta box: unidentified, so it is refused rather than guessed.
        val truncated = File.createTempFile("aux", ".heic").apply { writeBytes(byteArrayOf(0, 0, 0, 64) + "ftyp".toByteArray()) }
        assertEquals(listOf(AuxiliaryImage.UNIDENTIFIED), ImageCompression.heifAuxiliary(truncated))
    }

    @Test
    fun exifBlockIsTheJpegApp1Payload() {
        val exif = "Exif\u0000\u0000MM\u0000*".toByteArray(Charsets.ISO_8859_1)
        fun segment(marker: Int, body: ByteArray) =
            byteArrayOf(0xFF.toByte(), marker.toByte(), ((body.size + 2) ushr 8).toByte(), (body.size + 2).toByte()) + body
        val jpeg =
            byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + segment(0xE0, "JFIF\u0000".toByteArray()) +
                segment(0xE1, "http://ns.adobe.com/xap/1.0/\u0000".toByteArray()) + segment(0xE1, exif) +
                byteArrayOf(0xFF.toByte(), 0xDA.toByte())
        org.junit.Assert.assertArrayEquals(exif, ImageCompression.app1Exif(jpeg))
        assertNull(ImageCompression.app1Exif(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDA.toByte())))
    }

    @Test
    fun previewSamplingKeepsTheLongerSideAtLeastTheTarget() {
        assertEquals(1, sampleFor(3000, 4000, 3072))
        assertEquals(2, sampleFor(4000, 6144, 3072))
        assertEquals(4, sampleFor(12288, 9000, 3072))
        assertEquals(1, sampleFor(0, 0, 3072))
    }
}
