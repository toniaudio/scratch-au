package com.toniaudio.djaaay.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.toniaudio.djaaay.StatoDeck
import kotlin.math.PI
import kotlin.math.atan2

/** Un giro di vinile a 33⅓ giri = 1,8 secondi di brano. */
const val SECONDI_PER_GIRO = 1.8

/**
 * Il disco: gira con il brano; il dito lo afferra e lo trascina (scratch). Girando in senso orario si va avanti.
 * `sensibilita` 1 = un giro di dito sposta il brano come un giro di vinile vero.
 */
@Composable
fun Piatto(
    deck: StatoDeck,
    sensibilita: Float,
    onToccoInizio: () -> Unit,
    onToccoSposta: (Double) -> Unit,
    onToccoFine: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sens by rememberUpdatedState(sensibilita)
    val inizio by rememberUpdatedState(onToccoInizio)
    val sposta by rememberUpdatedState(onToccoSposta)
    val fine by rememberUpdatedState(onToccoFine)
    val colore = Colori.deck(deck.indice)

    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val giu = awaitFirstDown()
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        var angolo = atan2(giu.position.y - cy, giu.position.x - cx)
                        var giri = 0.0
                        inizio()
                        do {
                            val e = awaitPointerEvent()
                            val c = e.changes.firstOrNull { it.id == giu.id } ?: break
                            val nuovo = atan2(c.position.y - cy, c.position.x - cx)
                            var delta = (nuovo - angolo).toDouble()
                            if (delta > PI) delta -= 2 * PI
                            if (delta < -PI) delta += 2 * PI
                            angolo = nuovo
                            if (delta != 0.0) {
                                giri += delta / (2 * PI)
                                sposta(giri * SECONDI_PER_GIRO * sens)
                            }
                            c.consume()
                        } while (c.pressed)
                        fine()
                    }
                },
        ) {
            val r = size.minDimension / 2
            val centro = Offset(size.width / 2, size.height / 2)
            // solchi
            drawCircle(Color(0xFF15171D), r, centro)
            var rr = r - 4.dp.toPx()
            val solco = 3.2.dp.toPx()
            while (rr > r * 0.36f) {
                drawCircle(Color(0xFF1D2029), rr, centro, style = Stroke(solco / 2))
                rr -= solco
            }
            drawCircle(Color(0xFF2D3448), r - 1.5.dp.toPx(), centro, style = Stroke(3.dp.toPx()))
            // etichetta colorata del deck
            drawCircle(colore, r * 0.33f, centro)
            // segno che gira con il brano (un giro ogni 1,8 s)
            val angolo = ((deck.posizione / SECONDI_PER_GIRO) % 1.0 * 360.0).toFloat()
            rotate(angolo, centro) {
                drawLine(Color.White, Offset(centro.x, centro.y - r * 0.92f), Offset(centro.x, centro.y - r * 0.55f), 3.dp.toPx(), StrokeCap.Round)
            }
        }
        Etichetta(deck.lettera, Colori.Sfondo, 10.sp)
    }
}
