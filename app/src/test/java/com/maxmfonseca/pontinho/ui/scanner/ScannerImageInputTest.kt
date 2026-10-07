package com.maxmfonseca.pontinho.ui.scanner

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@OptIn(ExperimentalCoroutinesApi::class)
class ScannerImageInputTest {
    @Test fun cameraBindsOnlyWhileResumedWithPermissionAndUnbindsOnStopOrDisposal() {
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this)
        }
        var binds = 0; var unbinds = 0
        val session = ResumedCameraSession(owner, true,
            bind = { binds++; AutoCloseable { unbinds++ } }, onError = { throw AssertionError(it) })
        owner.lifecycle.currentState = Lifecycle.State.STARTED
        assertEquals(0, binds)
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        assertEquals(1, binds)
        owner.lifecycle.currentState = Lifecycle.State.STARTED
        assertEquals(1, unbinds)
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        assertEquals(2, binds)
        session.close()
        assertEquals(2, unbinds)
        owner.lifecycle.currentState = Lifecycle.State.STARTED
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        assertEquals(2, binds)
        val denied = ResumedCameraSession(owner, false,
            bind = { binds++; AutoCloseable {} }, onError = { throw AssertionError(it) })
        assertEquals(2, binds)
        denied.close()
    }

    @Test fun cameraProviderFailureIsReportedWithoutClosingTheScoreFlow() {
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this)
        }
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        var errors = 0
        val session = ResumedCameraSession(owner, true,
            bind = { error("camera unavailable") }, onError = { errors++ })
        assertEquals(1, errors)
        session.close()
    }
    @Test fun cancellationDuringDecodeRecyclesTheUndeliveredBitmap() = runTest {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val owner = Job()
        val adapter = ScannerImageLoader(decode = { owner.cancel(); bitmap },
            dispatcher = StandardTestDispatcher(testScheduler))
        val result = async(owner) { adapter.load(Uri.EMPTY) }
        advanceUntilIdle()
        assertTrue(result.isCancelled)
        assertTrue(bitmap.isRecycled)
    }

    @Test fun successfulDecodeTransfersOwnershipToCaller() = runTest {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val adapter = ScannerImageLoader(decode = { bitmap }, dispatcher = StandardTestDispatcher(testScheduler))
        val result = adapter.load(Uri.EMPTY)
        assertSame(bitmap, result)
        assertFalse(result.isRecycled)
        result.recycle()
    }

    @Test fun tempFilesArePrivateAndClearRemovesOnlyOwnedImagesIncludingLateWrites() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val files = ScanCaptureFiles(context.cacheDir)
        val first = files.create()
        assertEquals(context.cacheDir.resolve("scans"), first.parentFile)
        val unrelated = context.cacheDir.resolve("keep.txt").apply { writeText("keep") }
        files.clear()
        assertFalse(first.exists())
        // CameraX may finish writing after cancellation. Its late callback must discard the output.
        first.writeText("late")
        files.discard(first)
        assertFalse(first.exists())
        val second = files.create()
        files.close()
        assertFalse(second.exists())
        assertTrue(unrelated.exists())
        assertThrows(IllegalStateException::class.java) { files.create() }
        unrelated.delete()
    }
}
