package com.toniaudio.djaaay.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.drawscope.DrawScope
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
 * Fader verticale (volumi, pitch) disegnato come quelli dei mixer: fessura sottile, tacche della scala ai lati,
 * cappuccio scuro zigrinato con la linea bianca. `larghezza` è la zona da toccare (più larga del disegno).
 * `centrale` segna lo zero al centro (pitch). `valore` 0…1 (1 in alto).
 */
@Composable
fun FaderVerticale(
    valore: Float,
    onCambia: (Float) -> Unit,
    modifier: Modifier = Modifier,
    centrale: Boolean = false,
    larghezza: Dp = 26.dp,
    cappuccio: Dp = 20.dp,
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
                            v = (v - dy / (size.height - kAltezzaCapV.toPx())).coerceIn(0f, 1f)
                            cambia(v)
                            c.consume()
                        }
                    } while (c.pressed)
                }
            }
            .pointerInput(centrale) { detectTapGestures(onDoubleTap = { if (centrale) cambia(0.5f) }) },
    ) {
        val hCap = kAltezzaCapV.toPx()
        val cx = size.width / 2
        val alto = hCap / 2
        val basso = size.height - hCap / 2
        // tacche: 11 (lunghe a fondo corsa e a metà), ai due lati della fessura
        val dentro = 4.dp.toPx()
        for (i in 0..10) {
            val ty = alto + (basso - alto) * i / 10f
            val lunga = i == 0 || i == 5 || i == 10
            val l = if (lunga) 5.dp.toPx() else 3.dp.toPx()
            val col = if (centrale && i == 5) Colori.Spento else if (lunga) Colori.Scuro else Color(0xFF3A4152)
            val sp = if (centrale && i == 5) 1.5.dp.toPx() else 1.dp.toPx()
            drawLine(col, Offset(cx - dentro - l, ty), Offset(cx - dentro, ty), sp)
            drawLine(col, Offset(cx + dentro, ty), Offset(cx + dentro + l, ty), sp)
        }
        fessura(Offset(cx, alto - 3.dp.toPx()), Offset(cx, basso + 3.dp.toPx()))
        val y = alto + (1f - valore.coerceIn(0f, 1f)) * (basso - alto)
        cappuccioFader(Offset(cx, y), Size(cappuccio.toPx().coerceAtMost(size.width), hCap), verticale = true)
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
                val margine = kLarghezzaCapX.toPx() / 2
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
        val wCap = kLarghezzaCapX.toPx()
        val cy = size.height / 2
        val sx = wCap / 2
        val dx = size.width - wCap / 2
        // tacche sopra e sotto: estremi, quarti e centro
        val dentro = 4.dp.toPx()
        for (i in 0..8) {
            val tx = sx + (dx - sx) * i / 8f
            val lunga = i % 4 == 0
            val l = if (lunga) 4.dp.toPx() else 2.5.dp.toPx()
            val col = if (lunga) Colori.Scuro else Color(0xFF3A4152)
            drawLine(col, Offset(tx, cy - dentro - l), Offset(tx, cy - dentro), 1.dp.toPx())
            drawLine(col, Offset(tx, cy + dentro), Offset(tx, cy + dentro + l), 1.dp.toPx())
        }
        fessura(Offset(sx - 3.dp.toPx(), cy), Offset(dx + 3.dp.toPx(), cy))
        val x = sx + valore.coerceIn(0f, 1f) * (dx - sx)
        cappuccioFader(Offset(x, cy), Size(wCap, size.height.coerceAtMost(26.dp.toPx())), verticale = false)
    }
}

/** Altezza del cappuccio dei fader verticali e larghezza di quello del crossfader (nel senso della corsa). */
private val kAltezzaCapV = 30.dp
private val kLarghezzaCapX = 16.dp

/** La fessura nera in cui scorre il cappuccio. */
private fun DrawScope.fessura(da: Offset, a: Offset) {
    drawLine(Color(0xFF2A3040), da, a, 5.dp.toPx(), StrokeCap.Round)
    drawLine(Color(0xFF030406), da, a, 3.dp.toPx(), StrokeCap.Round)
}

/**
 * Cappuccio del fader visto dall'alto: plastica scura con riflesso, zigrinatura e la linea bianca di riferimento
 * (orizzontale nei fader verticali, verticale nel crossfader).
 */
private fun DrawScope.cappuccioFader(centro: Offset, dim: Size, verticale: Boolean) {
    val tl = Offset(centro.x - dim.width / 2, centro.y - dim.height / 2)
    val arrot = CornerRadius(3.dp.toPx())
    // ombra
    drawRoundRect(Color.Black.copy(alpha = 0.55f), tl + Offset(0f, 2.dp.toPx()), dim, arrot)
    // corpo: più chiaro al centro (la sella) e più scuro verso i bordi, nel senso della corsa
    val chiaro = Color(0xFF4B5265)
    val scuro = Color(0xFF1C2029)
    val sfumatura = if (verticale) {
        Brush.verticalGradient(0f to Color(0xFF5A6176), 0.18f to scuro, 0.5f to chiaro, 0.82f to scuro, 1f to Color(0xFF14171E), startY = tl.y, endY = tl.y + dim.height)
    } else {
        Brush.horizontalGradient(0f to Color(0xFF5A6176), 0.18f to scuro, 0.5f to chiaro, 0.82f to scuro, 1f to Color(0xFF14171E), startX = tl.x, endX = tl.x + dim.width)
    }
    drawRoundRect(sfumatura, tl, dim, arrot)
    drawRoundRect(Color(0xFF6A7287).copy(alpha = 0.6f), tl, dim, arrot, style = Stroke(0.8.dp.toPx()))
    // zigrinatura: righe sottili perpendicolari alla corsa, lasciando libero il centro
    val passo = 2.5.dp.toPx()
    val bordo = 3.dp.toPx()
    val lunghezzaCorsa = if (verticale) dim.height else dim.width
    var t = bordo
    while (t < lunghezzaCorsa - bordo) {
        if (kotlin.math.abs(t - lunghezzaCorsa / 2) > 4.dp.toPx()) {
            if (verticale) {
                drawLine(Color.Black.copy(alpha = 0.45f), Offset(tl.x + bordo, tl.y + t), Offset(tl.x + dim.width - bordo, tl.y + t), 0.8.dp.toPx())
            } else {
                drawLine(Color.Black.copy(alpha = 0.45f), Offset(tl.x + t, tl.y + bordo), Offset(tl.x + t, tl.y + dim.height - bordo), 0.8.dp.toPx())
            }
        }
        t += passo
    }
    // linea bianca di riferimento
    if (verticale) {
        drawLine(Color.White, Offset(tl.x + 1.5.dp.toPx(), centro.y), Offset(tl.x + dim.width - 1.5.dp.toPx(), centro.y), 2.dp.toPx())
    } else {
        drawLine(Color.White, Offset(centro.x, tl.y + 1.5.dp.toPx()), Offset(centro.x, tl.y + dim.height - 1.5.dp.toPx()), 2.dp.toPx())
    }
}

/** Cursore sottile delle opzioni: linea di 3 dp e pomello tondo; si tocca o si trascina in qualsiasi punto. `valore` 0…1. */
@Composable
fun CursoreSottile(valore: Float, onCambia: (Float) -> Unit, modifier: Modifier = Modifier) {
    val cambia by rememberUpdatedState(onCambia)
    Canvas(
        modifier.height(24.dp).pointerInput(Unit) {
            awaitEachGesture {
                val giu = awaitFirstDown()
                val margine = 7.dp.toPx()
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
        val margine = 7.dp.toPx()
        val cy = size.height / 2
        val x = margine + valore.coerceIn(0f, 1f) * (size.width - 2 * margine)
        drawLine(Colori.Linea, Offset(margine, cy), Offset(size.width - margine, cy), 3.dp.toPx(), StrokeCap.Round)
        drawLine(Colori.Neutro, Offset(margine, cy), Offset(x, cy), 3.dp.toPx(), StrokeCap.Round)
        drawCircle(Color.Black.copy(alpha = 0.5f), 7.dp.toPx(), Offset(x, cy + 1.dp.toPx()))
        drawCircle(Color.White, 6.5.dp.toPx(), Offset(x, cy))
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
