package com.screenshotbrain

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

enum class Ic { Back, Close, Trash, Refresh, Check, Search }

@Composable
fun SbIcon(ic: Ic, tint: Color, modifier: Modifier = Modifier, dp: Dp = 22.dp) {
    Canvas(modifier.size(dp)) {
        val w = this.size.width
        val sw = w * 0.08f
        fun p(x: Float, y: Float) = Offset(x * w, y * w)
        fun line(a: Offset, b: Offset) = drawLine(tint, a, b, strokeWidth = sw, cap = StrokeCap.Round)
        when (ic) {
            Ic.Back -> {
                line(p(.6f, .2f), p(.3f, .5f)); line(p(.3f, .5f), p(.6f, .8f)); line(p(.32f, .5f), p(.8f, .5f))
            }
            Ic.Close -> {
                line(p(.28f, .28f), p(.72f, .72f)); line(p(.72f, .28f), p(.28f, .72f))
            }
            Ic.Check -> {
                line(p(.27f, .52f), p(.43f, .68f)); line(p(.43f, .68f), p(.74f, .33f))
            }
            Ic.Search -> {
                drawCircle(tint, radius = w * .24f, center = p(.45f, .45f), style = Stroke(sw))
                line(p(.63f, .63f), p(.8f, .8f))
            }
            Ic.Trash -> {
                line(p(.2f, .28f), p(.8f, .28f))
                line(p(.4f, .28f), p(.4f, .2f)); line(p(.4f, .2f), p(.6f, .2f)); line(p(.6f, .2f), p(.6f, .28f))
                val body = Path().apply {
                    moveTo(.3f * w, .38f * w); lineTo(.34f * w, .8f * w)
                    lineTo(.66f * w, .8f * w); lineTo(.7f * w, .38f * w)
                }
                drawPath(body, tint, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
                line(p(.45f, .5f), p(.45f, .68f)); line(p(.55f, .5f), p(.55f, .68f))
            }
            Ic.Refresh -> {
                drawArc(
                    tint, startAngle = -50f, sweepAngle = 280f, useCenter = false,
                    topLeft = p(.2f, .2f), size = Size(.6f * w, .6f * w),
                    style = Stroke(sw, cap = StrokeCap.Round)
                )
                val a = Math.toRadians(230.0)
                val r = .3f * w
                val tip = Offset(.5f * w + r * cos(a).toFloat(), .5f * w + r * sin(a).toFloat())
                val bx = sin(a).toFloat()
                val by = -cos(a).toFloat()
                fun barb(rot: Double): Offset {
                    val c = cos(rot).toFloat(); val s = sin(rot).toFloat()
                    return Offset(tip.x + (bx * c - by * s) * .17f * w, tip.y + (bx * s + by * c) * .17f * w)
                }
                line(tip, barb(.6)); line(tip, barb(-.6))
            }
        }
    }
}
