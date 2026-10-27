package com.toniaudio.djaaay.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Scritta piccola in maiuscolo come quelle degli EQ nelle bozze. */
@Composable
fun Etichetta(testo: String, colore: Color = Colori.Spento, dimensione: TextUnit = 9.sp, modifier: Modifier = Modifier) {
    Text(testo, color = colore, fontSize = dimensione, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp, modifier = modifier)
}

@Composable
fun Numero(testo: String, colore: Color = Colori.Testo, dimensione: TextUnit = 11.sp, modifier: Modifier = Modifier) {
    Text(testo, color = colore, fontSize = dimensione, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = modifier)
}

enum class TipoManopola { Normale, Sottile, Filtro }

/**
 * Manopola: si gira trascinando su e giù; doppio tocco = torna al valore di partenza.
 * `valore` 0…1 (per il filtro −1…+1, centro spento).
 */
@Composable
fun Manopola(
    valore: Float,
    onCambia: (Float) -> Unit,
    modifier: Modifier = Modifier,
    tipo: TipoManopola = TipoManopola.Normale,
    diametro: Dp = 24.dp,
    predefinito: Float = if (tipo == TipoManopola.Filtro) 0f else 0.5f,
    colore: Color = Colori.Neutro,
) {
    val minimo = if (tipo == TipoManopola.Filtro) -1f else 0f
    val attuale by rememberUpdatedState(valore)
    val cambia by rememberUpdatedState(onCambia)
    Canvas(
        modifier
            .size(diametro)
            .pointerInput(tipo) {
                detectTapGestures(onDoubleTap = { cambia(predefinito) })
            }
            .pointerInput(tipo) {
                awaitEachGesture {
                    // anche se il riconoscitore del doppio tocco ha già "preso" il contatto
                    val giu = awaitFirstDown(requireUnconsumed = false)
                    var y = giu.position.y
                    var v = attuale
                    do {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == giu.id } ?: break
                        val dy = c.position.y - y
                        y = c.position.y
                        if (dy != 0f) {
                            // 150 dp di corsa = tutta la manopola
                            v = (v - dy / (150.dp.toPx()) * (1f - minimo)).coerceIn(minimo, 1f)
                            cambia(v)
                            c.consume()
                        }
                    } while (c.pressed)
                }
            },
    ) {
        val r = size.minDimension / 2
        val centro = Offset(size.width / 2, size.height / 2)
        val frazione = (valore - minimo) / (1f - minimo)  // 0…1
        val angolo = -135f + 270f * frazione
        when (tipo) {
            TipoManopola.Sottile -> {
                drawCircle(Colori.Scuro, r - 1.dp.toPx(), centro, style = Stroke(1.5.dp.toPx()))
            }
            TipoManopola.Normale -> {
                drawCircle(Brush.radialGradient(listOf(Color(0xFF353C50), Colori.Pannello2), centro, r), r, centro)
                drawCircle(Color(0xFF2D3448), r, centro, style = Stroke(1.dp.toPx()))
            }
            TipoManopola.Filtro -> {
                val anello = 3.dp.toPx()
                drawCircle(Colori.Linea, r - anello / 2, centro, style = Stroke(anello))
                // arco dal centro (ore 12) verso il valore: sinistra passa-basso, destra passa-alto
                if (valore != 0f) {
                    drawArc(
                        colore, -90f, 135f * valore, false,
                        topLeft = Offset(anello / 2, anello / 2), size = Size(size.width - anello, size.height - anello),
                        style = Stroke(anello),
                    )
                }
                drawCircle(Brush.radialGradient(listOf(Color(0xFF3A4258), Color(0xFF171B27)), centro, r - anello), r - anello * 1.4f, centro)
            }
        }
        rotate(angolo, centro) {
            val lunghezza = if (tipo == TipoManopola.Filtro) r * 0.45f else r * 0.55f
            val inizio = if (tipo == TipoManopola.Sottile) r * 0.2f else r * 0.25f
            drawLine(
                if (tipo == TipoManopola.Filtro) colore else Color.White,
                Offset(centro.x, centro.y - inizio - lunghezza + r * 0.1f),
                Offset(centro.x, centro.y - inizio),
                strokeWidth = if (tipo == TipoManopola.Sottile) 1.5.dp.toPx() else 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * Fader verticale (volumi, pitch): si trascina il cappuccio; `centrale` disegna lo zero al centro (pitch).
 * `valore` 0…1 (1 in alto).
 */
@Composable
fun FaderVerticale(
    valore: Float,
    onCambia: (Float) -> Unit,
    modifier: Modifier = Modifier,
    centrale: Boolean = false,
    larghezza: Dp = 24.dp,
) {
    val attuale by rememberUpdatedState(valore)
    val cambia by rememberUpdatedState(onCambia)
    Canvas(
        modifier
            .width(larghezza)
            .pointerInput(Unit) {
                awaitEachGesture {
                    // anche se il riconoscitore del doppio tocco ha già "preso" il contatto
                    val giu = awaitFirstDown(requireUnconsumed = false)
                    var y = giu.position.y
                    var v = attuale
                    do {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == giu.id } ?: break
                        val dy = c.position.y - y
                        y = c.position.y
                        if (dy != 0f) {
                            v = (v - dy / (size.height - 16.dp.toPx())).coerceIn(0f, 1f)
                            cambia(v)
                            c.consume()
                        }
                    } while (c.pressed)
                }
            }
            .pointerInput(centrale) { detectTapGestures(onDoubleTap = { if (centrale) cambia(0.5f) }) },
    ) {
        val arrot = CornerRadius(6.dp.toPx())
        drawRoundRect(Color(0xFF0F1219), cornerRadius = arrot)
        drawRoundRect(Colori.Linea, cornerRadius = arrot, style = Stroke(1.dp.toPx()))
        val margine = 8.dp.toPx()
        drawLine(Colori.Linea, Offset(size.width / 2, margine), Offset(size.width / 2, size.height - margine), 2.dp.toPx())
        if (centrale) {
            drawLine(Colori.Spento, Offset(-4.dp.toPx(), size.height / 2), Offset(size.width + 4.dp.toPx(), size.height / 2), 1.dp.toPx())
        }
        val altezzaCap = 14.dp.toPx()
        val y = margine + (1f - valore) * (size.height - 2 * margine) - altezzaCap / 2
        drawRoundRect(Color(0xFFD9DEE8), Offset(2.dp.toPx(), y), Size(size.width - 4.dp.toPx(), altezzaCap), CornerRadius(3.dp.toPx()))
        drawLine(Colori.Sfondo, Offset(5.dp.toPx(), y + altezzaCap / 2), Offset(size.width - 5.dp.toPx(), y + altezzaCap / 2), 2.dp.toPx())
    }
}

/** Crossfader: segue il dito in posizione assoluta (per i tagli veloci da scratch). `valore` 0 = A … 1 = B. */
@Composable
fun Crossfader(valore: Float, onCambia: (Float) -> Unit, modifier: Modifier = Modifier) {
    val cambia by rememberUpdatedState(onCambia)
    Canvas(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                val giu = awaitFirstDown()
                val margine = 13.dp.toPx()
                fun daX(x: Float) = ((x - margine) / (size.width - 2 * margine)).coerceIn(0f, 1f)
                cambia(daX(giu.position.x))
                do {
                    val e = awaitPointerEvent()
                    val c = e.changes.firstOrNull { it.id == giu.id } ?: break
                    cambia(daX(c.position.x))
                    c.consume()
                } while (c.pressed)
            }
        },
    ) {
        val arrot = CornerRadius(7.dp.toPx())
        drawRoundRect(Color(0xFF0F1219), cornerRadius = arrot)
        drawRoundRect(Colori.Linea, cornerRadius = arrot, style = Stroke(1.dp.toPx()))
        val margine = 13.dp.toPx()
        drawLine(Colori.Linea, Offset(margine, size.height / 2), Offset(size.width - margine, size.height / 2), 2.dp.toPx())
        val capL = 26.dp.toPx()
        val x = margine + valore * (size.width - 2 * margine)
        drawRoundRect(Color(0xFFD9DEE8), Offset(x - capL / 2, 3.dp.toPx()), Size(capL, size.height - 6.dp.toPx()), CornerRadius(3.dp.toPx()))
    }
}

/** Simbolo della curva del crossfader (taglio ⊓, lineare ×, morbida), grigio come le scritte degli EQ. */
@Composable
fun SimboloCurva(curva: Int, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 22.dp, height = 11.dp)) {
        val w = size.width
        val h = size.height
        val s = 1.6.dp.toPx()
        val c = Colori.Spento
        fun linea(a: Offset, b: Offset) = drawLine(c, a, b, s, StrokeCap.Round)
        when (curva) {
            0 -> {  // taglio: pieni quasi ovunque, giù di colpo agli estremi
                linea(Offset(0f, h), Offset(w * 0.07f, 0f)); linea(Offset(w * 0.07f, 0f), Offset(w * 0.93f, 0f)); linea(Offset(w * 0.93f, 0f), Offset(w, h))
            }
            1 -> { linea(Offset(0f, 0f), Offset(w, h)); linea(Offset(w, 0f), Offset(0f, h)) }
            else -> {
                val p1 = androidx.compose.ui.graphics.Path().apply { moveTo(0f, 0f); cubicTo(w * 0.4f, 0f, w * 0.7f, h * 0.25f, w, h) }
                val p2 = androidx.compose.ui.graphics.Path().apply { moveTo(w, 0f); cubicTo(w * 0.6f, 0f, w * 0.3f, h * 0.25f, 0f, h) }
                drawPath(p1, c, style = Stroke(s, cap = StrokeCap.Round))
                drawPath(p2, c, style = Stroke(s, cap = StrokeCap.Round))
            }
        }
    }
}

/** Meter verticale (verde/giallo/rosso). `livello` 0…1. */
@Composable
fun Meter(livello: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val arrot = CornerRadius(size.width / 2)
        drawRoundRect(
            Brush.verticalGradient(
                0f to Colori.RossoMeter, 0.12f to Colori.RossoMeter, 0.12f to Colori.Arancio, 0.3f to Colori.Arancio,
                0.3f to Colori.Verde, 1f to Colori.Verde,
            ),
            cornerRadius = arrot,
        )
        // la parte spenta copre dall'alto fino al livello (scala in radice: si vedono anche i suoni bassi)
        val acceso = kotlin.math.sqrt(livello.coerceIn(0f, 1f))
        drawRect(Colori.Sfondo, size = Size(size.width, size.height * (1f - acceso)))
    }
}

/**
 * Tasto rettangolare dei deck (CUE, Play, SYNC, pad). I gesti non si riarmano quando lo schermo si aggiorna
 * (succede a ogni fotogramma): altrimenti un CUE tenuto premuto perderebbe il rilascio.
 */
@Composable
fun Tasto(
    modifier: Modifier = Modifier,
    acceso: Boolean = false,
    coloreAcceso: Color = Colori.Neutro,
    bordo: Color? = null,
    onTocco: (() -> Unit)? = null,
    onPremi: (() -> Unit)? = null,
    onRilascia: (() -> Unit)? = null,
    onTieni: (() -> Unit)? = null,
    contenuto: @Composable () -> Unit,
) {
    val forma = RoundedCornerShape(7.dp)
    val tocco by rememberUpdatedState(onTocco)
    val premi by rememberUpdatedState(onPremi)
    val rilascia by rememberUpdatedState(onRilascia)
    val tieni by rememberUpdatedState(onTieni)
    val conTieni = onTieni != null
    Box(
        modifier
            .background(if (acceso) coloreAcceso else Colori.Pannello2, forma)
            .border(1.dp, bordo ?: if (acceso) coloreAcceso else Colori.Linea, forma)
            .pointerInput(conTieni) {
                detectTapGestures(
                    onPress = {
                        premi?.invoke()
                        tryAwaitRelease()
                        rilascia?.invoke()
                    },
                    onTap = { tocco?.invoke() },
                    onLongPress = if (conTieni) ({ _: Offset -> tieni?.invoke(); Unit }) else null,
                )
            },
        contentAlignment = Alignment.Center,
    ) { contenuto() }
}

/** Schede a "pillola" (HOT CUE · LOOP · FX, MIXER · FX, schede delle opzioni). */
@Composable
fun Schede(voci: List<String>, scelta: Int, onScegli: (Int) -> Unit, modifier: Modifier = Modifier, dimensione: TextUnit = 9.sp) {
    val scegli by rememberUpdatedState(onScegli)
    Row(modifier.background(Colori.Pannello, RoundedCornerShape(7.dp)).padding(2.dp)) {
        voci.forEachIndexed { i, v ->
            Box(
                Modifier
                    .weight(1f)
                    .background(if (i == scelta) Colori.Pannello2 else Color.Transparent, RoundedCornerShape(5.dp))
                    .pointerInput(i) { detectTapGestures { scegli(i) } }
                    .padding(vertical = 3.dp),
                contentAlignment = Alignment.Center,
            ) { Etichetta(v, if (i == scelta) Colori.Testo else Colori.Spento, dimensione) }
        }
    }
}
