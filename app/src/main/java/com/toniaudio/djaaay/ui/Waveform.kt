package com.toniaudio.djaaay.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.dp
import com.toniaudio.djaaay.LUNGHEZZE_LOOP
import com.toniaudio.djaaay.StatoDeck
import kotlin.math.ceil
import kotlin.math.max

/** Col brano in play, quanto tenere il dito sulla panoramica prima del salto. */
private const val kAttesaSalto = 2000L

/**
 * Waveform di un deck che scorre sotto la puntina (al centro, disegnata da chi la contiene). La waveform non comanda
 * il brano (lo scratch si fa solo sul disco): due dita = zoom. Sotto, la panoramica di tutto il brano: un tocco porta
 * il deck in quel punto (inizio, metà…), trascinando il brano segue il dito. Col brano in play il dito va tenuto
 * fermo 2 secondi prima del salto (un tocco per sbaglio durante il set non fa niente): intanto si vede l'attesa.
 */
@Composable
fun WaveformDeck(
    deck: StatoDeck,
    zoomSecondi: Float,
    onZoom: (Float) -> Unit,
    onVai: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val zoom by rememberUpdatedState(zoomSecondi)
    val cambiaZoom by rememberUpdatedState(onZoom)
    val vai by rememberUpdatedState(onVai)
    val durata by rememberUpdatedState(if (deck.brano != null) deck.durata else 0.0)
    val inPlay by rememberUpdatedState(deck.inPlay)
    // attesa prima del salto (in play): dove è il dito (0…1 della larghezza) e quanto manca (0…1); null = niente
    var attesa by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    val telefono = LocalMisure.current.telefono
    val altaPanoramica = if (telefono) 14.dp else 16.dp

    Canvas(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                val giu = awaitFirstDown(requireUnconsumed = false)
                // la zona da toccare è un po' più alta della striscia disegnata: si prende bene col dito
                val zonaPanoramica = size.height - (altaPanoramica + 6.dp).toPx()
                val salta = giu.position.y >= zonaPanoramica && durata > 0.0
                fun puntoDi(x: Float) = (x / size.width).coerceIn(0f, 1f) * durata
                var attivo = salta  // dito sulla panoramica e salto concesso
                if (salta && inPlay) {
                    // in play: il dito deve restare giù 2 s (può spostarsi: si salta dove si trova alla fine)
                    giu.consume()
                    val inizio = SystemClock.uptimeMillis()
                    var x = giu.position.x
                    var dentro = true
                    try {
                        while (true) {
                            val trascorsi = SystemClock.uptimeMillis() - inizio
                            attesa = (x / size.width).coerceIn(0f, 1f) to (trascorsi / kAttesaSalto.toFloat()).coerceIn(0f, 1f)
                            if (trascorsi >= kAttesaSalto) {
                                vai(puntoDi(x))
                                break
                            }
                            // un'occhiata al dito ogni fotogramma (anche se è fermo l'attesa deve avanzare)
                            val e = withTimeoutOrNull(16L) { awaitPointerEvent() } ?: continue
                            val p = e.changes.firstOrNull { it.id == giu.id }
                            e.changes.forEach { it.consume() }
                            if (p == null || !p.pressed) {
                                dentro = false  // alzato prima dei 2 s: niente salto
                                break
                            }
                            x = p.position.x
                        }
                    } finally {
                        attesa = null
                    }
                    attivo = dentro
                    if (!dentro) return@awaitEachGesture
                } else if (salta) {
                    vai(puntoDi(giu.position.x))
                    giu.consume()
                }
                do {
                    val e = awaitPointerEvent()
                    if (attivo) {
                        val p = e.changes.firstOrNull { it.id == giu.id }
                        if (p != null && p.pressed && p.positionChanged()) vai(puntoDi(p.position.x))
                    } else if (e.changes.count { it.pressed } >= 2) {
                        val z = e.calculateZoom()
                        if (z != 1f) cambiaZoom((zoom / z).coerceIn(1.5f, 60f))
                    }
                    e.changes.forEach { it.consume() }
                } while (e.changes.any { it.pressed })
            }
        },
    ) {
        val panoramica = altaPanoramica.toPx()
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
        attesa?.let { (x, p) ->
            // attesa del salto: segno dove andrà il brano e una barretta che si riempie in 2 s
            val px = x * size.width
            drawRect(Color.White.copy(alpha = 0.5f + 0.5f * p), Offset(px - 1.dp.toPx(), altezza), Size(2.dp.toPx(), panoramica))
            val largo = 44.dp.toPx()
            val sx = (px - largo / 2).coerceIn(0f, size.width - largo)
            val sy = altezza - 5.dp.toPx()
            drawRect(Color.Black.copy(alpha = 0.6f), Offset(sx, sy), Size(largo, 3.dp.toPx()))
            drawRect(Color.White, Offset(sx, sy), Size(largo * p, 3.dp.toPx()))
        }
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
        // si disegna la griglia vera (fissa o variabile): una riga per battuta, più marcata ogni 4
        val pxBattito = deck.battitoSecondi * pxAlSecondo
        if (pxBattito > 4) {  // con zoom molto lontano la griglia sarebbe solo rumore
            var k = ceil(deck.battutaA(inizioVisibile)).toInt()
            while (true) {
                val t = deck.tempoDellaBattuta(k.toDouble())
                if (t > fineVisibile) break
                val forte = Math.floorMod(k, 4) == 0
                drawLine(Color.White.copy(alpha = if (forte) 0.45f else 0.19f), Offset(xDi(t), 0f), Offset(xDi(t), altezza), 1.dp.toPx())
                k++
            }
        }
    }
    if (deck.loopAttivo) {
        val a = xDi(deck.loopInizio)
        val b = xDi(if (deck.bpm > 0) deck.tempoDellaBattuta(deck.loopBattuta + LUNGHEZZE_LOOP[deck.loopIndice])
                    else deck.loopInizio + LUNGHEZZE_LOOP[deck.loopIndice] * 0.5)
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
