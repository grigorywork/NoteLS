package com.notessync.app.ui.screens

import com.notessync.app.i18n.uiText

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/** Сканер QR-кода сопряжения (Этап 4): камера + ML Kit. */
@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(markerClass = [ExperimentalGetImage::class])
@Composable
fun ScannerScreen(onResult: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermission by remember { mutableStateOf<Boolean?>(null) }
    var scanned by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    // Запрашиваем камеру при входе на экран.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            hasPermission = true
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiText("Сканировать код")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = uiText("Назад"))
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (hasPermission) {
                true -> {
                    val scanner = remember { BarcodeScanning.getClient() }
                    val executor = remember { Executors.newSingleThreadExecutor() }
                    val disposed = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
                    val preview = remember { Preview.Builder().build() }
                    val analysis = remember {
                        ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                    }
                    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
                    DisposableEffect(scanner, executor, analysis, preview) {
                        onDispose {
                            disposed.set(true)
                            analysis.clearAnalyzer()
                            runCatching { provider?.unbind(preview, analysis) }
                            runCatching { scanner.close() }
                            executor.shutdownNow()
                        }
                    }
                    AndroidView(
                        factory = { ctx ->
                            val previewView = PreviewView(ctx)
                            val future = ProcessCameraProvider.getInstance(ctx)
                            future.addListener({
                                // A delayed provider must not bind after navigation/disposal.
                                if (!disposed.get()) {
                                    val cameraProvider = future.get()
                                    provider = cameraProvider
                                    preview.surfaceProvider = previewView.surfaceProvider
                                    analysis.setAnalyzer(executor) { imageProxy ->
                                        val media = imageProxy.image
                                        if (media == null || scanned || disposed.get()) {
                                            imageProxy.close()
                                        } else {
                                            scanner.process(InputImage.fromMediaImage(
                                                media, imageProxy.imageInfo.rotationDegrees
                                            )).addOnSuccessListener { barcodes ->
                                                val value = barcodes.firstOrNull()?.rawValue
                                                if (value != null && !scanned && !disposed.get() &&
                                                    lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                                                    scanned = true
                                                    onResult(value)
                                                }
                                            }.addOnCompleteListener { imageProxy.close() }
                                        }
                                    }
                                    runCatching {
                                        cameraProvider.bindToLifecycle(
                                            lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                                        )
                                    }
                                }
                            }, androidx.core.content.ContextCompat.getMainExecutor(ctx))
                            previewView
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                false -> Text(
                    text = uiText("Без доступа к камере сканер не работает.\nМожно ввести адрес и код вручную."),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp)
                )
                null -> Unit
            }
            if (hasPermission == true) {
                Text(
                    text = uiText("Наведите камеру на QR-код другого устройства"),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(24.dp)
                )
            }
        }
    }
}

