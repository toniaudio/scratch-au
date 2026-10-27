package com.toniaudio.djaaay.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.toniaudio.djaaay.Controllore
import com.toniaudio.djaaay.LUNGHEZZE_LOOP
import com.toniaudio.djaaay.StatoApp
import com.toniaudio.djaaay.etichettaLoop

/** Un deck: disco, trasporto, pad e pitch. Il deck B è lo specchio del deck A. La libreria si apre qui dentro. */
@Composable
fun PannelloDeck(app: StatoApp, d: Int, c: Controllore, modifier: Modifier = Modifier) {
    val deck = app.deck[d]
    Box(modifier.fillMaxHeight().padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (d == 1) ColonnaPitch(app, d, c)
            Column(
                Modifier.weight(1f).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Piatto(
                        deck, app.opzioni.sensibilita,
                        onToccoInizio = { c.toccoInizio(d) },
                        onToccoSposta = { c.toccoSposta(d, it) },
                        onToccoFine = { c.toccoFine(d) },
                        modifier = Modifier.fillMaxHeight(),
                    )
                    TastoKey(
                        acceso = deck.keylock,
                        onTocco = { c.keylock(d) },
                        modifier = Modifier.align(if (d == 0) Alignment.TopStart else Alignment.TopEnd),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Tasto(Modifier.weight(1f).height(26.dp), onPremi = { c.cueGiu(d) }, onRilascia = { c.cueSu(d) }) {
                        Etichetta("CUE", Colori.Testo, 10.5.sp)
                    }
                    Tasto(Modifier.weight(1f).height(26.dp), acceso = deck.inPlay, onTocco = { c.playPausa(d) }) {
                        SimboloPlay(deck.inPlay)
                    }
                    Tasto(Modifier.weight(1f).height(26.dp), onTocco = { c.sync(d) }) {
                        Etichetta("SYNC", Colori.Testo, 10.5.sp)
                    }
                }
                Schede(listOf("HOT CUE", "LOOP", "FX"), deck.tabPad, { deck.tabPad = it }, Modifier.fillMaxWidth())
                Pad(app, d, c)
            }
            if (d == 0) ColonnaPitch(app, d, c)
        }
        if (deck.libreriaAperta) LibreriaDeck(app, d, c, Modifier.fillMaxSize())
    }
}

@Composable
private fun SimboloPlay(inPlay: Boolean) {
    Canvas(Modifier.size(12.dp)) {
        val colore = if (inPlay) Colori.Sfondo else Colori.Testo
        if (inPlay) {
            drawRect(colore, Offset(size.width * 0.15f, 0f), androidx.compose.ui.geometry.Size(size.width * 0.25f, size.height))
            drawRect(colore, Offset(size.width * 0.6f, 0f), androidx.compose.ui.geometry.Size(size.width * 0.25f, size.height))
        } else {
            drawPath(Path().apply { moveTo(size.width * 0.15f, 0f); lineTo(size.width, size.height / 2); lineTo(size.width * 0.15f, size.height); close() }, colore)
        }
    }
}

/** KEY (keylock): senza riquadro, lucchetto e scritta grigi da spento, verdi da acceso. */
@Composable
private fun TastoKey(acceso: Boolean, onTocco: () -> Unit, modifier: Modifier = Modifier) {
    val colore = if (acceso) Colori.Verde else Colori.Scuro
    Row(
        modifier.pointerInput(Unit) { detectTapGestures { onTocco() } }.padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(width = 9.dp, height = 11.dp)) {
            val w = size.width
            val h = size.height
            drawArc(colore, 180f, 180f, false, Offset(w * 0.18f, 0f), androidx.compose.ui.geometry.Size(w * 0.64f, h * 0.7f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(1.6.dp.toPx()))
            drawRect(colore, Offset(0f, h * 0.42f), androidx.compose.ui.geometry.Size(w, h * 0.58f))
        }
        Spacer(Modifier.width(3.dp))
        Etichetta("KEY", colore, 9.5.sp)
    }
}

@Composable
private fun Pad(app: StatoApp, d: Int, c: Controllore) {
    val deck = app.deck[d]
    val altezza = Modifier.height(24.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        when (deck.tabPad) {
            0 -> for (i in 0 until 4) {
                val punto = deck.hotCue[i]
                Tasto(altezza.weight(1f), onTocco = { c.hotCue(d, i) }, onTieni = { c.cancellaHotCue(d, i) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (punto != null) {
                            Canvas(Modifier.size(5.dp)) { drawCircle(Colori.HotCue[i]) }
                            Spacer(Modifier.width(4.dp))
                        }
                        Etichetta("${i + 1}", if (punto != null) Colori.Testo else Colori.Spento, 9.5.sp)
                    }
                }
            }
            1 -> {
                Tasto(altezza.weight(1f), onTocco = { c.loopLunghezza(d, -1) }) { Freccia(sinistra = true) }
                Tasto(
                    altezza.weight(2f),
                    bordo = if (deck.loopAttivo) Colori.Verde else null,
                    onTocco = { c.loopTocco(d) },
                ) {
                    Text(
                        etichettaLoop(LUNGHEZZE_LOOP[deck.loopIndice]),
                        color = if (deck.loopAttivo) Colori.Verde else Colori.Testo,
                        fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.ExtraBold,
                    )
                }
                Tasto(altezza.weight(1f), onTocco = { c.loopLunghezza(d, +1) }) { Freccia(sinistra = false) }
            }
            else -> for (nome in listOf("ECHO", "DUB", "VERB", "STRETCH")) {
                Tasto(altezza.weight(1f), onTocco = { c.effetto(nome) }) { Etichetta(nome, Colori.Scuro, 8.sp) }
            }
        }
    }
}

@Composable
private fun Freccia(sinistra: Boolean) {
    Canvas(Modifier.size(10.dp)) {
        val p = Path()
        if (sinistra) { p.moveTo(size.width * 0.75f, 0f); p.lineTo(size.width * 0.15f, size.height / 2); p.lineTo(size.width * 0.75f, size.height) }
        else { p.moveTo(size.width * 0.25f, 0f); p.lineTo(size.width * 0.85f, size.height / 2); p.lineTo(size.width * 0.25f, size.height) }
        p.close()
        drawPath(p, Colori.Testo)
    }
}

/** PITCH: fader con lo zero al centro, valore in %, e sotto il tasto DVS. */
@Composable
private fun ColonnaPitch(app: StatoApp, d: Int, c: Controllore) {
    val deck = app.deck[d]
    val escursione = app.opzioni.escursione / 100f
    Column(
        Modifier.width(52.dp).fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Etichetta("PITCH", Colori.Spento, 8.5.sp)
        // in alto = più veloce
        FaderVerticale(
            valore = 0.5f + deck.pitch / (2 * escursione),
            onCambia = { c.impostaPitch(d, (it - 0.5f) * 2 * escursione) },
            centrale = true,
            larghezza = 30.dp,
            modifier = Modifier.weight(1f),
        )
        Numero("%+.1f%%".format(deck.pitch * 100), dimensione = 10.sp)
        Tasto(Modifier.width(44.dp).height(22.dp), onTocco = { c.dvs(d) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(9.dp)) {
                    drawCircle(Colori.Spento, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                    drawCircle(Colori.Spento, size.minDimension * 0.18f)
                }
                Spacer(Modifier.width(3.dp))
                Etichetta("DVS", Colori.Spento, 8.sp)
            }
        }
    }
}
