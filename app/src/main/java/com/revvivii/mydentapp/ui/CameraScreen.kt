package com.revvivii.mydentapp.ui

import android.view.ViewGroup
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
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
import com.revvivii.mydentapp.model.OpeningPattern
import kotlin.math.abs

// ════════════════════════════════════════════════════════════════════
// NOTA: El óvalo rígido (OVAL_WIDTH_RATIO, OVAL_HEIGHT_RATIO, etc.) fue
// ELIMINADO de la interfaz. Ya no se dibuja ningún elemento de encuadre
// fijo. La validación de posición ahora ocurre exclusivamente dentro de
// FaceMeshAnalyzer mediante la "Zona de Éxito Virtual" (distancia IPD +
// alineación roll/yaw + estabilidad sostenida de 1s).
// ════════════════════════════════════════════════════════════════════

@Composable
fun CameraScreen(hasCameraPermission: Boolean) {
    var liveResult     by remember { mutableStateOf<MeasurementResult?>(null) }
    var frozenResult   by remember { mutableStateOf<MeasurementResult?>(null) }
    var useFrontCamera by remember { mutableStateOf(true) }
    // true mientras estamos en sesión de medición activa, esperando el pico
    var isMeasuring    by remember { mutableStateOf(false) }
    val analyzerRef    = remember { mutableStateOf<FaceMeshAnalyzer?>(null) }

    val isFrozen = frozenResult != null

    Box(modifier = Modifier.fillMaxSize()) {
        if (hasCameraPermission) {
            CameraPreviewWithAnalysis(
                useFrontCamera      = useFrontCamera,
                onMeasurementUpdate = { liveResult = it },
                onAnalyzerReady     = { analyzerRef.value = it },
                onAutoPeakDetected  = { peak ->
                    frozenResult = peak
                    isMeasuring  = false
                }
            )

            // Overlay SIN óvalo: solo puntos anatómicos + líneas cuando
            // hay un resultado alineado (en vivo o congelado)
            FaceGuideOverlay(
                result   = if (isFrozen) frozenResult else liveResult,
                isFrozen = isFrozen
            )

            TopControls(
                isFrozen       = isFrozen,
                useFrontCamera = useFrontCamera,
                onFlipCamera   = {
                    frozenResult   = null
                    liveResult     = null
                    isMeasuring    = false
                    analyzerRef.value?.stopMeasuring()
                    useFrontCamera = !useFrontCamera
                },
                onUnfreeze = {
                    frozenResult = null
                    isMeasuring  = false
                    analyzerRef.value?.stopMeasuring()
                }
            )

            Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                if (isFrozen) {
                    FrozenMetricsPanel(result = frozenResult!!)
                } else {
                    LivePanel(
                        result      = liveResult,
                        isMeasuring = isMeasuring,
                        onStartMeasuring = {
                            isMeasuring = true
                            analyzerRef.value?.startMeasuring()
                        }
                    )
                }
            }
        } else {
            NoPermissionScreen()
        }
    }
}

@Composable
fun CameraPreviewWithAnalysis(
    useFrontCamera: Boolean,
    onMeasurementUpdate: (MeasurementResult) -> Unit,
    onAnalyzerReady: (FaceMeshAnalyzer) -> Unit,
    onAutoPeakDetected: (MeasurementResult) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val analyzer = remember(useFrontCamera) {
        FaceMeshAnalyzer(useFrontCamera, onMeasurementUpdate, onAutoPeakDetected)
            .also { onAnalyzerReady(it) }
    }

    key(useFrontCamera) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                }

                val future = ProcessCameraProvider.getInstance(ctx)
                future.addListener({
                    val provider = future.get()
                    val preview  = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(ContextCompat.getMainExecutor(ctx), analyzer) }

                    val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

                    try {
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                    } catch (e: Exception) { e.printStackTrace() }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

// ────────────────────────────────────────────────────────────────────
// OVERLAY: ya NO dibuja óvalo. Solo puntos anatómicos y líneas de
// medición/desviación cuando hay un rostro válido (alineado o congelado)
// ────────────────────────────────────────────────────────────────────

@Composable
fun FaceGuideOverlay(result: MeasurementResult?, isFrozen: Boolean) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val W = size.width
        val H = size.height

        val r = result ?: return@Canvas
        if (!r.isAligned) return@Canvas

        fun Offset.sc() = Offset(x * W, y * H)
        val upper = r.upperLipScreen?.sc() ?: return@Canvas
        val lower = r.lowerLipScreen?.sc() ?: return@Canvas
        val left  = r.leftMouthScreen?.sc()
        val right = r.rightMouthScreen?.sc()

        val accColor = if (isFrozen) Color(0xFFFFD700) else Color(0xFF00E676)



        // ── Puntos anatómicos discretos (siempre visibles si hay cara) ─
        drawCircle(color = accColor, radius = 4.dp.toPx(), center = upper)
        drawCircle(color = accColor, radius = 4.dp.toPx(), center = lower)
        if (left != null && right != null) {
            drawCircle(color = Color(0xFF29B6F6), radius = 3.5.dp.toPx(), center = left)
            drawCircle(color = Color(0xFF29B6F6), radius = 3.5.dp.toPx(), center = right)
        }

        // ── Detalles diagnósticos completos: solo al congelar ──────────
        if (isFrozen) {
            // Línea vertical que muestra la apertura medida entre labios
            drawLine(
                color       = accColor,
                start       = upper,
                end         = lower,
                strokeWidth = 2.5.dp.toPx(),
                pathEffect  = PathEffect.dashPathEffect(floatArrayOf(8f, 5f))
            )

            if (left != null && right != null) {
                // Línea horizontal de comisuras
                drawLine(
                    color       = Color(0xFF29B6F6),
                    start       = left,
                    end         = right,
                    strokeWidth = 2.dp.toPx()
                )

                // Eje de desviación mandibular: línea vertical sobre el
                // punto medio de las comisuras
                val facialCenterX = (left.x + right.x) / 2f
                val lineTop = upper.y - 60.dp.toPx()
                val lineBot = upper.y + 80.dp.toPx()

                drawLine(
                    color       = Color.Black.copy(alpha = 0.5f),
                    start       = Offset(facialCenterX + 1f, lineTop),
                    end         = Offset(facialCenterX + 1f, lineBot),
                    strokeWidth = 4.dp.toPx()
                )
                drawLine(
                    color       = Color(0xFFFF7043),
                    start       = Offset(facialCenterX, lineTop),
                    end         = Offset(facialCenterX, lineBot),
                    strokeWidth = 2.5.dp.toPx()
                )

                val tickLen = 6.dp.toPx()
                drawLine(color = Color(0xFFFF7043), start = Offset(facialCenterX - tickLen, lineTop), end = Offset(facialCenterX + tickLen, lineTop), strokeWidth = 2.dp.toPx())
                drawLine(color = Color(0xFFFF7043), start = Offset(facialCenterX - tickLen, lineBot), end = Offset(facialCenterX + tickLen, lineBot), strokeWidth = 2.dp.toPx())
            }

            drawRect(color = Color(0xFFFFD700).copy(alpha = 0.3f), size = this.size, style = Stroke(width = 6.dp.toPx()))
        }
    }
}

@Composable
fun TopControls(
    isFrozen: Boolean,
    useFrontCamera: Boolean,
    onFlipCamera: () -> Unit,
    onUnfreeze: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .statusBarsPadding(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        IconButton(
            onClick  = onFlipCamera,
            modifier = Modifier
                .size(48.dp)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
        ) {
            Text(text = if (useFrontCamera) "🔄" else "🤳", fontSize = 22.sp)
        }

        Surface(
            color = if (isFrozen) Color(0xFFFFD700).copy(alpha = 0.9f) else Color.Black.copy(alpha = 0.5f),
            shape = RoundedCornerShape(20.dp)
        ) {
            Text(
                text = if (isFrozen) "📸 CAPTURA CONGELADA" else if (useFrontCamera) "Cámara frontal" else "Cámara trasera",
                color      = if (isFrozen) Color.Black else Color.White,
                fontSize   = 12.sp,
                fontWeight = if (isFrozen) FontWeight.Bold else FontWeight.Normal,
                modifier   = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        if (isFrozen) {
            IconButton(
                onClick  = onUnfreeze,
                modifier = Modifier
                    .size(48.dp)
                    .background(Color(0xFFFF5252).copy(alpha = 0.8f), CircleShape)
            ) {
                Text("✕", fontSize = 20.sp, color = Color.White)
            }
        } else {
            Spacer(modifier = Modifier.size(48.dp))
        }
    }
}

@Composable
fun FrozenMetricsPanel(result: MeasurementResult, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().padding(16.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape    = RoundedCornerShape(16.dp),
            colors   = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.92f))
        ) {
            Column(
                modifier            = Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("ANÁLISIS CINEMÁTICO ATM", color = Color(0xFFFFD700), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    val (rangeLabel, rangeColor) = clinicalRange(result.openingMm)
                    MetricBox("Apertura", "${result.openingMm} mm", rangeLabel, rangeColor)

                    val devColor = if (result.deviationDirection == "Centro") Color(0xFF00E676) else Color(0xFFFF9800)
                    MetricBox("Desviación", "${"%.1f".format(abs(result.deviationMm))} mm", result.deviationDirection, devColor)

                    MetricBox("Confianza", "${result.confidence}%", if (result.confidence >= 80) "Alta" else "Media", Color.White.copy(alpha = 0.6f))
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.15f))
                Spacer(Modifier.height(8.dp))

                Text("Patrón Clínico Detectado:", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                Text(
                    text = result.detectedPattern.description,
                    color = when (result.detectedPattern) {
                        OpeningPattern.RECTILINEO -> Color(0xFF00E676)
                        else -> Color(0xFFFF7043)
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(12.dp))

                Text("Curva de Desplazamiento Lateral (Eje X)", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                Spacer(Modifier.height(4.dp))
                TrajectoryGraph(history = result.trajectoryHistory)

                Spacer(Modifier.height(8.dp))
                DeviationBar(deviationMm = result.deviationMm)
            }
        }
    }
}

@Composable
fun TrajectoryGraph(history: List<Float>) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(85.dp)
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
            .padding(4.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Text("💡 DERECHA", color = Color(0xFFFF7043).copy(alpha = 0.7f), fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp, top = 2.dp))
            Text("LÍNEA MEDIA (RECTO)", color = Color.White.copy(alpha = 0.2f), fontSize = 8.sp, modifier = Modifier.padding(start = 6.dp))
            Text("💡 IZQUIERDA", color = Color(0xFF29B6F6).copy(alpha = 0.7f), fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp, bottom = 2.dp))
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val centerY = h / 2f

            drawLine(
                color = Color.White.copy(alpha = 0.15f),
                start = Offset(0f, centerY),
                end = Offset(w, centerY),
                strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
            )

            if (history.size < 2) return@Canvas

            val maxVal = history.maxOf { abs(it) }.coerceAtLeast(4f)
            val stepX = w / (history.size - 1)
            val path = Path()

            history.forEachIndexed { index, dev ->
                val ratioY = dev / maxVal
                val x = index * stepX
                val y = centerY - (ratioY * (h / 2f) * 0.75f)

                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)

                if (index == history.size - 1) {
                    drawCircle(color = Color(0xFFFFD700), radius = 4.dp.toPx(), center = Offset(x, y))
                }
            }

            drawPath(
                path = path,
                color = Color(0xFF00E676),
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
}



@Composable
fun LivePanel(
    result: MeasurementResult?,
    isMeasuring: Boolean,
    onStartMeasuring: () -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape    = RoundedCornerShape(16.dp),
            colors   = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.82f))
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    result == null -> {
                        Text("🔍 Apunta la cámara hacia el rostro", color = Color(0xFFFFD700), fontSize = 15.sp, textAlign = TextAlign.Center)
                        Text("Buscando puntos de control...", color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp)
                    }
                    !result.isAligned -> {
                        // Aquí se muestran los mensajes de la Zona de Éxito:
                        // distancia, alineación o "Mantén la posición... (0.7s)"
                        Text("⚠️ ${result.alignmentMessage}", color = Color(0xFFFF6B35), fontSize = 15.sp, textAlign = TextAlign.Center)
                    }
                    else -> {
                        Text(
                            if (isMeasuring) "Apertura en curso..." else "Apertura Funcional Activa",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 12.sp
                        )
                        Text("${result.openingMm} mm", color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold)

                        val (label, labelColor) = clinicalRange(result.openingMm)
                        Text(label, color = labelColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)

                        Spacer(Modifier.height(10.dp))

                        if (isMeasuring) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                CircularProgressIndicator(
                                    modifier    = Modifier.size(16.dp),
                                    color       = Color(0xFF00E676),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Abre al máximo, se congelará solo",
                                    color    = Color.White.copy(alpha = 0.7f),
                                    fontSize = 12.sp
                                )
                            }
                        } else {
                            Button(
                                onClick  = onStartMeasuring,
                                modifier = Modifier.fillMaxWidth(),
                                colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF00695C))
                            ) {
                                Text("▶ Iniciar medición", fontSize = 14.sp)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Presiona y luego abre la boca al máximo",
                                color     = Color.White.copy(alpha = 0.4f),
                                fontSize  = 11.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DeviationBar(deviationMm: Float) {
    val maxDev = 8f
    val clamp  = deviationMm.coerceIn(-maxDev, maxDev)
    val ratio  = clamp / maxDev

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("← Izq.",  color = Color.White.copy(alpha = 0.4f), fontSize = 10.sp)
            Text("Desviación terminal", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
            Text("Der. →", color = Color.White.copy(alpha = 0.4f), fontSize = 10.sp)
        }
        Spacer(Modifier.height(3.dp))
        Canvas(modifier = Modifier.fillMaxWidth().height(10.dp)) {
            val w  = size.width
            val h  = size.height
            val cx = w / 2f
            drawRoundRect(color = Color.White.copy(alpha = 0.12f), size = this.size, cornerRadius = CornerRadius(4f))

            val fillW = (w / 2f) * abs(ratio)
            val fillX = if (ratio >= 0) cx else cx - fillW
            drawRect(
                color   = if (abs(ratio) < 0.25f) Color(0xFF00E676) else Color(0xFFFF9800),
                topLeft = Offset(fillX, 0f),
                size    = Size(fillW, h)
            )
            drawLine(color = Color.White.copy(alpha = 0.7f), start = Offset(cx, 0f), end = Offset(cx, h), strokeWidth = 2f)
        }
    }
}

@Composable
fun MetricBox(
    title: String,
    value: String,
    subtitle: String,
    color: Color
) {

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.widthIn(min = 90.dp)
    ) {

        Text(
            text = title,
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 10.sp
        )

        Spacer(Modifier.height(2.dp))

        Text(
            text = value,
            color = Color.White,
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(2.dp))

        Text(
            text = subtitle,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

fun clinicalRange(openingMm: Int): Pair<String, Color> =
    when {

        openingMm < 35 ->
            "Restringido" to Color(0xFFE53935)

        openingMm in 35..40 ->
            "Límite" to Color(0xFFFF9800)

        else ->
            "Normal" to Color(0xFF00E676)
    }


@Composable
fun NoPermissionScreen() {
    Box(modifier = Modifier.fillMaxSize().background(Color.DarkGray), contentAlignment = Alignment.Center) {
        Text("Permiso de cámara requerido.", color = Color.White, fontSize = 16.sp)
    }
}

