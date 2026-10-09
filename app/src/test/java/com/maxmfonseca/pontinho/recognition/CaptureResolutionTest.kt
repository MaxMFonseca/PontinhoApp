package com.maxmfonseca.pontinho.recognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.maxmfonseca.pontinho.domain.recognition.*
import com.maxmfonseca.pontinho.ui.scanner.ScannerImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CaptureResolutionTest {
    @Test fun `production capture loader preserves original pixels through engine crop`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("resolution-test", ".png", context.cacheDir)
        val source = Bitmap.createBitmap(3000, 2000, Bitmap.Config.ARGB_8888)
        source.setPixel(675, 400, Color.RED)
        file.outputStream().use { assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        source.recycle()
        val loaded = ScannerImageLoader(context.contentResolver).load(Uri.fromFile(file))
        try {
            var detectorSize: Pair<Int, Int>? = null
            var cropSize: Pair<Int, Int>? = null
            var cropPixel = 0
            val detector = object : CardDetectorRunner {
                override fun detect(bitmap: Bitmap): List<RawCardBox> {
                    detectorSize = bitmap.width to bitmap.height
                    return listOf(RawCardBox(.9f, .25f, .25f, .5f, .75f))
                }
                override fun close() = Unit
            }
            val classifier = object : CardClassifierRunner {
                override fun classify(bitmap: Bitmap): RawCardClassification {
                    cropSize = bitmap.width to bitmap.height
                    cropPixel = bitmap.getPixel(0, 0)
                    return RawCardClassification(FloatArray(52).apply { this[0] = 1f })
                }
                override fun close() = Unit
            }
            val metadata = (RecognitionAssetVerifier().verify(BundleFixture.detector, BundleFixture.classifier, BundleFixture.bytes())
                as RecognitionAvailability.Available).metadata as RecognitionModelBundleMetadata
            TwoStageCardRecognitionEngine(detector, classifier, metadata, Dispatchers.Unconfined).use { it.recognize(loaded) }
            assertEquals(3000 to 2000, detectorSize)
            assertEquals(900 to 1200, cropSize)
            assertEquals(Color.RED, cropPixel)
        } finally { loaded.recycle(); file.delete() }
    }

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
