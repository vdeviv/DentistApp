package com.revvivii.mydentapp.ui

import android.view.ViewGroup
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.revvivii.mydentapp.analyzer.FaceMeshAnalyzer
import com.revvivii.mydentapp.model.MeasurementResult

@Composable
fun CameraScreen(hasCameraPermission: Boolean) {

    // Estado del resultado de medición (se actualiza en tiempo real)
    var measurementResult by remember { mutableStateOf<MeasurementResult?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        if (hasCameraPermission) {
            // Vista de cámara con análisis de ML Kit
            CameraPreviewWithAnalysis(
                onMeasurementUpdate = { result ->
                    measurementResult = result
                }
            )

            // Overlay de guía de posición (círculo y línea de referencia)
            AlignmentOverlay(result = measurementResult)

            // Panel inferior con el resultado
            MeasurementPanel(
                result = measurementResult,
                modifier = Modifier.align(Alignment.BottomCenter)
            )

        } else {
            // Pantalla cuando no hay permiso
            NoPermissionScreen()
        }
    }
}

// ─────────────────────────────────────────────────────────────
// CÁMARA + ANÁLISIS
// ─────────────────────────────────────────────────────────────

@Composable
fun CameraPreviewWithAnalysis(
    onMeasurementUpdate: (MeasurementResult) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                // FILL_CENTER mantiene la relación de aspecto correcta
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        },
        update = { previewView ->
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                // Caso de uso 1: Preview (lo que el usuario ve)
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                // Caso de uso 2: ImageAnalysis (para ML Kit)
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(
                            ContextCompat.getMainExecutor(context),
                            FaceMeshAnalyzer(onMeasurementUpdate)
                        )
                    }

                // Usamos la cámara frontal para alineación de rostro
                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }

            }, ContextCompat.getMainExecutor(context))
        },
        modifier = Modifier.fillMaxSize()
    )
}

// ─────────────────────────────────────────────────────────────
// OVERLAY DE GUÍA
// ─────────────────────────────────────────────────────────────

@Composable
fun AlignmentOverlay(result: MeasurementResult?) {
    // Color de la guía: verde si está bien alineado, amarillo si no
    val guideColor = when {
        result == null -> Color(0xFFFFD700)          // amarillo — buscando rostro
        result.isAligned -> Color(0xFF00E676)         // verde — bien posicionado
        else -> Color(0xFFFF6B35)                     // naranja — ajustar posición
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val centerX = size.width / 2f
        val centerY = size.height / 2.5f
        val ovalWidth = size.width * 0.55f
        val ovalHeight = size.height * 0.45f

        // Óvalo guía para el rostro
        drawOval(
            color = guideColor,
            topLeft = Offset(centerX - ovalWidth / 2, centerY - ovalHeight / 2),
            size = androidx.compose.ui.geometry.Size(ovalWidth, ovalHeight),
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        )

        // Línea horizontal central (referencia de nivel)
        drawLine(
            color = guideColor.copy(alpha = 0.5f),
            start = Offset(centerX - ovalWidth / 2 - 20f, centerY),
            end = Offset(centerX + ovalWidth / 2 + 20f, centerY),
            strokeWidth = 1.dp.toPx()
        )

        // Si hay resultado, dibuja los puntos de medición en los labios
        result?.let { r ->
            if (r.upperLipScreen != null && r.lowerLipScreen != null) {
                // Punto labio superior
                drawCircle(
                    color = Color(0xFF00E676),
                    radius = 6.dp.toPx(),
                    center = r.upperLipScreen
                )
                // Punto labio inferior
                drawCircle(
                    color = Color(0xFF00E676),
                    radius = 6.dp.toPx(),
                    center = r.lowerLipScreen
                )
                // Línea de medición entre los dos puntos
                drawLine(
                    color = Color(0xFF00E676),
                    start = r.upperLipScreen,
                    end = r.lowerLipScreen,
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                        floatArrayOf(10f, 5f)
                    )
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// PANEL DE RESULTADO
// ─────────────────────────────────────────────────────────────

@Composable
fun MeasurementPanel(result: MeasurementResult?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color.Black.copy(alpha = 0.75f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (result == null) {
                    // Sin rostro detectado
                    Text(
                        text = "🔍 Coloca tu rostro dentro del óvalo",
                        color = Color(0xFFFFD700),
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Buscando rostro...",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp
                    )

                } else if (!result.isAligned) {
                    // Rostro detectado pero mal alineado
                    Text(
                        text = "⚠️ ${result.alignmentMessage}",
                        color = Color(0xFFFF6B35),
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center
                    )

                } else {
                    // ✅ Bien alineado — mostrar resultado
                    Text(
                        text = "Apertura Bucal",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    // Medición principal
                    Text(
                        text = "${result.openingMm} mm",
                        color = Color.White,
                        fontSize = 42.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Clasificación clínica
                    val (label, labelColor) = when {
                        result.openingMm >= 35 -> "✅ Normal (≥35 mm)" to Color(0xFF00E676)
                        result.openingMm >= 25 -> "🟡 Limitación Leve" to Color(0xFFFFD700)
                        result.openingMm >= 15 -> "🟠 Limitación Moderada" to Color(0xFFFF9800)
                        else -> "🔴 Limitación Severa (<15 mm)" to Color(0xFFFF5252)
                    }

                    Text(
                        text = label,
                        color = labelColor,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Divider(color = Color.White.copy(alpha = 0.2f))
                    Spacer(modifier = Modifier.height(8.dp))

                    // Confianza de la estimación
                    Text(
                        text = "Confianza: ${result.confidence}%",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// SIN PERMISO
// ─────────────────────────────────────────────────────────────

@Composable
fun NoPermissionScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "📷", fontSize = 48.sp)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Se necesita permiso de cámara\npara medir la apertura bucal.",
                color = Color.White,
                textAlign = TextAlign.Center,
                fontSize = 16.sp
            )
        }
    }
}
