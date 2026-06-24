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

class FaceMeshAnalyzer(
    private val useFrontCamera: Boolean,
    private val onResult: (MeasurementResult) -> Unit
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
        const val PEAK_WINDOW        = 12
        const val MIN_OPEN_MM        = 8
        const val OVAL_MARGIN        = 0.20f
        const val DENTAL_OFFSET_MM   = 2
    }

    private val detector = FaceMeshDetection.getClient(
        FaceMeshDetectorOptions.Builder()
            .setUseCase(FaceMeshDetectorOptions.FACE_MESH)
            .build()
    )

    private val recentOpenings = ArrayDeque<Int>(PEAK_WINDOW)
    private val currentTrajectory = mutableListOf<Float>()
    private var lastPeakResult: MeasurementResult? = null
    private var frameCount = 0

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

                // ── 1. Validar Óvalo ──────
                val ovalCx = imgW / 2f
                val ovalCy = imgH * OVAL_CENTER_Y_RATIO
                val semiA  = (imgW * OVAL_WIDTH_RATIO  / 2f) * (1f + OVAL_MARGIN)
                val semiB  = (imgH * OVAL_HEIGHT_RATIO / 2f) * (1f + OVAL_MARGIN)

                val pointsToCheck = listOfNotNull(leftEye, rightEye, upperLip, lowerLip)
                val faceInsideOval = pointsToCheck.all { p ->
                    isInsideOval(p.position.x, p.position.y, ovalCx, ovalCy, semiA, semiB)
                }

                if (!faceInsideOval) {
                    resetTrajectory()
                    onResult(MeasurementResult(openingMm = 0, isAligned = false, alignmentMessage = "Ajusta tu cara dentro del óvalo"))
                    imageProxy.close()
                    return@addOnSuccessListener
                }

                // ── 2. Validar Inclinación ──────
                val verticalDiff = abs(leftEye.position.y - rightEye.position.y)
                if (verticalDiff > MAX_TILT_PX) {
                    onResult(MeasurementResult(openingMm = 0, isAligned = false, alignmentMessage = "Endereza la cabeza (no la inclines)"))
                    imageProxy.close()
                    return@addOnSuccessListener
                }

                // ── 3. Escala IPD ──────
                val ipdPx = distance2D(leftEye, rightEye)
                if (ipdPx < 20f) {
                    imageProxy.close()
                    return@addOnSuccessListener
                }
                val mmPerPx = AVERAGE_IPD_MM / ipdPx

                // ── 4. Apertura ──────
                val openingPx = distance2D(upperLip, lowerLip)
                val baseOpeningMm = (openingPx * mmPerPx).toInt()
                val openingMm = if (baseOpeningMm > MIN_OPEN_MM) baseOpeningMm + DENTAL_OFFSET_MM else baseOpeningMm

                val confidence = when {
                    ipdPx < 120 -> 65
                    ipdPx < 180 -> 85
                    else -> 98
                }

                // ── 5. Desviación Horizontal ──────
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

                // ── 6. Trayectoria ──────
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

                // ── 7. Pico Máximo ──────
                if (openingMm >= MIN_OPEN_MM) {
                    if (recentOpenings.size >= PEAK_WINDOW) recentOpenings.removeFirst()
                    recentOpenings.addLast(openingMm)

                    val peakInWindow = recentOpenings.max()
                    if (recentOpenings.size >= 3 && openingMm == peakInWindow && openingMm > (recentOpenings.dropLast(1).maxOrNull() ?: 0)) {
                        lastPeakResult = currentResult.copy(isPeakCapture = true)
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
        recentOpenings.clear()
        currentTrajectory.clear()
        lastPeakResult = null
    }

    private fun isInsideOval(px: Float, py: Float, cx: Float, cy: Float, a: Float, b: Float): Boolean {
        return ((px - cx) / a).pow(2) + ((py - cy) / b).pow(2) <= 1.0f
    }

    private fun distance2D(a: FaceMeshPoint, b: FaceMeshPoint): Float {
        return sqrt((a.position.x - b.position.x).pow(2) + (a.position.y - b.position.y).pow(2))
    }

    fun consumePeak(): MeasurementResult? {
        val p = lastPeakResult
        lastPeakResult = null
        return p
    }
}