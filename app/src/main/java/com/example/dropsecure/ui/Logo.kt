package com.example.dropsecure.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp

/** Shield with a downward arrow, drawn in code so no image asset is needed. */
@Composable
fun DropSecureLogo(size: Dp, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val shield = Path().apply {
            moveTo(w * 0.5f, h * 0.04f)
            lineTo(w * 0.9f, h * 0.2f)
            lineTo(w * 0.9f, h * 0.5f)
            quadraticBezierTo(w * 0.9f, h * 0.82f, w * 0.5f, h * 0.97f)
            quadraticBezierTo(w * 0.1f, h * 0.82f, w * 0.1f, h * 0.5f)
            lineTo(w * 0.1f, h * 0.2f)
            close()
        }
        drawPath(shield, color)
        val stroke = w * 0.09f
        drawLine(Color.White, Offset(w * 0.5f, h * 0.27f), Offset(w * 0.5f, h * 0.65f), stroke, StrokeCap.Round)
        drawLine(Color.White, Offset(w * 0.5f, h * 0.67f), Offset(w * 0.35f, h * 0.5f), stroke, StrokeCap.Round)
        drawLine(Color.White, Offset(w * 0.5f, h * 0.67f), Offset(w * 0.65f, h * 0.5f), stroke, StrokeCap.Round)
    }
}
