package com.toniaudio.djaaay.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.toniaudio.djaaay.LUNGHEZZE_LOOP
import com.toniaudio.djaaay.StatoDeck
import kotlin.math.ceil
import kotlin.math.max

/**
 * Waveform di un deck che scorre sotto la puntina (al centro, disegnata da chi la contiene).
 * Un dito trascina il brano (scratch, se attivo nelle opzioni); due dita = zoom.
 */
@Composable
fun WaveformDeck(
    deck: StatoDeck,
    zoomSecondi: Float,
    scratchAttivo: Boolean,
    onZoom: (Float) -> Unit,
    onToccoInizio: () -> Unit,
    onToccoSposta: (Double) -> Unit,
    onToccoFine: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val zoom by rememberUpdatedState(zoomSecondi)
    val scratch by rememberUpdatedState(scratchAttivo && deck.brano != null)
    val inizio by rememberUpdatedState(onToccoInizio)
    val sposta by rememberUpdatedState(onToccoSposta)
    val fine by rememberUpdatedState(onToccoFine)
    val cambiaZoom by rememberUpdatedState(onZoom)

    Canvas(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var scratchando = false
                var spostamento = 0.0
                if (scratch) {
                    inizio()
                    scratchando = true
                }
                do {
                    val e = awaitPointerEvent()
                    val premuti = e.changes.count { it.pressed }
                    if (premuti >= 2) {
                        if (scratchando) {
                            fine()
                            scratchando = false
                        }
                        val z = e.calculateZoom()
                        if (z != 1f) cambiaZoom((zoom / z).coerceIn(1.5f, 60f))
                    } else if (scratchando) {
                        val dx = e.calculatePan().x
                        if (dx != 0f) {
                            // trascinare verso sinistra porta avanti il brano
                            spostamento -= dx / (size.width / zoom)
                            sposta(spostamento)
                        }
                    }
                    e.changes.forEach { it.consume() }
                } while (e.changes.any { it.pressed })
                if (scratchando) fine()
            }
        },
    ) {
        val panoramica = 6.dp.toPx()
        val altezza = size.height - panoramica
        drawRect(Colori.Sfondo)
        val wf = deck.waveform
        val caricamento = deck.caricamento
        if (wf == null || wf.livelli.isEmpty()) {
            drawLine(Colori.Linea, Offset(0f, altezza / 2), Offset(size.width, altezza / 2), 1.dp.toPx())
            if (caricamento != null) {
                drawRect(Colori.deck(deck.indice).copy(alpha = 0.5f), Offset(0f, altezza / 2 - 1.5.dp.toPx()), Size(size.width * caricamento, 3.dp.toPx()))
            }
            return@Canvas
        }
        // mentre il brano si decodifica la waveform cresce (il brano si può già suonare)
        val pxAlSecondo = size.width / zoomSecondi
        disegnaOnda(deck, wf, pxAlSecondo, altezza)
        disegnaGriglia(deck, pxAlSecondo, altezza)
        // la parte già suonata è più scura
        drawRect(Colori.Sfondo.copy(alpha = 0.4f), size = Size(size.width / 2, altezza))
        disegnaPanoramica(deck, wf, altezza, panoramica)
        if (caricamento != null) {
            drawRect(Colori.deck(deck.indice).copy(alpha = 0.7f), Offset(0f, size.height - 1.5.dp.toPx()), Size(size.width * caricamento, 1.5.dp.toPx()))
        }
    }
}

private fun DrawScope.disegnaOnda(deck: StatoDeck, wf: com.toniaudio.djaaay.Waveform, pxAlSecondo: Float, altezza: Float) {
    val framePerPx = wf.frequenza / pxAlSecondo
    // il livello più dettagliato le cui colonne non sono più strette di un pixel
    var livello = 0
    while (livello + 1 < wf.livelli.size && wf.framePerColonna(livello + 1) <= framePerPx) livello++
    val dati = wf.livelli[livello]
    val colonne = wf.colonne(livello)
    val fpc = wf.framePerColonna(livello)
    val passo = 2f  // una barra ogni 2 pixel
    val centro = altezza / 2
    val bassi = Path()
    val medi = Path()
    val alti = Path()
    var x = 0f
    while (x < size.width) {
        val t = deck.posizione + (x - size.width / 2) / pxAlSecondo
        val col = (t * wf.frequenza / fpc).toInt()
        if (t >= 0 && col in 0 until colonne) {
            val b = (dati[col].toInt() and 0xFF) / 255f * centro * 0.95f
            val m = (dati[colonne + col].toInt() and 0xFF) / 255f * centro * 0.8f
            val a = (dati[2 * colonne + col].toInt() and 0xFF) / 255f * centro * 0.7f
            bassi.moveTo(x, centro - b); bassi.lineTo(x, centro + b)
            medi.moveTo(x, centro - m); medi.lineTo(x, centro + m)
            alti.moveTo(x, centro - a); alti.lineTo(x, centro + a)
        }
        x += passo
    }
    val tratto = Stroke(passo)
    drawPath(bassi, Colori.deckBassi(deck.indice), style = tratto)
    drawPath(medi, Colori.deck(deck.indice), alpha = 0.75f, style = tratto)
    drawPath(alti, Colori.Alti, style = tratto)
}

private fun DrawScope.disegnaGriglia(deck: StatoDeck, pxAlSecondo: Float, altezza: Float) {
    fun xDi(t: Double) = (size.width / 2 + (t - deck.posizione) * pxAlSecondo).toFloat()
    val inizioVisibile = deck.posizione - size.width / 2 / pxAlSecondo
    val fineVisibile = deck.posizione + size.width / 2 / pxAlSecondo
    if (deck.bpm > 0) {
        val battito = deck.battitoSecondi
        val pxBattito = battito * pxAlSecondo
        if (pxBattito > 4) {  // con zoom molto lontano la griglia sarebbe solo rumore
            var k = ceil((inizioVisibile - deck.primoBattito) / battito).toInt()
            while (true) {
                val t = deck.primoBattito + k * battito
                if (t > fineVisibile) break
                val forte = Math.floorMod(k, 4) == 0
                drawLine(Color.White.copy(alpha = if (forte) 0.45f else 0.19f), Offset(xDi(t), 0f), Offset(xDi(t), altezza), 1.dp.toPx())
                k++
            }
        }
    }
    if (deck.loopAttivo) {
        val a = xDi(deck.loopInizio)
        val b = xDi(deck.loopInizio + LUNGHEZZE_LOOP[deck.loopIndice] * deck.battitoSecondi)
        drawRect(Colori.Verde.copy(alpha = 0.16f), Offset(a, 0f), Size(max(0f, b - a), altezza))
        drawLine(Colori.Verde, Offset(a, 0f), Offset(a, altezza), 2.dp.toPx())
        drawLine(Colori.Verde, Offset(b, 0f), Offset(b, altezza), 2.dp.toPx())
    }
    deck.hotCue.forEachIndexed { i, punto ->
        if (punto != null) {
            val x = xDi(punto)
            val colore = Colori.HotCue[i]
            drawLine(colore, Offset(x, 0f), Offset(x, altezza), 2.dp.toPx())
            val bandiera = Path().apply {
                moveTo(x, 0f); lineTo(x + 9.dp.toPx(), 0f); lineTo(x + 2.dp.toPx(), 8.dp.toPx()); close()
            }
            drawPath(bandiera, colore)
        }
    }
}

private fun DrawScope.disegnaPanoramica(deck: StatoDeck, wf: com.toniaudio.djaaay.Waveform, y: Float, altezza: Float) {
    drawRect(Color.Black.copy(alpha = 0.6f), Offset(0f, y), Size(size.width, altezza))
    val ultimo = wf.livelli.size - 1
    val dati = wf.livelli[ultimo]
    val colonne = wf.colonne(ultimo)
    if (colonne == 0) return
    // colonne in proporzione alla durata: mentre si decodifica la parte che manca resta vuota
    val colonneTotali = if (deck.durata > 0) maxOf(colonne.toDouble(), deck.durata * wf.frequenza / wf.framePerColonna(ultimo)) else colonne.toDouble()
    var x = 0f
    while (x < size.width) {
        val col = (x / size.width * colonneTotali).toInt()
        if (col >= colonne) break
        val v = maxOf(dati[col].toInt() and 0xFF, dati[colonne + col].toInt() and 0xFF, dati[2 * colonne + col].toInt() and 0xFF) / 255f
        val h = v * altezza
        drawLine(Color.White.copy(alpha = 0.25f), Offset(x, y + (altezza - h) / 2), Offset(x, y + (altezza + h) / 2), 1.5f)
        x += 2f
    }
    if (deck.durata > 0) {
        val px = (deck.posizione / deck.durata).toFloat().coerceIn(0f, 1f) * size.width
        drawRect(Colori.deck(deck.indice), Offset(px - 1f, y), Size(2.dp.toPx(), altezza))
    }
}
