package com.revvivii.mydentapp.analyzer

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.compose.ui.geometry.Offset
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.facemesh.FaceMesh
import com.google.mlkit.vision.facemesh.FaceMeshDetection
import com.google.mlkit.vision.facemesh.FaceMeshDetectorOptions
import com.google.mlkit.vision.facemesh.FaceMeshPoint
import com.revvivii.mydentapp.model.MeasurementResult
import com.revvivii.mydentapp.model.OpeningPattern
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Analizador de frames con ML Kit Face Mesh.
 *
 * ════════════════════════════════════════════════════════════════════
 * CAMBIO DE ARQUITECTURA: Óvalo manual → Zona de Éxito Virtual
 * ════════════════════════════════════════════════════════════════════
 *
 * ANTES: Se dibujaba un óvalo fijo en pantalla y se comprobaba si los
 * puntos del rostro caían dentro de esa elipse. Esto obligaba al dentista
 * a encuadrar manualmente y dependía de la resolución/aspect ratio de
 * cada teléfono, generando inconsistencia.
 *
 * AHORA: No hay ningún elemento visual de encuadre. La validación es
 * puramente matemática sobre la geometría del Face Mesh, sin importar
 * dónde esté la cara en la pantalla:
 *
 *   1. ALINEACIÓN (roll/yaw aproximados):
 *      - Roll (inclinación lateral de cabeza) → diferencia de altura Y
 *        entre los dos ojos. Cabeza derecha = diferencia ≈ 0.
 *      - Yaw (giro de cabeza izq/der) → comparamos la distancia de cada
 *        ojo a la punta de la nariz. Si el rostro está de frente, ambas
 *        distancias son similares; si gira, una se acorta y otra se
 *        alarga.
 *
 *   2. DISTANCIA ÓPTIMA (proxy de "ni muy cerca ni muy lejos"):
 *      - Se mide el IPD en píxeles (distancia entre comisuras oculares).
 *      - Debe caer dentro de un rango umbral fijo, p.ej. [150, 250] px.
 *        Este rango se calibra para que corresponda a una distancia real
 *        cómoda de exploración clínica (~25-40cm aprox, depende del FOV
 *        de la cámara del dispositivo).
 *
 *   3. ESTABILIDAD SOSTENIDA (1 segundo):
 *      - No basta con que UN frame cumpla las condiciones. Se exige que
 *        la "Zona de Éxito" se mantenga cumplida de forma CONTINUA
 *        durante STABILITY_WINDOW_MS milisegundos antes de empezar a
 *        considerar la medición válida. Esto evita capturas accidentales
 *        por un parpadeo de detección.
 *
 * ════════════════════════════════════════════════════════════════════
 * NORMALIZACIÓN DE ESCALA (IPD como regla absoluta)
 * ════════════════════════════════════════════════════════════════════
 *
 * NOTA IMPORTANTE sobre la fórmula pedida originalmente
 * (factor_escala = distancia_ojos_base / distancia_ojos_abierta):
 *
 * Esa fórmula compara el IPD en dos instantes distintos (boca cerrada
 * vs boca abierta). Pero el IPD NO cambia por abrir la boca — solo
 * cambia si el teléfono se aleja o acerca físicamente. Si el dentista
 * sostiene el teléfono fijo durante toda la prueba (que es el flujo
 * normal), ese cociente es ≈ 1.0 siempre y no corrige nada.
 *
 * La forma correcta de "neutralizar" el movimiento del teléfono es usar
 * el IPD como regla de calibración EN CADA FRAME INDIVIDUAL (no como
 * cociente entre dos tomas):
 *
 *     mm_por_px  = IPD_PROMEDIO_MM / IPD_medido_en_ESTE_frame_px
 *     apertura_mm = apertura_px(labio_sup, labio_inf) × mm_por_px
 *
 * Esto sí compensa automáticamente la distancia: si el teléfono se aleja,
 * el IPD_px baja, mm_por_px sube, y la apertura en mm se mantiene estable
 * aunque la apertura en píxeles haya disminuido por la distancia.
 * Esta es la lógica que ya implementábamos y la mantenemos aquí, ahora
 * sin depender de ningún óvalo dibujado.
 */
class FaceMeshAnalyzer(
    private val useFrontCamera: Boolean,
    private val onResult: (MeasurementResult) -> Unit,
    private val onAutoPeakDetected: (MeasurementResult) -> Unit = {}
) : ImageAnalysis.Analyzer {

    companion object {
        // ── Landmarks del Face Mesh (468 puntos, índices MediaPipe) ──
        const val UPPER_LIP_CENTER   = 13
        const val LOWER_LIP_CENTER   = 14
        const val LEFT_EYE_CORNER    = 33
        const val RIGHT_EYE_CORNER   = 263
        const val LEFT_MOUTH_CORNER  = 61
        const val RIGHT_MOUTH_CORNER = 291
        const val NOSE_TIP           = 1     // punta de nariz, para estimar yaw

        // ── Calibración de escala ──────────────────────────────────
        const val AVERAGE_IPD_MM     = 63.0f
        const val DENTAL_OFFSET_MM   = 2     // compensa grosor de labios

        // ── Zona de Éxito Virtual: distancia óptima ─────────────────
        // Rango de IPD en píxeles considerado "distancia correcta".
        // Si IPD_px < MIN → cara muy lejos (poca resolución, impreciso)
        // Si IPD_px > MAX → cara muy cerca (riesgo de distorsión de lente)
        const val MIN_IPD_PX = 150f
        const val MAX_IPD_PX = 250f

        // ── Zona de Éxito Virtual: alineación ───────────────────────
        // Roll: diferencia vertical máxima entre ojos (px) para considerar
        // la cabeza "derecha" (no inclinada lateralmente)
        const val MAX_ROLL_PX = 25f
        // Yaw: máxima asimetría permitida entre dist(ojoIzq,nariz) y
        // dist(ojoDer,nariz), como fracción del promedio de ambas.
        // 0.15 = 15% de asimetría tolerada antes de considerar que el
        // rostro está girado y no de frente.
        const val MAX_YAW_ASYMMETRY = 0.22f

        // ── Estabilidad sostenida ───────────────────────────────────
        // Tiempo que la Zona de Éxito debe cumplirse de forma continua
        // antes de empezar a aceptar mediciones como válidas
        const val STABILITY_WINDOW_MS = 1000L

        // ── Detección de pico máximo (igual que antes) ──────────────
        const val MIN_OPEN_MM = 8
        const val FRAMES_TO_CONFIRM_DESCENT = 4
        const val MIN_DROP_TO_CONFIRM_MM = 3
        const val MIN_OPEN_TO_TRACK_MM = 12
    }

    private val detector = FaceMeshDetection.getClient(
        FaceMeshDetectorOptions.Builder()
            .setUseCase(FaceMeshDetectorOptions.FACE_MESH)
            .build()
    )

    private val currentTrajectory = mutableListOf<Float>()
    private var frameCount = 0

    // ── Estado de estabilidad (Zona de Éxito) ───────────────────────
    // Marca de tiempo (ms) desde la cual la Zona de Éxito se cumple sin
    // interrupción. null = no se está cumpliendo actualmente.
    private var zoneEnteredAtMs: Long? = null

    // ── Estado de medición automática (pico máximo) ─────────────────
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
        zoneEnteredAtMs = null
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
                handleResult(faceMeshes, rotationDegrees, imageProxy)
                imageProxy.close()
            }
            .addOnFailureListener { imageProxy.close() }
    }

    private fun handleResult(
        faceMeshes: List<FaceMesh>,
        rotationDegrees: Int,
        imageProxy: ImageProxy
    ) {
        if (faceMeshes.isEmpty()) {
            zoneEnteredAtMs = null
            resetTrajectory()
            onResult(
                MeasurementResult(
                    openingMm = 0, isAligned = false,
                    alignmentMessage = "Apunta la cámara hacia el rostro"
                )
            )
            return
        }

        val mesh   = faceMeshes[0]
        val points = mesh.allPoints

        val upperLip   = points.getOrNull(UPPER_LIP_CENTER)
        val lowerLip   = points.getOrNull(LOWER_LIP_CENTER)
        val leftEye    = points.getOrNull(LEFT_EYE_CORNER)
        val rightEye   = points.getOrNull(RIGHT_EYE_CORNER)
        val leftMouth  = points.getOrNull(LEFT_MOUTH_CORNER)
        val rightMouth = points.getOrNull(RIGHT_MOUTH_CORNER)
        val noseTip    = points.getOrNull(NOSE_TIP)

        if (upperLip == null || lowerLip == null || leftEye == null || rightEye == null) {
            return
        }

        // ════════════════════════════════════════════════════════════
        // 1. ZONA DE ÉXITO VIRTUAL — validación matemática sin overlay
        // ════════════════════════════════════════════════════════════

        val ipdPx = distance2D(leftEye, rightEye)

        // ── 1a. Distancia óptima ────────────────────────────────────
        val distanceOk = ipdPx in MIN_IPD_PX..MAX_IPD_PX

        // ── 1b. Roll (inclinación lateral) ──────────────────────────
        val rollPx = abs(leftEye.position.y - rightEye.position.y)
        val rollOk = rollPx <= MAX_ROLL_PX

        // ── 1c. Yaw (giro de cabeza) ─────────────────────────────────
        // Si no tenemos punta de nariz, omitimos esta validación (no
        // bloqueamos la medición solo por falta de un punto opcional)
        val yawOk = if (noseTip != null) {

            val distLeftToNose = distance2D(leftEye, noseTip)
            val distRightToNose = distance2D(rightEye, noseTip)

            // Normalización usando la distancia interpupilar
            val asymmetry = abs(distLeftToNose - distRightToNose) / ipdPx

            asymmetry <= MAX_YAW_ASYMMETRY

        } else {
            true
        }

        val zoneOk = distanceOk && rollOk && yawOk

        // ── 1d. Mensaje específico de guía cuando algo falla ────────
        val guidanceMessage = when {
            !distanceOk && ipdPx < MIN_IPD_PX -> "Acerca el teléfono al rostro"
            !distanceOk && ipdPx > MAX_IPD_PX -> "Aleja un poco el teléfono"
            !rollOk -> "Endereza la cabeza (no la inclines)"
            !yawOk  -> "Gira el rostro para mirar de frente"
            else -> ""
        }

        // ════════════════════════════════════════════════════════════
        // 2. ESTABILIDAD SOSTENIDA — debe cumplirse 1s sin interrupción
        // ════════════════════════════════════════════════════════════

        val now = System.currentTimeMillis()

        if (!zoneOk) {
            // Cualquier condición que falle reinicia el contador de estabilidad
            zoneEnteredAtMs = null
            onResult(
                MeasurementResult(
                    openingMm = 0, isAligned = false,
                    alignmentMessage = guidanceMessage
                )
            )
            return
        }

        // La zona se cumple en este frame. Si es la primera vez, marcamos
        // el instante de entrada. Si ya estaba marcado, comprobamos cuánto
        // tiempo lleva sostenida.
        if (zoneEnteredAtMs == null) {
            zoneEnteredAtMs = now
        }
        val stableForMs = now - (zoneEnteredAtMs ?: now)
        val isStable = stableForMs >= STABILITY_WINDOW_MS

        if (!isStable) {
            // Aún cumpliendo, pero no ha pasado 1 segundo todavía
            val remainingMs =
                (STABILITY_WINDOW_MS - stableForMs)
                    .coerceAtLeast(0L)

            val remainingText =
                String.format("%.1f", remainingMs / 1000.0f)

            onResult(
                MeasurementResult(
                    openingMm = 0,
                    isAligned = false,
                    alignmentMessage = "Mantén la posición... ($remainingText s)"
                )
            )
            return
        }

        // ════════════════════════════════════════════════════════════
        // 3. MEDICIÓN — la Zona de Éxito lleva ≥1s estable, medimos
        // ════════════════════════════════════════════════════════════

        val isRotated = rotationDegrees == 90 || rotationDegrees == 270
        val imgW = if (isRotated) imageProxy.height.toFloat() else imageProxy.width.toFloat()
        val imgH = if (isRotated) imageProxy.width.toFloat() else imageProxy.height.toFloat()

        // ── 3a. Escala mm/px usando IPD como regla absoluta ─────────
        // Esto es lo que neutraliza el efecto de que el teléfono esté
        // más cerca o más lejos: cada frame se autocalibra con su propio
        // IPD, sin depender de una toma de referencia anterior.
        val mmPerPx = AVERAGE_IPD_MM / ipdPx

        // ── 3b. Apertura bucal ───────────────────────────────────────
        val openingPx = distance2D(upperLip, lowerLip)
        val baseOpeningMm = (openingPx * mmPerPx).toInt()
        val openingMm = if (baseOpeningMm > MIN_OPEN_MM) baseOpeningMm + DENTAL_OFFSET_MM else baseOpeningMm

        // Confianza: ahora basada en qué tan centrado está el IPD dentro
        // del rango óptimo [MIN_IPD_PX, MAX_IPD_PX] — el centro del rango
        // da la mayor confianza
        val rangeCenter = (MIN_IPD_PX + MAX_IPD_PX) / 2f
        val rangeHalfWidth = (MAX_IPD_PX - MIN_IPD_PX) / 2f
        val distanceFromCenter = abs(ipdPx - rangeCenter) / rangeHalfWidth
        val confidence = (98 - (distanceFromCenter * 25)).toInt().coerceIn(65, 98)

        // ── 3c. Desviación mandibular ────────────────────────────────
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

        // ── 3d. Trayectoria de desviación (para patrón C/S/Deflexión) ─
        if (baseOpeningMm > MIN_OPEN_MM) {
            currentTrajectory.add(deviationMm)
        } else if (currentTrajectory.size > 3) {
            currentTrajectory.clear()
        }
        val currentPattern = analyzePattern(currentTrajectory, deviationMm)

        // ── 3e. Coordenadas normalizadas [0..1] para overlay ─────────
        fun FaceMeshPoint.toNorm() = Offset(position.x / imgW, position.y / imgH)
        val upperLipSc   = upperLip.toNorm()
        val lowerLipSc   = lowerLip.toNorm()
        val leftMouthSc  = leftMouth?.toNorm()
        val rightMouthSc = rightMouth?.toNorm()
        val midpointSc   = if (leftMouthSc != null && rightMouthSc != null)
            Offset((leftMouthSc.x + rightMouthSc.x) / 2f, (leftMouthSc.y + rightMouthSc.y) / 2f)
        else null

        val meshPoints = points.map {
            Offset(
                it.position.x / imgW,
                it.position.y / imgH
            )
        }

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
            detectedPattern     = currentPattern,
            faceMeshPoints = meshPoints
        )

        // ════════════════════════════════════════════════════════════
        // 4. DETECCIÓN AUTOMÁTICA DE PICO MÁXIMO Y AUTO-CAPTURA
        // ════════════════════════════════════════════════════════════
        //
        // Mientras isMeasuring = true, vamos guardando el mayor valor de
        // apertura visto. Si la apertura empieza a bajar de forma
        // sostenida (≥3mm de caída durante 4 frames seguidos), asumimos
        // que el pico ya ocurrió y disparamos la captura automática con
        // el MEJOR resultado registrado (no el actual, que ya va bajando).
        if (isMeasuring && !hasPeakBeenAutoCaptured) {
            if (openingMm >= MIN_OPEN_TO_TRACK_MM) {

                if (openingMm > bestOpeningSoFar) {
                    bestOpeningSoFar = openingMm
                    bestResultSoFar  = currentResult.copy(isPeakCapture = true)
                    descentFrameCount = 0

                } else if (bestOpeningSoFar - openingMm >= MIN_DROP_TO_CONFIRM_MM) {
                    descentFrameCount++
                    if (descentFrameCount >= FRAMES_TO_CONFIRM_DESCENT) {
                        hasPeakBeenAutoCaptured = true
                        bestResultSoFar?.let { onAutoPeakDetected(it) }
                    }
                } else {
                    descentFrameCount = 0
                }
            }
        }

        onResult(currentResult)
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
            if ((list[i] >= 0f && list[i + 1] < 0f) || (list[i] < 0f && list[i + 1] >= 0f)) {
                crossings++
            }
        }
        return crossings
    }

    private fun resetTrajectory() {
        currentTrajectory.clear()
    }

    private fun distance2D(a: FaceMeshPoint, b: FaceMeshPoint): Float {
        return sqrt((a.position.x - b.position.x).pow(2) + (a.position.y - b.position.y).pow(2))
    }
}
