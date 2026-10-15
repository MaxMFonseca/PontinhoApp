package com.maxmfonseca.pontinho.ui.scanner

import android.Manifest
import android.content.ContentResolver
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maxmfonseca.pontinho.recognition.RecognitionImageLoader
import java.io.File
import kotlinx.coroutines.*

internal fun deleteStaleScanCaptures(cacheDir: File) {
    cacheDir.resolve("scans").listFiles().orEmpty()
        .filter { it.isFile && it.name.startsWith("scan-") && it.name.endsWith(".jpg") }
        .forEach(File::delete)
}

/** Owns exactly the use cases returned by bind; it never unbinds another camera client's cases. */
internal class ResumedCameraSession(
    private val owner: LifecycleOwner,
    private val permissionGranted: Boolean,
    private val bind: () -> AutoCloseable,
    private val onError: (Exception) -> Unit,
) : AutoCloseable {
    private var binding: AutoCloseable? = null
    private var closed = false
    private var observing = false
    private val observer = LifecycleEventObserver { _, _ -> if (observing) update() }

    init { owner.lifecycle.addObserver(observer); observing = true; update() }

    private fun update() {
        if (closed) return
        if (permissionGranted && owner.lifecycle.currentState == Lifecycle.State.RESUMED) {
            if (binding == null) try { binding = bind() } catch (error: Exception) { onError(error) }
        } else release()
    }

    private fun release() {
        val previous = binding
        binding = null
        try { previous?.close() } catch (error: Exception) { onError(error) }
    }

    override fun close() {
        closed = true
        owner.lifecycle.removeObserver(observer)
        release()
    }
}

/** Production adapter for ScannerViewModel.loadImage. Ownership transfers only after IO dispatch returns. */
class ScannerImageLoader internal constructor(
    private val decode: (Uri) -> Bitmap,
    private val dispatcher: CoroutineDispatcher,
) {
    constructor(contentResolver: ContentResolver) : this(RecognitionImageLoader(contentResolver)::load, Dispatchers.IO)

    suspend fun load(uri: Uri): Bitmap {
        var owned: Bitmap? = null
        try {
            // Keep ownership outside withContext: prompt cancellation can discard its return value.
            withContext(dispatcher) { owned = decode(uri) }
            return requireNotNull(owned).also { owned = null }
        } finally {
            owned?.let { if (!it.isRecycled) it.recycle() }
        }
    }
}

internal class ScanCaptureFiles(private val cacheDir: File) : AutoCloseable {
    private val owned = mutableSetOf<File>()
    private var closed = false
    fun create(): File {
        check(!closed)
        val directory = cacheDir.resolve("scans")
        check(directory.isDirectory || directory.mkdirs())
        return File.createTempFile("scan-", ".jpg", directory).also { owned.add(it) }
    }
    fun clear() { owned.toList().forEach(::discard) }
    /** Detaches a finished capture from screen cleanup before the processing operation receives it. */
    fun transfer(file: File): AutoCloseable {
        check(!closed && owned.remove(file))
        return AutoCloseable { file.delete() }
    }
    fun discard(file: File) {
        // Keep failed deletes for another cleanup attempt. A late CameraX callback can delete again.
        if (file.delete() || !file.exists()) owned.remove(file)
    }
    override fun close() { closed = true; clear() }
}

/**
 * Device-input adapter. The caller creates the ViewModel using ScannerImageLoader::load and handles
 * its Saved/Cancelled events. Navigation and score-dialog wiring intentionally belong to that caller.
 */
@Composable
fun ScannerScreen(viewModel: ScannerViewModel, recognitionAvailable: Boolean, recognitionExperimental: Boolean = false) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val files = remember(context.cacheDir, owner) { ScanCaptureFiles(context.cacheDir) }
    ScannerScreen(viewModel, recognitionAvailable, files, recognitionExperimental)
}

@Composable
internal fun ScannerScreen(viewModel: ScannerViewModel, recognitionAvailable: Boolean, files: ScanCaptureFiles, recognitionExperimental: Boolean = false) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var attached by remember { mutableStateOf(true) }
    var permission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context,
        Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var permissionDenied by remember { mutableStateOf(false) }
    var permissionRequestStarted by rememberSaveable { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf(false) }
    var capture by remember { mutableStateOf<(() -> Unit)?>(null) }

    DisposableEffect(files, owner) {
        attached = true
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                if (permission) permissionDenied = false
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { attached = false; owner.lifecycle.removeObserver(observer); files.close() }
    }
    LaunchedEffect(state.phase, state.saved) {
        // Only unfinished/untransferred outputs belong to the screen. Processing owns its source
        // until its job completes, including cancellation and any remaining decoder opens.
        if (state.phase == ScannerPhase.Review || state.phase == ScannerPhase.Error || state.saved) files.clear()
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (attached) { permission = granted; permissionDenied = !granted }
    }
    LaunchedEffect(state.phase, state.saved, recognitionAvailable, permission, permissionDenied) {
        if (state.phase == ScannerPhase.Capture && !state.saved && recognitionAvailable &&
            !permission && !permissionDenied && !permissionRequestStarted
        ) {
            permissionRequestStarted = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    val cancel: () -> Unit = { if (state.phase != ScannerPhase.Saving && !state.saved) { files.clear(); viewModel.cancel() } }
    BackHandler { cancel() }
    ScannerScreen(state, viewModel.request.indicator, recognitionAvailable, permissionDenied, cameraError,
        recognitionExperimental = recognitionExperimental,
        captureEnabled = permission && capture != null,
        actions = ScannerActions(
            onCapture = {
                cameraError = false
                capture?.let { viewModel.beginCapture(ScanImageSource.Camera); it() }
            },
            onCancel = cancel,
            onConfirm = viewModel::confirm,
            onRetry = { files.clear(); cameraError = false; viewModel.retry() },
            onRetake = { files.clear(); cameraError = false; viewModel.retake() },
            onAdd = viewModel::addCard,
            onReplace = { id, card -> viewModel.replaceRank(id, card.rank); viewModel.replaceSuit(id, card.suit) },
            onRemove = viewModel::removeCard,
        ),
        cameraPreview = {
            if (permission && recognitionAvailable && !cameraError) CameraCapture(
                permissionGranted = permission, files = files,
                onReady = { capture = it },
                onCaptured = viewModel::onCameraCaptured,
                onError = { cameraError = true; viewModel.onCameraCaptured(null) },
            )
        })
}

@Composable
internal fun CameraCapture(
    permissionGranted: Boolean,
    files: ScanCaptureFiles,
    onReady: ((() -> Unit)?) -> Unit,
    onCaptured: (Uri?, AutoCloseable?) -> Unit,
    onError: () -> Unit,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val ready by rememberUpdatedState(onReady)
    val captured by rememberUpdatedState(onCaptured)
    val failed by rememberUpdatedState(onError)
    val view = remember(context) { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
    DisposableEffect(view, owner, permissionGranted, files) {
        var disposed = false
        var session: ResumedCameraSession? = null
        val executor = ContextCompat.getMainExecutor(context)
        try {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                if (!disposed) try {
                    val provider = future.get()
                    session = ResumedCameraSession(owner, permissionGranted, bind = {
                        val selector = when {
                            provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                            provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                            else -> error("No camera")
                        }
                        val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                        val imageCapture = ImageCapture.Builder().build()
                        var live = true
                        var inFlight: File? = null
                        try { provider.bindToLifecycle(owner, selector, preview, imageCapture) }
                        catch (error: Exception) { provider.unbind(preview, imageCapture); throw error }
                        lateinit var takePhoto: () -> Unit
                        takePhoto = take@{
                            if (!live || disposed || inFlight != null) return@take
                            try {
                                files.clear()
                                val output = files.create()
                                inFlight = output
                                ready(null)
                                imageCapture.targetRotation = view.display?.rotation ?: android.view.Surface.ROTATION_0
                                imageCapture.takePicture(ImageCapture.OutputFileOptions.Builder(output).build(), executor,
                                    object : ImageCapture.OnImageSavedCallback {
                                        override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                                            inFlight = null
                                            if (!live || disposed) files.discard(output)
                                            else {
                                                ready(takePhoto)
                                                val sourceOwner = files.transfer(output)
                                                try { captured(Uri.fromFile(output), sourceOwner) }
                                                catch (error: Exception) { sourceOwner.close(); throw error }
                                            }
                                        }
                                        override fun onError(exception: ImageCaptureException) {
                                            inFlight = null
                                            files.discard(output)
                                            if (live && !disposed) { ready(takePhoto); failed() }
                                        }
                                    })
                            } catch (_: Exception) {
                                inFlight?.let(files::discard); inFlight = null
                                if (live && !disposed) { ready(takePhoto); failed() }
                            }
                        }
                        ready(takePhoto)
                        AutoCloseable {
                            live = false
                            ready(null)
                            inFlight?.let { files.discard(it); captured(null, null) }
                            inFlight = null
                            provider.unbind(preview, imageCapture)
                        }
                    }, onError = { if (!disposed) failed() })
                } catch (_: Exception) { if (!disposed) failed() }
            }, executor)
        } catch (_: Exception) { failed() }
        onDispose { disposed = true; session?.close(); ready(null) }
    }
}
