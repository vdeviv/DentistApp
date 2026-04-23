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
 * @param confidence     Porcentaje de confianza de la estimación (0-100)
 */
data class MeasurementResult(
    val openingMm: Int,
    val isAligned: Boolean,
    val alignmentMessage: String = "",
    val upperLipScreen: Offset? = null,
    val lowerLipScreen: Offset? = null,
    val confidence: Int = 0
)
