package com.toniaudio.djaaay.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.toniaudio.djaaay.AzioneMidi
import com.toniaudio.djaaay.Controllore
import com.toniaudio.djaaay.AZIONI_PAD_FX
import com.toniaudio.djaaay.EFFETTI
import com.toniaudio.djaaay.StatoApp

/** Scheda FX del mixer: un'unità per deck (FX · DECK A a sinistra, FX · DECK B a destra). */
@Composable
fun PannelloFx(app: StatoApp, c: Controllore, modifier: Modifier = Modifier, grande: Boolean = false) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(if (grande) 14.dp else 6.dp)) {
        for (d in 0..1) UnitaFx(app, d, c, Modifier.weight(1f).fillMaxHeight(), grande)
    }
}

/**
 * Unità effetti di un deck: i quattro effetti sempre presenti, ognuno col suo trackpad (si possono usare insieme,
 * un dito per effetto): dito giù = suona, su = svanisce, secondo dito sullo stesso trackpad = HOLD. Sopra ogni
 * trackpad il nome (pieno = suona, punto verde = HOLD; tocco = mostra le sue regolazioni nella riga in basso).
 */
@Composable
private fun UnitaFx(app: StatoApp, d: Int, c: Controllore, modifier: Modifier, grande: Boolean) {
    val fx = app.deck[d].fx
    val t = fx.tipo
    val descr = EFFETTI[t]
    val forma = RoundedCornerShape(if (grande) 14.dp else 9.dp)
    val alto = if (grande) 26.dp else 15.dp
    val spazio = if (grande) 6.dp else 3.dp
    Column(
        modifier
            .background(if (grande) Colori.Pannello else Color.Transparent, forma)
            .border(1.dp, Colori.Linea, forma)
            .padding(horizontal = if (grande) 10.dp else 5.dp, vertical = if (grande) 6.dp else 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (grande) 5.dp else 3.dp),
    ) {
        Etichetta(
            if (d == 1 && app.opzioni.unDeck) "FX · CAMPIONATORE" else "FX · DECK ${app.deck[d].lettera}",
            Colori.Testo, if (grande) 12.sp else 8.5.sp, Modifier.fillMaxWidth(),
        )
        // telefono: 4 in fila; tablet (colonna stretta): 2 × 2
        val righe = if (grande) listOf(listOf(0, 1, 2, 3)) else listOf(listOf(0, 1), listOf(2, 3))
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spazio)) {
            for (riga in righe) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spazio)) {
                    for (i in riga) {
                        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(spazio)) {
                            NomeFx(fx, i, i == t, alto, grande) { c.fxTipo(d, i) }
                            Assegnabile(app, c, AZIONI_PAD_FX[i], d, Modifier.weight(1f).fillMaxWidth()) {
                                TrackpadFx(app, d, i, c, Modifier.fillMaxSize(), grande)
                            }
                        }
                    }
                }
            }
        }
        Etichetta("${descr.nome} · ${descr.titoloRiga}", Colori.Spento, if (grande) 10.sp else 6.5.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (grande) 6.dp else 2.dp)) {
            descr.voci.forEachIndexed { k, voce ->
                val scelta = descr.indici[k] == fx.scelta[t]
                Tasto(Modifier.weight(1f).height(alto), acceso = scelta, onTocco = { c.fxScelta(d, k) }) {
                    Text(
                        voce, color = if (scelta) Colori.Sfondo else Colori.Spento, fontSize = if (grande) 12.sp else 7.5.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Clip,
                    )
                }
            }
        }
    }
}

/** Nome dell'effetto sopra il suo trackpad: pieno = suona, bordato = le sue regolazioni sono nella riga, punto verde = HOLD. */
@Composable
private fun NomeFx(fx: com.toniaudio.djaaay.StatoFx, i: Int, scelto: Boolean, alto: androidx.compose.ui.unit.Dp, grande: Boolean, onTocco: () -> Unit) {
    val suona = fx.accesi[i]
    Tasto(
        Modifier.fillMaxWidth().height(alto),
        acceso = suona,
        bordo = if (scelto && !suona) Colori.Neutro else null,
        onTocco = onTocco,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Etichetta(
                if (grande) EFFETTI[i].nome else EFFETTI[i].corto,
                if (suona) Colori.Sfondo else if (scelto) Colori.Testo else Colori.Spento,
                if (grande) 11.sp else 7.sp,
            )
            if (fx.tenuti[i]) {
                Canvas(Modifier.align(Alignment.CenterEnd).padding(end = if (grande) 6.dp else 3.dp).size(if (grande) 7.dp else 4.dp)) {
                    drawCircle(Colori.Verde)
                }
            }
        }
    }
}

/**
 * Trackpad dell'effetto (senza scritte): X = dry/wet, Y = il parametro principale. Un dito appoggiato suona l'effetto finché resta
 * giù (staccandolo svanisce con la sua coda); un secondo dito lo blocca (HOLD); il tocco dopo lo fa svanire.
 */
@Composable
private fun TrackpadFx(app: StatoApp, d: Int, t: Int, c: Controllore, modifier: Modifier, grande: Boolean) {
    val fx = app.deck[d].fx
    val descr = EFFETTI[t]
    val x = fx.dryWet[t]
    val y = if (descr.asseY == 1) fx.param1[t] else fx.param2[t]
    val ctrl by rememberUpdatedState(c)
    val forma = RoundedCornerShape(if (grande) 10.dp else 7.dp)
    Box(
        modifier
            .clip(forma)
            .background(Color(0xFF0E1118))
            .border(1.dp, if (fx.toccati[t]) Colori.Neutro else Colori.Linea, forma)
            .pointerInput(d, t) {
                awaitEachGesture {
                    val primo = awaitFirstDown(requireUnconsumed = false)
                    fun ax(o: Offset) = (o.x / size.width).coerceIn(0f, 1f)
                    fun ay(o: Offset) = (1f - o.y / size.height).coerceIn(0f, 1f)
                    ctrl.fxTrackpadGiu(d, t, ax(primo.position), ay(primo.position))
                    primo.consume()
                    var dito = primo.id
                    try {
                        while (true) {
                            val e = awaitPointerEvent()
                            // un dito in più = HOLD
                            for (ch in e.changes) if (ch.id != dito && ch.changedToDown()) {
                                ctrl.fxTrackpadTieni(d, t)
                                ch.consume()
                            }
                            val p = e.changes.firstOrNull { it.id == dito }
                            if (p != null && p.pressed) {
                                if (p.positionChanged()) {
                                    ctrl.fxTrackpadSposta(d, t, ax(p.position), ay(p.position))
                                    p.consume()
                                }
                            } else {
                                // il primo dito si è alzato: se ce n'è un altro, comanda lui
                                dito = e.changes.firstOrNull { it.pressed }?.id ?: break
                            }
                        }
                    } finally {
                        ctrl.fxTrackpadSu(d, t)  // anche se il gesto viene interrotto
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val griglia = Colori.Linea.copy(alpha = 0.6f)
            for (k in 1..3) drawLine(griglia, Offset(size.width * k / 4, 0f), Offset(size.width * k / 4, size.height), 1f)
            for (k in 1..3) {
                // ROLL: la riga a metà (tono normale) è più marcata
                val col = if (t == 3 && k == 2) Colori.Spento.copy(alpha = 0.7f) else griglia
                drawLine(col, Offset(0f, size.height * k / 4), Offset(size.width, size.height * k / 4), if (t == 3 && k == 2) 1.5f else 1f)
            }
            val px = x * size.width
            val py = (1f - y) * size.height
            val colore = if (fx.accesi[t]) Colori.Testo else Colori.Scuro
            drawLine(colore.copy(alpha = 0.35f), Offset(px, 0f), Offset(px, size.height), 1.dp.toPx())
            drawLine(colore.copy(alpha = 0.35f), Offset(0f, py), Offset(size.width, py), 1.dp.toPx())
            if (fx.toccati[t]) drawCircle(Colori.Neutro.copy(alpha = 0.18f), (if (grande) 30.dp else 18.dp).toPx(), Offset(px, py))
            drawCircle(colore, (if (grande) 9.dp else 5.dp).toPx(), Offset(px, py))
            drawCircle(colore, (if (grande) 15.dp else 9.dp).toPx(), Offset(px, py), style = Stroke(1.5.dp.toPx()))
        }
        if (fx.tenuti[t]) {
            Text(
                "HOLD", color = Colori.Sfondo, fontSize = if (grande) 10.sp else 7.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).background(Colori.Verde, RoundedCornerShape(5.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}
