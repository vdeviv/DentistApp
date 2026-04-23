package com.revvivii.mydentapp.analyzer

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.compose.ui.geometry.Offset
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.facemesh.FaceMeshDetection
import com.google.mlkit.vision.facemesh.FaceMeshDetectorOptions
import com.google.mlkit.vision.facemesh.FaceMeshPoint
import com.revvivii.mydentapp.model.MeasurementResult
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Analizador de frames de cámara usando ML Kit Face Mesh.
 *
 * ML Kit detecta 468 puntos (landmarks) en el rostro.
 * Usamos los índices específicos de los labios para medir la apertura bucal.
 *
 * Índices clave del mapa facial de MediaPipe/ML Kit:
 *   - 13  → centro labio superior (interior)
 *   - 14  → centro labio inferior (interior)
 *   - 33  → comisura izquierda del ojo (para referencia de escala)
 *   - 263 → comisura derecha del ojo (para referencia de escala)
 *   - 168 → punta de la nariz (para verificar orientación)
 *
 * La medición se hace así:
 *   1. Distancia en píxeles entre punto 13 y 14 = apertura en px
 *   2. Distancia en px entre esquinas oculares = IPD en px
 *   3. IPD real promedio en adultos = ~63 mm
 *   4. Escala = 63 / IPD_px  →  mm_por_px
 *   5. Apertura_mm = apertura_px * mm_por_px
 */
class FaceMeshAnalyzer(
    private val onResult: (MeasurementResult) -> Unit
) : ImageAnalysis.Analyzer {

    // Índices de los landmarks que nos interesan
    companion object {
        const val UPPER_LIP_CENTER = 13   // interior labio superior
        const val LOWER_LIP_CENTER = 14   // interior labio inferior
        const val LEFT_EYE_CORNER  = 33   // esquina exterior ojo izquierdo
        const val RIGHT_EYE_CORNER = 263  // esquina exterior ojo derecho
        const val NOSE_TIP         = 168  // punta de la nariz

        // IPD (Inter-Pupillary Distance) promedio en adultos en mm
        // Usamos 63 mm como referencia estándar de calibración
        const val AVERAGE_IPD_MM = 63.0f

        // Tolerancia de inclinación máxima permitida (en px de diferencia vertical entre ojos)
        const val MAX_TILT_PX = 30f
    }

    // Detector de ML Kit configurado para face mesh completo
    private val detector = FaceMeshDetection.getClient(
        FaceMeshDetectorOptions.Builder()
            .setUseCase(FaceMeshDetectorOptions.FACE_MESH)
            .build()
    )

    @androidx.camera.core.ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: run {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

        detector.process(image)
            .addOnSuccessListener { faceMeshes ->
                if (faceMeshes.isEmpty()) {
                    // No se detectó ningún rostro
                    onResult(
                        MeasurementResult(
                            openingMm = 0,
                            isAligned = false,
                            alignmentMessage = "Coloca tu rostro en el óvalo"
                        )
                    )
                    imageProxy.close()
                    return@addOnSuccessListener
                }

                // Tomamos el primer rostro detectado
                val mesh = faceMeshes[0]
                val points = mesh.allPoints

                // Extraemos los puntos que necesitamos
                val upperLip = points.getOrNull(UPPER_LIP_CENTER)
                val lowerLip = points.getOrNull(LOWER_LIP_CENTER)
                val leftEye  = points.getOrNull(LEFT_EYE_CORNER)
                val rightEye = points.getOrNull(RIGHT_EYE_CORNER)

                if (upperLip == null || lowerLip == null || leftEye == null || rightEye == null) {
                    imageProxy.close()
                    return@addOnSuccessListener
                }

                // ── 1. Verificar alineación del rostro ──────────────────
                val alignmentCheck = checkAlignment(leftEye, rightEye, imageProxy)

                if (!alignmentCheck.first) {
                    // Rostro detectado pero mal alineado
                    onResult(
                        MeasurementResult(
                            openingMm = 0,
                            isAligned = false,
                            alignmentMessage = alignmentCheck.second
                        )
                    )
                    imageProxy.close()
                    return@addOnSuccessListener
                }

                // ── 2. Calcular escala usando IPD ───────────────────────
                val ipdPx = distance2D(leftEye, rightEye)
                if (ipdPx < 10f) {
                    // Rostro demasiado lejos o pequeño
                    onResult(
                        MeasurementResult(
                            openingMm = 0,
                            isAligned = false,
                            alignmentMessage = "Acércate un poco más"
                        )
                    )
                    imageProxy.close()
                    return@addOnSuccessListener
                }

                val mmPerPx = AVERAGE_IPD_MM / ipdPx

                // ── 3. Medir apertura bucal ─────────────────────────────
                val openingPx = distance2D(upperLip, lowerLip)
                val openingMm = (openingPx * mmPerPx).toInt()

                // ── 4. Calcular confianza ───────────────────────────────
                // La confianza sube cuando el IPD es más grande (rostro más cerca y nítido)
                val confidence = calculateConfidence(ipdPx)

                // ── 5. Escalar coordenadas a la pantalla para el overlay ─
                // ML Kit devuelve coordenadas en el espacio de la imagen original
                // Necesitamos escalarlas al tamaño de la vista de pantalla
                val scaleX = imageProxy.width.toFloat()
                val scaleY = imageProxy.height.toFloat()

                val upperLipScreen = Offset(
                    upperLip.position.x / scaleX,
                    upperLip.position.y / scaleY
                )
                val lowerLipScreen = Offset(
                    lowerLip.position.x / scaleX,
                    lowerLip.position.y / scaleY
                )

                onResult(
                    MeasurementResult(
                        openingMm = openingMm,
                        isAligned = true,
                        upperLipScreen = upperLipScreen,
                        lowerLipScreen = lowerLipScreen,
                        confidence = confidence
                    )
                )

                imageProxy.close()
            }
            .addOnFailureListener {
                imageProxy.close()
            }
    }

    /**
     * Verifica que el rostro esté:
     * 1. Razonablemente de frente (no muy inclinado)
     * 2. No demasiado ladeado horizontalmente
     *
     * @return Pair(esValido, mensajeDeError)
     */
    private fun checkAlignment(
        leftEye: FaceMeshPoint,
        rightEye: FaceMeshPoint,
        imageProxy: ImageProxy
    ): Pair<Boolean, String> {

        // Diferencia vertical entre ojos → inclinación de cabeza
        val verticalDiff = abs(leftEye.position.y - rightEye.position.y)
        if (verticalDiff > MAX_TILT_PX) {
            return Pair(false, "Endereza la cabeza (no inclines)")
        }

        // Verificar que los ojos estén aproximadamente centrados en la imagen
        val eyeCenterX = (leftEye.position.x + rightEye.position.x) / 2f
        val imageCenter = imageProxy.width / 2f
        val horizontalOffset = abs(eyeCenterX - imageCenter)

        if (horizontalOffset > imageProxy.width * 0.25f) {
            return Pair(false, "Centra tu rostro en el óvalo")
        }

        return Pair(true, "")
    }

    /**
     * Distancia euclidiana entre dos puntos del mesh en 2D (x, y)
     */
    private fun distance2D(a: FaceMeshPoint, b: FaceMeshPoint): Float {
        val dx = a.position.x - b.position.x
        val dy = a.position.y - b.position.y
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * Calcula la confianza de la medición según qué tan grande
     * se ve el rostro en pantalla.
     * - IPD < 80px → baja confianza (lejos)
     * - IPD > 200px → alta confianza (cerca y nítido)
     */
    private fun calculateConfidence(ipdPx: Float): Int {
        return when {
            ipdPx < 80  -> 40
            ipdPx < 120 -> 60
            ipdPx < 180 -> 80
            else        -> 95
        }
    }
}
