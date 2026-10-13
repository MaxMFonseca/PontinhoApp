package com.maxmfonseca.pontinho.recognition

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CaptureResolutionTest {
    @Test fun `unsafe original size fails before pixel allocation without downsampling`() {
        var decoded = false
        val loader = RecognitionImageLoader(
            opener = { ByteArrayInputStream(byteArrayOf()) },
            decoder = object : ImageDecoder {
                override fun readBounds(stream: java.io.InputStream) = ImageBounds(9000, 9000)
                override fun decode(stream: java.io.InputStream, sampleSize: Int): Bitmap? { decoded = true; return null }
            },
            orientationReader = { _, _ -> ExifTransform.NORMAL },
            limits = ImageDecodeLimits(),
        )
        assertThrows(RecognitionImageException.SizeLimitExceeded::class.java) { loader.load(Uri.EMPTY) }
        assertFalse(decoded)
    }
}
