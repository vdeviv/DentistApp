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

const val OVAL_WIDTH_RATIO = 0.68f
const val OVAL_HEIGHT_RATIO = 0.58f
const val OVAL_CENTER_Y_RATIO = 1f / 2.4f

@Composable
fun CameraScreen(hasCameraPermission: Boolean) {
    var liveResult     by remember { mutableStateOf<MeasurementResult?>(null) }
    var frozenResult   by remember { mutableStateOf<MeasurementResult?>(null) }
    var useFrontCamera by remember { mutableStateOf(true) }
    val analyzerRef    = remember { mutableStateOf<FaceMeshAnalyzer?>(null) }

    val isFrozen = frozenResult != null

    Box(modifier = Modifier.fillMaxSize()) {
        if (hasCameraPermission) {
            CameraPreviewWithAnalysis(
                useFrontCamera      = useFrontCamera,
                onMeasurementUpdate = { liveResult = it },
                onAnalyzerReady     = { analyzerRef.value = it }
            )

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
                    useFrontCamera = !useFrontCamera
                },
                onUnfreeze = { frozenResult = null }
            )

            Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                if (isFrozen) {
                    FrozenMetricsPanel(result = frozenResult!!)
                } else {
                    LivePanel(
                        result        = liveResult,
                        onCapturePeak = {
                            analyzerRef.value?.consumePeak()?.let { frozenResult = it }
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
    onAnalyzerReady: (FaceMeshAnalyzer) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val analyzer = remember(useFrontCamera) {
        FaceMeshAnalyzer(useFrontCamera, onMeasurementUpdate).also { onAnalyzerReady(it) }
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

@Composable
fun FaceGuideOverlay(result: MeasurementResult?, isFrozen: Boolean) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val W = size.width
        val H = size.height

        val ovalW = W * OVAL_WIDTH_RATIO
        val ovalH = H * OVAL_HEIGHT_RATIO
        val cx    = W / 2f
        val cy    = H * OVAL_CENTER_Y_RATIO

        val ovalColor = when {
            isFrozen             -> Color(0xFFFFD700)
            result == null       -> Color(0xFFFFD700)
            result.isAligned     -> Color(0xFF00E676)
            else                 -> Color(0xFFFF6B35)
        }

        // ── 1. Óvalo Guía Estático ──────
        drawOval(
            color   = ovalColor,
            topLeft = Offset(cx - ovalW / 2f, cy - ovalH / 2f),
            size    = Size(ovalW, ovalH),
            style   = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        )

        // Caja de Enfoque Predictiva para la Boca (Solo en vivo si no está alineado)
        val mouthBoxY = cy + (ovalH / 4f)
        val mouthBoxW = ovalW * 0.55f
        val mouthBoxH = 55.dp.toPx()

        if (!isFrozen && (result == null || !result.isAligned)) {
            drawRoundRect(
                color = Color.White.copy(alpha = 0.35f),
                topLeft = Offset(cx - mouthBoxW / 2f, mouthBoxY - mouthBoxH / 2f),
                size = Size(mouthBoxW, mouthBoxH),
                style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))),
                cornerRadius = CornerRadius(8.dp.toPx())
            )
        }

        val r = result ?: return@Canvas
        if (!r.isAligned) return@Canvas

        fun Offset.sc() = Offset(x * W, y * H)
        val upper = r.upperLipScreen?.sc() ?: return@Canvas
        val lower = r.lowerLipScreen?.sc() ?: return@Canvas
        val left  = r.leftMouthScreen?.sc()
        val right = r.rightMouthScreen?.sc()

        val accColor = if (isFrozen) Color(0xFFFFD700) else Color(0xFF00E676)

        // ── 2. Puntos anatómicos discretos (Siempre visibles para feedback) ──────
        drawCircle(color = accColor, radius = 4.dp.toPx(), center = upper)
        drawCircle(color = accColor, radius = 4.dp.toPx(), center = lower)
        if (left != null && right != null) {
            drawCircle(color = Color(0xFF29B6F6), radius = 3.5.dp.toPx(), center = left)
            drawCircle(color = Color(0xFF29B6F6), radius = 3.5.dp.toPx(), center = right)
        }

        // ── 3. DETALLES DIAGNÓSTICOS CONGELADOS (SOLO APARECEN AL CAPTURAR) ──
        if (isFrozen) {
            // Línea vertical que muestra la apertura medida entre incisivos
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

                // EJE CRANEAL DE DESVIACIÓN MANDIBULAR (Súper estable, fijo y nítido)
                val facialCenterX = (left.x + right.x) / 2f
                val lineTop   = upper.y - 60.dp.toPx()
                val lineBot   = upper.y + 80.dp.toPx()

                // Sombra de contraste posterior
                drawLine(
                    color       = Color.Black.copy(alpha = 0.5f),
                    start       = Offset(facialCenterX + 1f, lineTop),
                    end         = Offset(facialCenterX + 1f, lineBot),
                    strokeWidth = 4.dp.toPx()
                )
                // Línea de desviación central
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

            // Marco decorativo de pantalla capturada
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
                    color = when(result.detectedPattern) {
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
fun LivePanel(result: MeasurementResult?, onCapturePeak: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape    = RoundedCornerShape(16.dp),
            colors   = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.82f))
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    result == null -> {
                        Text("🔍 Centra el rostro en la guía", color = Color(0xFFFFD700), fontSize = 15.sp)
                        Text("Buscando puntos de control...", color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp)
                    }
                    !result.isAligned -> {
                        Text("⚠️ ${result.alignmentMessage}", color = Color(0xFFFF6B35), fontSize = 15.sp, textAlign = TextAlign.Center)
                    }
                    else -> {
                        Text("Apertura Funcional Activa", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                        Text("${result.openingMm} mm", color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold)

                        val (label, labelColor) = clinicalRange(result.openingMm)
                        Text(label, color = labelColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)

                        Spacer(Modifier.height(10.dp))
                        Button(onClick = onCapturePeak, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00695C))) {
                            Text("⚡ Congelar Pico Máximo", fontSize = 14.sp)
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
fun MetricBox(label: String, value: String, sub: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
        Text(value, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(sub,   color = color,       fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

fun clinicalRange(mm: Int): Pair<String, Color> = when {
    mm >= 35 -> "Normal"           to Color(0xFF00E676)
    mm >= 25 -> "Limitación Leve"  to Color(0xFFFFD700)
    mm >= 15 -> "Lim. Moderada"    to Color(0xFFFF9800)
    else     -> "Lim. Severa"      to Color(0xFFFF5252)
}

@Composable
fun NoPermissionScreen() {
    Box(modifier = Modifier.fillMaxSize().background(Color.DarkGray), contentAlignment = Alignment.Center) {
        Text("Permiso de cámara requerido.", color = Color.White, fontSize = 16.sp)
    }
}