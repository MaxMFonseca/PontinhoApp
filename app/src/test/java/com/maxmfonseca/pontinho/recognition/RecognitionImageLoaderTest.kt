package com.maxmfonseca.pontinho.recognition

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Base64
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecognitionImageLoaderTest {
    private val uri = Uri.parse("content://fixture/image")

    @Test
    fun `bounds are decoded before pixels and sample is orientation aware`() {
        val events = mutableListOf<String>()
        val opener = TrackingOpener(events)
        val decoder = FakeDecoder(
            bounds = ImageBounds(8_000, 4_000),
            decoded = coloredBitmap(500, 250),
            events = events,
        )
        val loader = RecognitionImageLoader(
            opener = opener,
            decoder = decoder,
            orientationReader = { _, _ -> events.add("orientation"); ExifTransform.ROTATE_90 },
            limits = ImageDecodeLimits(maxWidth = 1_000, maxHeight = 600, maxPixels = 500_000),
        )

        val result = loader.load(uri)

        assertEquals(listOf("open", "bounds", "close", "open", "orientation", "close", "open", "decode:16", "close"), events)
        assertEquals(16, decoder.sampleSize)
        assertEquals(250, result.width)
        assertEquals(500, result.height)
        assertEquals(Bitmap.Config.ARGB_8888, result.config)
        assertTrue(opener.streams.all { it.closed })
        assertTrue(decoder.decoded!!.isRecycled)
        assertFalse(result.isRecycled)
        result.recycle()
    }

    @Test
    fun `all EXIF rotations and mirrors produce the expected corner layout`() {
        val expected = mapOf(
            ExifTransform.NORMAL to listOf(1, 2, 3, 4, 5, 6),
            ExifTransform.FLIP_HORIZONTAL to listOf(3, 2, 1, 6, 5, 4),
            ExifTransform.ROTATE_180 to listOf(6, 5, 4, 3, 2, 1),
            ExifTransform.FLIP_VERTICAL to listOf(4, 5, 6, 1, 2, 3),
            ExifTransform.TRANSPOSE to listOf(1, 4, 2, 5, 3, 6),
            ExifTransform.ROTATE_90 to listOf(4, 1, 5, 2, 6, 3),
            ExifTransform.TRANSVERSE to listOf(6, 3, 5, 2, 4, 1),
            ExifTransform.ROTATE_270 to listOf(3, 6, 2, 5, 1, 4),
        )

        expected.forEach { (orientation, pixels) ->
            val source = numberedBitmap()
            val loader = RecognitionImageLoader(
                opener = TrackingOpener(mutableListOf()),
                decoder = FakeDecoder(ImageBounds(3, 2), source, mutableListOf()),
                orientationReader = { _, _ -> orientation },
                limits = ImageDecodeLimits(maxWidth = 10, maxHeight = 10, maxPixels = 100),
            )

            val result = loader.load(uri)

            assertEquals("$orientation width", if (orientation.swapsDimensions) 2 else 3, result.width)
            assertEquals("$orientation height", if (orientation.swapsDimensions) 3 else 2, result.height)
            assertEquals("$orientation pixels", pixels, result.pixelMarkers())
            assertFalse(result.isRecycled)
            if (result !== source) assertTrue("$orientation source recycled", source.isRecycled)
            result.recycle()
        }
    }

    @Test
    fun `normal orientation copies non ARGB input and recycles only the intermediate`() {
        val source = Bitmap.createBitmap(2, 2, Bitmap.Config.RGB_565)
        val loader = RecognitionImageLoader(
            opener = TrackingOpener(mutableListOf()),
            decoder = FakeDecoder(ImageBounds(2, 2), source, mutableListOf()),
            orientationReader = { _, _ -> ExifTransform.NORMAL },
            limits = ImageDecodeLimits(),
        )

        val result = loader.load(uri)

        assertNotSame(source, result)
        assertTrue(source.isRecycled)
        assertFalse(result.isRecycled)
        assertEquals(Bitmap.Config.ARGB_8888, result.config)
        result.recycle()
    }

    @Test
    fun `malformed content throws typed decode error without leaking URI`() {
        val opener = TrackingOpener(mutableListOf())
        val loader = RecognitionImageLoader(
            opener = opener,
            decoder = FakeDecoder(ImageBounds(10, 10), decoded = null, events = mutableListOf()),
            orientationReader = { _, _ -> ExifTransform.NORMAL },
            limits = ImageDecodeLimits(),
        )

        val error = assertThrows(RecognitionImageException.DecodeFailed::class.java) { loader.load(uri) }

        assertFalse(error.message.orEmpty().contains(uri.toString()))
        assertTrue(opener.streams.all { it.closed })
    }

    @Test
    fun `revoked access and zero dimensions are typed and contain no content identifier`() {
        val denied = RecognitionImageLoader(
            opener = ImageStreamOpener { throw SecurityException("private-uri") },
            decoder = FakeDecoder(ImageBounds(1, 1), coloredBitmap(1, 1), mutableListOf()),
            orientationReader = { _, _ -> ExifTransform.NORMAL },
            limits = ImageDecodeLimits(),
        )
        val accessError = assertThrows(RecognitionImageException.AccessDenied::class.java) { denied.load(uri) }
        assertFalse(accessError.message.orEmpty().contains("private-uri"))
        assertEquals(null, accessError.cause)

        val zero = RecognitionImageLoader(
            opener = TrackingOpener(mutableListOf()),
            decoder = FakeDecoder(ImageBounds(0, 100), coloredBitmap(1, 1), mutableListOf()),
            orientationReader = { _, _ -> ExifTransform.NORMAL },
            limits = ImageDecodeLimits(),
        )
        assertThrows(RecognitionImageException.InvalidDimensions::class.java) { zero.load(uri) }
    }

    @Test
    fun `cancellation propagates and closes the active stream`() {
        val opener = TrackingOpener(mutableListOf())
        val loader = RecognitionImageLoader(
            opener = opener,
            decoder = object : ImageDecoder {
                override fun readBounds(stream: InputStream) = ImageBounds(2, 2)
                override fun decode(stream: InputStream, sampleSize: Int): Bitmap = throw CancellationException("stop")
            },
            orientationReader = { _, _ -> ExifTransform.NORMAL },
            limits = ImageDecodeLimits(),
        )

        assertThrows(CancellationException::class.java) { loader.load(uri) }
        assertTrue(opener.streams.all { it.closed })
    }

    @Test
    fun `security failures during reads and closes are typed without identifiers`() {
        val readFailure = RecognitionImageLoader(
            opener = ImageStreamOpener { SecurityStream(failOnRead = true) },
            decoder = object : ImageDecoder {
                override fun readBounds(stream: InputStream): ImageBounds {
                    stream.read()
                    return ImageBounds(1, 1)
                }

                override fun decode(stream: InputStream, sampleSize: Int) = coloredBitmap(1, 1)
            },
            orientationReader = { _, _ -> ExifTransform.NORMAL },
            limits = ImageDecodeLimits(),
        )
        val readError = assertThrows(RecognitionImageException.AccessDenied::class.java) {
            readFailure.load(uri)
        }
        assertEquals(null, readError.cause)

        val closeFailure = RecognitionImageLoader(
            opener = ImageStreamOpener { SecurityStream(failOnClose = true) },
            decoder = FakeDecoder(ImageBounds(1, 1), coloredBitmap(1, 1), mutableListOf()),
            orientationReader = { _, _ -> ExifTransform.NORMAL },
            limits = ImageDecodeLimits(),
        )
        val closeError = assertThrows(RecognitionImageException.AccessDenied::class.java) {
            closeFailure.load(uri)
        }
        assertEquals(null, closeError.cause)
    }

    @Test
    fun `security failures from EXIF and pixel decode are typed and close their streams`() {
        val exifOpener = TrackingOpener(mutableListOf())
        val exifFailure = RecognitionImageLoader(
            opener = exifOpener,
            decoder = FakeDecoder(ImageBounds(1, 1), coloredBitmap(1, 1), mutableListOf()),
            orientationReader = { _, _ -> throw SecurityException("sensitive-exif-id") },
            limits = ImageDecodeLimits(),
        )
        assertThrows(RecognitionImageException.AccessDenied::class.java) { exifFailure.load(uri) }
        assertTrue(exifOpener.streams.all { it.closed })

        val decodeOpener = TrackingOpener(mutableListOf())
        val decodeFailure = RecognitionImageLoader(
            opener = decodeOpener,
            decoder = object : ImageDecoder {
                override fun readBounds(stream: InputStream) = ImageBounds(1, 1)
                override fun decode(stream: InputStream, sampleSize: Int): Bitmap =
                    throw SecurityException("sensitive-decode-id")
            },
            orientationReader = { _, _ -> ExifTransform.NORMAL },
            limits = ImageDecodeLimits(),
        )
        assertThrows(RecognitionImageException.AccessDenied::class.java) { decodeFailure.load(uri) }
        assertTrue(decodeOpener.streams.all { it.closed })
    }

    @Test
    fun `cancellation remains primary when closing the stream also loses access`() {
        var opens = 0
        val loader = RecognitionImageLoader(
            opener = ImageStreamOpener {
                opens++
                SecurityStream(failOnClose = opens == 3)
            },
            decoder = object : ImageDecoder {
                override fun readBounds(stream: InputStream) = ImageBounds(1, 1)
                override fun decode(stream: InputStream, sampleSize: Int): Bitmap =
                    throw CancellationException("stop")
            },
            orientationReader = { _, _ -> ExifTransform.NORMAL },
            limits = ImageDecodeLimits(),
        )

        val cancellation = assertThrows(CancellationException::class.java) { loader.load(uri) }

        assertEquals("stop", cancellation.message)
        assertEquals(0, cancellation.suppressed.size)
    }

    @Test
    fun `pixel bitmap is discarded exactly once when stream close loses access`() {
        val bitmap = coloredBitmap(2, 2)
        var opens = 0
        var recycleCalls = 0
        val loader = RecognitionImageLoader(
            opener = ImageStreamOpener {
                opens++
                SecurityStream(failOnClose = opens == 3)
            },
            decoder = FakeDecoder(ImageBounds(2, 2), bitmap, mutableListOf()),
            orientationReader = { _, _ -> ExifTransform.NORMAL },
            limits = ImageDecodeLimits(),
            bitmapRecycler = {
                recycleCalls++
                it.recycle()
            },
        )

        assertThrows(RecognitionImageException.AccessDenied::class.java) { loader.load(uri) }
        assertTrue(bitmap.isRecycled)
        assertEquals(1, recycleCalls)
    }

    @Test
    fun `decoded bitmap exceeding post decode limits is typed and recycled exactly once`() {
        val oversized = coloredBitmap(8, 8)
        var recycleCalls = 0
        val loader = RecognitionImageLoader(
            opener = TrackingOpener(mutableListOf()),
            decoder = FakeDecoder(ImageBounds(16, 16), oversized, mutableListOf()),
            orientationReader = { _, _ -> ExifTransform.NORMAL },
            limits = ImageDecodeLimits(maxWidth = 4, maxHeight = 4, maxPixels = 16),
            bitmapRecycler = {
                recycleCalls++
                it.recycle()
            },
        )

        assertThrows(RecognitionImageException.SizeLimitExceeded::class.java) { loader.load(uri) }

        assertTrue(oversized.isRecycled)
        assertEquals(1, recycleCalls)
    }

    @Test
    fun `allocation failure while reading bounds maps to typed size error`() {
        val loader = RecognitionImageLoader(
            opener = TrackingOpener(mutableListOf()),
            decoder = object : ImageDecoder {
                override fun readBounds(stream: InputStream): ImageBounds =
                    throw OutOfMemoryError("allocation")

                override fun decode(stream: InputStream, sampleSize: Int): Bitmap? = null
            },
            orientationReader = { _, _ -> ExifTransform.NORMAL },
            limits = ImageDecodeLimits(),
        )

        val error = assertThrows(RecognitionImageException.SizeLimitExceeded::class.java) { loader.load(uri) }

        assertTrue(error.cause is OutOfMemoryError)
    }

    @Test
    fun `real generated fixtures exercise BitmapFactory sampling and EXIF rotate and mirror`() {
        val png = fixtureBytes("recognition/generated-128x64.png.b64")
        val sampled = realLoader(png, ImageDecodeLimits(maxWidth = 16, maxHeight = 16, maxPixels = 128)).load(uri)
        assertEquals(16, sampled.width)
        assertEquals(8, sampled.height)
        assertEquals(Bitmap.Config.ARGB_8888, sampled.config)
        sampled.recycle()

        val rotated = realLoader(fixtureBytes("recognition/generated-64x32-rotate90.jpg.b64")).load(uri)
        assertEquals(32, rotated.width)
        assertEquals(64, rotated.height)
        rotated.recycle()

        val mirrored = realLoader(fixtureBytes("recognition/generated-64x32-mirror.jpg.b64")).load(uri)
        val left = mirrored.getPixel(8, mirrored.height / 2)
        val right = mirrored.getPixel(mirrored.width - 9, mirrored.height / 2)
        assertTrue(Color.blue(left) > Color.red(left))
        assertTrue(Color.red(right) > Color.blue(right))
        mirrored.recycle()
    }

    @Test
    fun `real malformed fixture fails with typed decode error`() {
        val loader = realLoader(fixtureBytes("recognition/malformed.bin.b64"))

        val error = assertThrows(RecognitionImageException.DecodeFailed::class.java) { loader.load(uri) }

        assertFalse(error.message.orEmpty().contains(uri.toString()))
    }

    private class TrackingOpener(private val events: MutableList<String>) : ImageStreamOpener {
        val streams = mutableListOf<TrackingStream>()

        override fun open(uri: Uri): InputStream = TrackingStream(events).also {
            events += "open"
            streams += it
        }
    }

    private class TrackingStream(private val events: MutableList<String>) :
        ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
        var closed = false

        override fun close() {
            if (!closed) events += "close"
            closed = true
            super.close()
        }
    }

    private class SecurityStream(
        private val failOnRead: Boolean = false,
        private val failOnClose: Boolean = false,
    ) : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
        override fun read(): Int {
            if (failOnRead) throw SecurityException("sensitive-provider-id")
            return super.read()
        }

        override fun close() {
            if (failOnClose) throw SecurityException("sensitive-provider-id")
            super.close()
        }
    }

    private class FakeDecoder(
        private val bounds: ImageBounds,
        val decoded: Bitmap?,
        private val events: MutableList<String>,
    ) : ImageDecoder {
        var sampleSize: Int? = null

        override fun readBounds(stream: InputStream): ImageBounds {
            events += "bounds"
            return bounds
        }

        override fun decode(stream: InputStream, sampleSize: Int): Bitmap? {
            events += "decode:$sampleSize"
            this.sampleSize = sampleSize
            return decoded
        }
    }

    private fun coloredBitmap(width: Int, height: Int) =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    private fun numberedBitmap(): Bitmap = Bitmap.createBitmap(
        intArrayOf(1, 2, 3, 4, 5, 6).map { 0xff000000.toInt() or it }.toIntArray(),
        3,
        2,
        Bitmap.Config.ARGB_8888,
    )

    private fun Bitmap.pixelMarkers(): List<Int> = buildList {
        for (y in 0 until height) for (x in 0 until width) add(getPixel(x, y) and 0xff)
    }

    private fun fixtureBytes(path: String): ByteArray {
        val encoded = requireNotNull(javaClass.classLoader?.getResourceAsStream(path))
            .bufferedReader()
            .use { it.readText() }
        return Base64.getMimeDecoder().decode(encoded)
    }

    private fun realLoader(
        bytes: ByteArray,
        limits: ImageDecodeLimits = ImageDecodeLimits(),
    ) = RecognitionImageLoader(
        opener = ImageStreamOpener { ByteArrayInputStream(bytes) },
        decoder = BitmapFactoryImageDecoder,
        orientationReader = PlatformExifOrientationReader,
        limits = limits,
    )
}
