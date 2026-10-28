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
import com.toniaudio.djaaay.AZIONI_HOT_CUE
import com.toniaudio.djaaay.AzioneMidi
import com.toniaudio.djaaay.Controllore
import com.toniaudio.djaaay.LUNGHEZZE_LOOP
import com.toniaudio.djaaay.StatoApp
import com.toniaudio.djaaay.etichettaLoop

/** Un deck: disco, trasporto, pad e pitch. Il deck B è lo specchio del deck A. La libreria si apre qui dentro. */
@Composable
fun PannelloDeck(app: StatoApp, d: Int, c: Controllore, modifier: Modifier = Modifier) {
    val deck = app.deck[d]
    val m = LocalMisure.current
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
                    Assegnabile(app, c, AzioneMidi.KEY, d, Modifier.align(if (d == 0) Alignment.TopStart else Alignment.TopEnd)) {
                        TastoKey(acceso = deck.keylock, onTocco = { c.keylock(d) })
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Assegnabile(app, c, AzioneMidi.CUE, d, Modifier.weight(1f).height(m.tasto)) {
                        Tasto(Modifier.fillMaxSize(), onPremi = { c.cueGiu(d) }, onRilascia = { c.cueSu(d) }) {
                            Etichetta("CUE", Colori.Testo, m.testoTasto)
                        }
                    }
                    Assegnabile(app, c, AzioneMidi.PLAY, d, Modifier.weight(1f).height(m.tasto)) {
                        Tasto(Modifier.fillMaxSize(), acceso = deck.inPlay, onTocco = { c.playPausa(d) }) {
                            SimboloPlay(deck.inPlay, if (m.telefono) 16.dp else 12.dp)
                        }
                    }
                    Assegnabile(app, c, AzioneMidi.SYNC, d, Modifier.weight(1f).height(m.tasto)) {
                        Tasto(Modifier.fillMaxSize(), onTocco = { c.sync(d) }) {
                            Etichetta("SYNC", Colori.Testo, m.testoTasto)
                        }
                    }
                }
                Schede(listOf("HOT CUE", "LOOP"), deck.tabPad, { deck.tabPad = it }, Modifier.fillMaxWidth(), m.schede)
                Pad(app, d, c)
            }
            if (d == 0) ColonnaPitch(app, d, c)
        }
        if (deck.libreriaAperta) LibreriaDeck(app, d, c, Modifier.fillMaxSize())
    }
}

@Composable
private fun SimboloPlay(inPlay: Boolean, lato: androidx.compose.ui.unit.Dp) {
    Canvas(Modifier.size(lato)) {
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
    val m = LocalMisure.current
    val scala = if (m.telefono) 1.3f else 1f
    Row(
        modifier.pointerInput(Unit) { detectTapGestures { onTocco() } }.padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(width = 9.dp * scala, height = 11.dp * scala)) {
            val w = size.width
            val h = size.height
            drawArc(colore, 180f, 180f, false, Offset(w * 0.18f, 0f), androidx.compose.ui.geometry.Size(w * 0.64f, h * 0.7f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(1.6.dp.toPx()))
            drawRect(colore, Offset(0f, h * 0.42f), androidx.compose.ui.geometry.Size(w, h * 0.58f))
        }
        Spacer(Modifier.width(3.dp))
        Etichetta("KEY", colore, m.key)
    }
}

@Composable
private fun Pad(app: StatoApp, d: Int, c: Controllore) {
    val deck = app.deck[d]
    val m = LocalMisure.current
    val altezza = Modifier.height(m.pad)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        when (deck.tabPad) {
            0 -> for (i in 0 until 4) {
                val punto = deck.hotCue[i]
                Assegnabile(app, c, AZIONI_HOT_CUE[i], d, altezza.weight(1f)) {
                    Tasto(Modifier.fillMaxSize(), onTocco = { c.hotCue(d, i) }, onTieni = { c.cancellaHotCue(d, i) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (punto != null) {
                                Canvas(Modifier.size(5.dp)) { drawCircle(Colori.HotCue[i]) }
                                Spacer(Modifier.width(4.dp))
                            }
                            Etichetta("${i + 1}", if (punto != null) Colori.Testo else Colori.Spento, m.testoPad)
                        }
                    }
                }
            }
            else -> {
                Assegnabile(app, c, AzioneMidi.LOOP_MEZZO, d, altezza.weight(1f)) {
                    Tasto(Modifier.fillMaxSize(), onTocco = { c.loopLunghezza(d, -1) }) { Freccia(sinistra = true) }
                }
                Assegnabile(app, c, AzioneMidi.LOOP, d, altezza.weight(2f)) {
                    Tasto(
                        Modifier.fillMaxSize(),
                        bordo = if (deck.loopAttivo) Colori.Verde else null,
                        onTocco = { c.loopTocco(d) },
                    ) {
                        Text(
                            etichettaLoop(LUNGHEZZE_LOOP[deck.loopIndice]),
                            color = if (deck.loopAttivo) Colori.Verde else Colori.Testo,
                            fontSize = if (m.telefono) 18.sp else 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.ExtraBold,
                        )
                    }
                }
                Assegnabile(app, c, AzioneMidi.LOOP_DOPPIO, d, altezza.weight(1f)) {
                    Tasto(Modifier.fillMaxSize(), onTocco = { c.loopLunghezza(d, +1) }) { Freccia(sinistra = false) }
                }
            }
        }
    }
}

@Composable
internal fun Freccia(sinistra: Boolean, lato: androidx.compose.ui.unit.Dp = if (LocalMisure.current.telefono) 14.dp else 10.dp) {
    Canvas(Modifier.size(lato)) {
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
    val m = LocalMisure.current
    val escursione = app.opzioni.escursione / 100f
    Column(
        Modifier.width(m.pitch).fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Etichetta("PITCH", Colori.Spento, if (m.telefono) 10.5.sp else 8.5.sp)
        // in alto = più veloce
        Assegnabile(app, c, AzioneMidi.PITCH, d, Modifier.weight(1f)) {
            FaderVerticale(
                valore = 0.5f + deck.pitch / (2 * escursione),
                onCambia = { c.impostaPitch(d, (it - 0.5f) * 2 * escursione) },
                centrale = true,
                larghezza = m.faderPitch,
                cappuccio = m.capPitch,
                modifier = Modifier.fillMaxHeight(),
            )
        }
        Numero("%+.1f%%".format(deck.pitch * 100), dimensione = m.testoPitch)
        // DVS acceso: il deck segue il vinile timecode
        Assegnabile(app, c, AzioneMidi.DVS, d) {
            Tasto(Modifier.width(m.dvsL).height(m.dvsH), acceso = deck.dvs, onTocco = { c.dvs(d) }) {
                val colore = if (deck.dvs) Colori.Sfondo else Colori.Spento
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(if (m.telefono) 11.dp else 9.dp)) {
                        drawCircle(colore, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                        drawCircle(colore, size.minDimension * 0.18f)
                    }
                    Spacer(Modifier.width(3.dp))
                    Etichetta("DVS", colore, m.testoDvs)
                }
            }
        }
    }
}
