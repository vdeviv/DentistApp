package com.revvivii.mydentapp.model

import androidx.compose.ui.geometry.Offset

/**
 * Resultado de una medición de apertura bucal.
 *
 * @param openingMm      Apertura estimada en milímetros
 * @param isAligned      true si el rostro está correctamente posicionado
 * @param alignmentMessage Mensaje de guía cuando el rostro no está alineado
 * @param upperLipScreen Posición en pantalla del punto del labio superior (para dibujar)
 * @param lowerLipScreen Posición en pantalla del punto del labio inferior (para dibujar)
 * @param leftMouthScreen Comisura izquierda de la boca
 * @param rightMouthScreen Comisura derecha de la boca
 * @param mouthMidpointScreen Punto medio de la linea de la boca
 * @param deviationMm Desviacion lateral en mm (+ = derecha, - = izquierda)
 * @param deviationDirection "Centro", "Derecha" o "Izquierda"
 * @param confidence     Porcentaje de confianza de la estimación (0-100)
 * @param isPeakCapture true cuando fue capturado en pico maximo de apertura
 */
enum class OpeningPattern(val description: String) {
    RECTILINEO("Línea Recta (Normal)"),
    DESVIACION_C("Desviación en 'C' (Sugerente de reducción de disco)"),
    DESVIACION_S("Desviación en 'S' (Sugerente de alteración de coordinación muscular)"),
    DEFLEXION("Deflexión Lateral (Sugerente de bloqueo/desplazamiento sin reducción)")
}

data class MeasurementResult(
    val openingMm: Int,
    val isAligned: Boolean,
    val alignmentMessage: String = "",
    val upperLipScreen: Offset? = null,
    val lowerLipScreen: Offset? = null,
    val leftMouthScreen: Offset? = null,
    val rightMouthScreen: Offset? = null,
    val mouthMidpointScreen: Offset? = null,
    val deviationMm: Float = 0f,
    val deviationDirection: String = "Centro",
    val confidence: Int = 0,
    val isPeakCapture: Boolean = false,
    val trajectoryHistory: List<Float> = emptyList(),
    val detectedPattern: OpeningPattern = OpeningPattern.RECTILINEO,
    val faceMeshPoints: List<Offset> = emptyList()
)