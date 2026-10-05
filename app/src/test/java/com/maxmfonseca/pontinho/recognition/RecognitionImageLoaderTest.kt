package com.maxmfonseca.pontinho.recognition

import android.graphics.Bitmap
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.InputStream
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
}
