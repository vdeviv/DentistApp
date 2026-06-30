package com.revvivii.mydentapp.analyzer

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.compose.ui.geometry.Offset
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.facemesh.FaceMeshDetection
import com.google.mlkit.vision.facemesh.FaceMeshDetectorOptions
import com.google.mlkit.vision.facemesh.FaceMeshPoint
import com.revvivii.mydentapp.model.MeasurementResult
import com.revvivii.mydentapp.model.OpeningPattern
import com.revvivii.mydentapp.ui.OVAL_CENTER_Y_RATIO
import com.revvivii.mydentapp.ui.OVAL_HEIGHT_RATIO
import com.revvivii.mydentapp.ui.OVAL_WIDTH_RATIO
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Analizador de frames con ML Kit Face Mesh.
 *
 * ── FIX: aperturas grandes (>50mm) no se registraban ──────────────
 * El óvalo solo se usa para decidir si la cara está "bien posicionada"
 * ANTES de medir (validación de distancia/centrado). Antes, también se
 * usaba como condición para aceptar la medición de apertura, y como el
 * labio inferior se mueve mucho hacia abajo en aperturas grandes, salía
 * del óvalo verticalmente y la medición se descartaba.
 *
 * Ahora la validación de óvalo se hace SOLO con los puntos de los OJOS
 * (que no se mueven al abrir la boca) más el labio SUPERIOR (que apenas
 * se mueve). El labio inferior queda libre de moverse fuera del óvalo
 * sin invalidar la medición — así se puede medir cualquier apertura,
 * incluso 60-70mm, mientras el rostro siga centrado.
 *
 * ── NUEVO FLUJO DE CAPTURA AUTOMÁTICA ──────────────────────────────
 * En vez de un botón "Capturar pico" que el usuario presiona mientras
 * mira la pantalla, ahora el flujo es:
 *   1. El usuario presiona "Iniciar medición" → startMeasuring()
 *   2. El analizador sigue el valor de apertura frame a frame
 *   3. Cuando detecta que la apertura SUBIÓ y luego empezó a BAJAR
 *      de forma sostenida, asume que el pico ya ocurrió y dispara
 *      automáticamente el callback onAutoPeakDetected con el mejor
 *      resultado registrado durante la sesión.
 */
class FaceMeshAnalyzer(
    private val useFrontCamera: Boolean,
    private val onResult: (MeasurementResult) -> Unit,
    private val onAutoPeakDetected: (MeasurementResult) -> Unit = {}
) : ImageAnalysis.Analyzer {

    companion object {
        const val UPPER_LIP_CENTER   = 13
        const val LOWER_LIP_CENTER   = 14
        const val LEFT_EYE_CORNER    = 33
        const val RIGHT_EYE_CORNER   = 263
        const val LEFT_MOUTH_CORNER  = 61
        const val RIGHT_MOUTH_CORNER = 291

        const val AVERAGE_IPD_MM     = 63.0f
        const val MAX_TILT_PX        = 40f
        const val MIN_OPEN_MM        = 8
        const val OVAL_MARGIN        = 0.20f
        const val DENTAL_OFFSET_MM   = 2

        // ── Parámetros de detección automática de pico ──────────────
        // Cuántos frames consecutivos de DESCENSO se necesitan para
        // confirmar que el pico ya pasó (evita falsos positivos por
        // jitter del detector en un solo frame)
        const val FRAMES_TO_CONFIRM_DESCENT = 4
        // Caída mínima en mm respecto al máximo para considerar que
        // realmente está bajando (no solo ruido de ±1mm)
        const val MIN_DROP_TO_CONFIRM_MM = 3
        // Apertura mínima para empezar a considerar que hay un intento
        // de medición en curso (evita disparar con la boca cerrada)
        const val MIN_OPEN_TO_TRACK_MM = 12
    }

    private val detector = FaceMeshDetection.getClient(
        FaceMeshDetectorOptions.Builder()
            .setUseCase(FaceMeshDetectorOptions.FACE_MESH)
            .build()
    )

    private val currentTrajectory = mutableListOf<Float>()
    private var lastPeakResult: MeasurementResult? = null
    private var frameCount = 0

    // ── Estado de la medición automática ────────────────────────────
    private var isMeasuring = false
    private var bestResultSoFar: MeasurementResult? = null
    private var bestOpeningSoFar = 0
    private var descentFrameCount = 0
    private var hasPeakBeenAutoCaptured = false

    /** Llamar cuando el usuario presiona "Iniciar medición" */
    fun startMeasuring() {
        isMeasuring = true
        hasPeakBeenAutoCaptured = false
        bestResultSoFar = null
        bestOpeningSoFar = 0
        descentFrameCount = 0
        currentTrajectory.clear()
    }

    /** Llamar al descongelar / reiniciar manualmente */
    fun stopMeasuring() {
        isMeasuring = false
        bestResultSoFar = null
        bestOpeningSoFar = 0
        descentFrameCount = 0
        hasPeakBeenAutoCaptured = false
    }

    @androidx.camera.core.ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        frameCount++
        if (frameCount % 2 != 0) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image ?: run {
            imageProxy.close()
            return
        }

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val image = InputImage.fromMediaImage(mediaImage, rotationDegrees)

        detector.process(image)
            .addOnSuccessListener { faceMeshes ->
                if (faceMeshes.isEmpty()) {
                    resetTrajectory()
                    onResult(MeasurementResult(openingMm = 0, isAligned = false, alignmentMessage = "Coloca tu rostro en el óvalo"))
                    imageProxy.close()
                    return@addOnSuccessListener
                }

                val mesh   = faceMeshes[0]
                val points = mesh.allPoints

                val upperLip   = points.getOrNull(UPPER_LIP_CENTER)
                val lowerLip   = points.getOrNull(LOWER_LIP_CENTER)
                val leftEye    = points.getOrNull(LEFT_EYE_CORNER)
                val rightEye   = points.getOrNull(RIGHT_EYE_CORNER)
                val leftMouth  = points.getOrNull(LEFT_MOUTH_CORNER)
                val rightMouth = points.getOrNull(RIGHT_MOUTH_CORNER)

                if (upperLip == null || lowerLip == null || leftEye == null || rightEye == null) {
                    imageProxy.close()
                    return@addOnSuccessListener
                }

                val isRotated = rotationDegrees == 90 || rotationDegrees == 270
                val imgW = if (isRotated) imageProxy.height.toFloat() else imageProxy.width.toFloat()
                val imgH = if (isRotated) imageProxy.width.toFloat() else imageProxy.height.toFloat()

                // ── 1. Validar Óvalo ──────────────────────────────────
                // FIX: solo se valida con OJOS + LABIO SUPERIOR.
                // El labio inferior se excluye a propósito: en aperturas
                // grandes baja mucho y antes salía del óvalo, descartando
                // la medición justo cuando la boca estaba más abierta.
                val ovalCx = imgW / 2f
                val ovalCy = imgH * OVAL_CENTER_Y_RATIO
                val semiA  = (imgW * OVAL_WIDTH_RATIO  / 2f) * (1f + OVAL_MARGIN)
                val semiB  = (imgH * OVAL_HEIGHT_RATIO / 2f) * (1f + OVAL_MARGIN)

                val pointsToCheck = listOfNotNull(leftEye, rightEye, upperLip)
                val faceInsideOval = pointsToCheck.all { p ->
                    isInsideOval(p.position.x, p.position.y, ovalCx, ovalCy, semiA, semiB)
                }

                if (!faceInsideOval) {
                    resetTrajectory()
                    onResult(MeasurementResult(openingMm = 0, isAligned = false, alignmentMessage = "Ajusta tu cara dentro del óvalo"))
                    imageProxy.close()
                    return@addOnSuccessListener
                }

                // ── 2. Validar Inclinación ─────────────────────────────
                val verticalDiff = abs(leftEye.position.y - rightEye.position.y)
                if (verticalDiff > MAX_TILT_PX) {
                    onResult(MeasurementResult(openingMm = 0, isAligned = false, alignmentMessage = "Endereza la cabeza (no la inclines)"))
                    imageProxy.close()
                    return@addOnSuccessListener
                }

                // ── 3. Escala IPD ───────────────────────────────────────
                val ipdPx = distance2D(leftEye, rightEye)
                if (ipdPx < 20f) {
                    imageProxy.close()
                    return@addOnSuccessListener
                }
                val mmPerPx = AVERAGE_IPD_MM / ipdPx

                // ── 4. Apertura ──────────────────────────────────────────
                // Sin límite superior artificial — cualquier distancia que
                // ML Kit detecte entre los puntos 13 y 14 se convierte a mm.
                val openingPx = distance2D(upperLip, lowerLip)
                val baseOpeningMm = (openingPx * mmPerPx).toInt()
                val openingMm = if (baseOpeningMm > MIN_OPEN_MM) baseOpeningMm + DENTAL_OFFSET_MM else baseOpeningMm

                val confidence = when {
                    ipdPx < 120 -> 65
                    ipdPx < 180 -> 85
                    else -> 98
                }

                // ── 5. Desviación Horizontal ────────────────────────────
                val faceCenterX = (leftEye.position.x + rightEye.position.x) / 2f
                val mouthCenterX = if (leftMouth != null && rightMouth != null)
                    (leftMouth.position.x + rightMouth.position.x) / 2f
                else
                    (upperLip.position.x + lowerLip.position.x) / 2f

                val rawDeviationPx = mouthCenterX - faceCenterX
                val correctedDeviationPx = if (useFrontCamera) rawDeviationPx * -1f else rawDeviationPx
                val deviationMm = correctedDeviationPx * mmPerPx

                val deviationDir = when {
                    abs(deviationMm) < 1.5f -> "Centro"
                    deviationMm > 0 -> "Derecha"
                    else -> "Izquierda"
                }

                // ── 6. Trayectoria (para patrón C/S/Deflexión) ──────────
                if (baseOpeningMm > MIN_OPEN_MM) {
                    currentTrajectory.add(deviationMm)
                } else {
                    if (currentTrajectory.size > 3) currentTrajectory.clear()
                }

                val currentPattern = analyzePattern(currentTrajectory, deviationMm)

                fun FaceMeshPoint.toNorm() = Offset(position.x / imgW, position.y / imgH)
                val upperLipSc   = upperLip.toNorm()
                val lowerLipSc   = lowerLip.toNorm()
                val leftMouthSc  = leftMouth?.toNorm()
                val rightMouthSc = rightMouth?.toNorm()
                val midpointSc   = if (leftMouthSc != null && rightMouthSc != null)
                    Offset((leftMouthSc.x + rightMouthSc.x) / 2f, (leftMouthSc.y + rightMouthSc.y) / 2f)
                else null

                val currentResult = MeasurementResult(
                    openingMm           = openingMm,
                    isAligned           = true,
                    upperLipScreen      = upperLipSc,
                    lowerLipScreen      = lowerLipSc,
                    leftMouthScreen     = leftMouthSc,
                    rightMouthScreen    = rightMouthSc,
                    mouthMidpointScreen = midpointSc,
                    deviationMm         = deviationMm,
                    deviationDirection  = deviationDir,
                    confidence          = confidence,
                    trajectoryHistory   = ArrayList(currentTrajectory),
                    detectedPattern     = currentPattern
                )

                // ── 7. Detección automática de pico máximo ─────────────
                //
                // Lógica: mientras isMeasuring = true, vamos guardando el
                // mejor (mayor) resultado visto hasta ahora. En cada frame
                // comparamos la apertura ACTUAL contra la MEJOR registrada:
                //
                //   - Si la apertura actual es nueva máxima → la guardamos
                //     y reseteamos el contador de descenso (todavía subiendo)
                //   - Si la apertura actual es menor que la mejor por al
                //     menos MIN_DROP_TO_CONFIRM_MM → contamos un frame de
                //     descenso. Si esto se repite FRAMES_TO_CONFIRM_DESCENT
                //     veces seguidas, confirmamos que el pico ya pasó y
                //     disparamos onAutoPeakDetected con el mejor resultado.
                //
                // Esto evita falsos positivos: un solo frame con ruido hacia
                // abajo no dispara la captura, se necesita una tendencia
                // sostenida de varios frames.
                if (isMeasuring && !hasPeakBeenAutoCaptured) {
                    if (openingMm >= MIN_OPEN_TO_TRACK_MM) {

                        if (openingMm > bestOpeningSoFar) {
                            // Nueva máxima — seguimos subiendo
                            bestOpeningSoFar = openingMm
                            bestResultSoFar  = currentResult.copy(isPeakCapture = true)
                            descentFrameCount = 0

                        } else if (bestOpeningSoFar - openingMm >= MIN_DROP_TO_CONFIRM_MM) {
                            // La apertura bajó de forma significativa respecto al máximo
                            descentFrameCount++

                            if (descentFrameCount >= FRAMES_TO_CONFIRM_DESCENT) {
                                // Confirmado: el pico ya pasó. Disparamos la captura
                                // automática con el MEJOR resultado registrado (no el actual,
                                // que ya está bajando)
                                hasPeakBeenAutoCaptured = true
                                bestResultSoFar?.let { onAutoPeakDetected(it) }
                            }
                        } else {
                            // Fluctuación pequeña (ruido), no cuenta como descenso real
                            descentFrameCount = 0
                        }
                    }
                }

                onResult(currentResult)
                imageProxy.close()
            }
            .addOnFailureListener { imageProxy.close() }
    }

    private fun analyzePattern(trajectory: List<Float>, currentDev: Float): OpeningPattern {
        if (trajectory.size < 5) return OpeningPattern.RECTILINEO

        val maxDeviation = trajectory.maxOf { abs(it) }
        val finalDeviation = abs(currentDev)

        return if (maxDeviation < 2.0f) {
            OpeningPattern.RECTILINEO
        } else if (finalDeviation >= 2.0f && abs(maxDeviation - finalDeviation) < 1.5f) {
            OpeningPattern.DEFLEXION
        } else {
            val crossings = countZeroCrossings(trajectory)
            if (crossings >= 1) OpeningPattern.DESVIACION_S else OpeningPattern.DESVIACION_C
        }
    }

    private fun countZeroCrossings(list: List<Float>): Int {
        var crossings = 0
        for (i in 0 until list.size - 1) {
            if ((list[i] >= 0f && list[i+1] < 0f) || (list[i] < 0f && list[i+1] >= 0f)) {
                crossings++
            }
        }
        return crossings
    }

    private fun resetTrajectory() {
        currentTrajectory.clear()
        lastPeakResult = null
    }

    private fun isInsideOval(px: Float, py: Float, cx: Float, cy: Float, a: Float, b: Float): Boolean {
        return ((px - cx) / a).pow(2) + ((py - cy) / b).pow(2) <= 1.0f
    }

    private fun distance2D(a: FaceMeshPoint, b: FaceMeshPoint): Float {
        return sqrt((a.position.x - b.position.x).pow(2) + (a.position.y - b.position.y).pow(2))
    }

    /** Mantenido por compatibilidad si se quiere capturar manualmente */
    fun consumePeak(): MeasurementResult? {
        val p = lastPeakResult
        lastPeakResult = null
        return p
    }
}
