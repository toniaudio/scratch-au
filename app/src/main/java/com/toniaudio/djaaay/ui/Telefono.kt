package com.toniaudio.djaaay.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.toniaudio.djaaay.AZIONI_HOT_CUE
import com.toniaudio.djaaay.AzioneMidi
import com.toniaudio.djaaay.Controllore
import com.toniaudio.djaaay.LUNGHEZZE_LOOP
import com.toniaudio.djaaay.ParMixer
import com.toniaudio.djaaay.StatoApp
import com.toniaudio.djaaay.etichettaLoop

/**
 * Telefono (schermo basso): sotto le waveform una vista alla volta, con comandi grandi.
 * DECK = dischi, trasporto, pad, e al centro filtri, volumi e crossfader (quello che si usa di continuo);
 * MIXER = tutto il mixer; PAD = hot cue e loop grandi; FX = le due unità effetti con trackpad.
 */
@Composable
fun CorpoTelefono(app: StatoApp, c: Controllore, modifier: Modifier = Modifier) {
    if (app.vista == 0) {
        Row(modifier) {
            PannelloDeck(app, 0, c, Modifier.weight(1f))
            CentroDeck(app, c, Modifier.width(236.dp).fillMaxHeight())
            PannelloDeck(app, 1, c, Modifier.weight(1f))
        }
        return
    }
    Column(
        modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SelettoreViste(app)
        when (app.vista) {
            1 -> MixerTelefono(app, c, Modifier.weight(1f).fillMaxWidth())
            2 -> PadTelefono(app, c, Modifier.weight(1f).fillMaxWidth())
            else -> PannelloFx(app, c, Modifier.weight(1f).fillMaxWidth(), grande = true)
        }
    }
}

@Composable
private fun SelettoreViste(app: StatoApp) {
    Schede(listOf("DECK", "MIXER", "PAD", "FX"), app.vista, { app.vista = it }, Modifier.width(220.dp), 11.5.sp, altezza = 32.dp)
}

/** Vista DECK: al centro filtri, volumi con meter e crossfader. */
@Composable
private fun CentroDeck(app: StatoApp, c: Controllore, modifier: Modifier) {
    val m = app.mixer
    Column(
        modifier
            .background(Colori.Pannello)
            .border(1.dp, Colori.Linea)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        SelettoreViste(app)
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            for (canale in 0..1) {
                Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Assegnabile(app, c, AzioneMidi.FILTRO, canale) {
                        Manopola(m.canale[canale].filtro, { c.mixer(ParMixer.FILTRO, canale, it) }, tipo = TipoManopola.Filtro, diametro = 44.dp)
                    }
                    Etichetta("FILTRO", Colori.Spento, 9.5.sp)
                    Spacer(Modifier.height(3.dp))
                    VolumeConMeter(app, c, canale, Modifier.weight(1f))
                }
            }
        }
        RigaCrossfader(app, c, 40)
    }
}

/** Fader del volume del canale con il suo meter accanto (verso il centro). */
@Composable
private fun VolumeConMeter(app: StatoApp, c: Controllore, canale: Int, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val meter = @Composable { Meter(app.deck[canale].livello, Modifier.width(6.dp).fillMaxHeight()) }
        if (canale == 1) meter()
        Assegnabile(app, c, AzioneMidi.VOLUME, canale, Modifier.fillMaxHeight()) {
            FaderVerticale(
                app.mixer.canale[canale].volume, { c.mixer(ParMixer.VOLUME, canale, it) }, Modifier.fillMaxHeight(),
                larghezza = 44.dp, cappuccio = 34.dp,
            )
        }
        if (canale == 0) meter()
    }
}

@Composable
private fun RigaCrossfader(app: StatoApp, c: Controllore, altezza: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Etichetta("A", Colori.Spento, 11.sp)
        Spacer(Modifier.width(6.dp))
        Assegnabile(app, c, AzioneMidi.CROSSFADER, 0, Modifier.weight(1f)) {
            Crossfader(app.mixer.crossfader, { c.crossfader(it) }, Modifier.fillMaxWidth().height(altezza.dp))
        }
        Spacer(Modifier.width(6.dp))
        Etichetta("B", Colori.Spento, 11.sp)
    }
}

/** Vista MIXER: canali A e B ai lati (gain, EQ, filtro), al centro volumi, master, REC, crossfader e curva. */
@Composable
private fun MixerTelefono(app: StatoApp, c: Controllore, modifier: Modifier) {
    val m = app.mixer
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        CanaleTelefono(app, c, 0, Modifier.weight(1f))
        Column(Modifier.width(240.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                VolumeConMeter(app, c, 0, Modifier.fillMaxHeight())
                Column(Modifier.width(76.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Etichetta("MASTER", Colori.Spento, 10.sp)
                    Assegnabile(app, c, AzioneMidi.MASTER, 0) {
                        Manopola(m.master, { c.master(it) }, diametro = 36.dp, predefinito = 0.8f)
                    }
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Meter(m.livelloL, Modifier.width(6.dp).fillMaxHeight())
                        Meter(m.livelloR, Modifier.width(6.dp).fillMaxHeight())
                    }
                    Tasto(Modifier.width(72.dp).height(30.dp), bordo = if (m.rec) Colori.Rec else null, onTocco = { c.rec() }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Etichetta(if (m.rec) tempoRec(m.recSecondi) else "REC", if (m.rec) Colori.Rec else Colori.Spento, 11.sp)
                            Spacer(Modifier.width(4.dp))
                            Canvas(Modifier.size(7.dp)) { drawCircle(if (m.rec) Colori.Rec else Colori.Spento) }
                        }
                    }
                }
                VolumeConMeter(app, c, 1, Modifier.fillMaxHeight())
            }
            RigaCrossfader(app, c, 38)
            Assegnabile(app, c, AzioneMidi.CURVA, 0) {
                Box(Modifier.pointerInput(Unit) { detectTapGestures { c.curvaSuccessiva() } }.padding(horizontal = 10.dp, vertical = 2.dp)) {
                    SimboloCurva(m.curva)
                }
            }
        }
        CanaleTelefono(app, c, 1, Modifier.weight(1f))
    }
}

@Composable
private fun CanaleTelefono(app: StatoApp, c: Controllore, canale: Int, modifier: Modifier) {
    val s = app.mixer.canale[canale]
    val forma = RoundedCornerShape(14.dp)
    Column(
        modifier.background(Colori.Pannello, forma).border(1.dp, Colori.Linea, forma).padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Etichetta("CANALE ${if (canale == 0) "A" else "B"}", Colori.Spento, 11.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
            ManopolaEtichettata(app, c, AzioneMidi.GAIN, canale, "GAIN", s.gain, 34) { c.mixer(ParMixer.GAIN, canale, it) }
            ManopolaEtichettata(app, c, AzioneMidi.ALTI, canale, "HI", s.alti, 44) { c.mixer(ParMixer.ALTI, canale, it) }
            ManopolaEtichettata(app, c, AzioneMidi.MEDI, canale, "MID", s.medi, 44) { c.mixer(ParMixer.MEDI, canale, it) }
            ManopolaEtichettata(app, c, AzioneMidi.BASSI, canale, "LOW", s.bassi, 44) { c.mixer(ParMixer.BASSI, canale, it) }
        }
        Assegnabile(app, c, AzioneMidi.FILTRO, canale) {
            Manopola(s.filtro, { c.mixer(ParMixer.FILTRO, canale, it) }, tipo = TipoManopola.Filtro, diametro = 58.dp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Etichetta("LP", Colori.Scuro, 10.sp)
            Etichetta("FILTRO", Colori.Spento, 10.sp)
            Etichetta("HP", Colori.Scuro, 10.sp)
        }
    }
}

@Composable
private fun ManopolaEtichettata(
    app: StatoApp, c: Controllore, azione: AzioneMidi, canale: Int, nome: String, valore: Float, diametro: Int, onCambia: (Float) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Assegnabile(app, c, azione, canale) { Manopola(valore, onCambia, diametro = diametro.dp) }
        Etichetta(nome, Colori.Spento, 10.sp)
    }
}

/** Vista PAD: per ogni deck 4 hot cue e il loop, grandi. Hot cue: tocco = fissa/salta, tenuto = cancella. */
@Composable
private fun PadTelefono(app: StatoApp, c: Controllore, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        for (d in 0..1) {
            if (d == 1) Box(Modifier.width(1.dp).fillMaxHeight().background(Colori.Linea))
            val deck = app.deck[d]
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Etichetta("DECK ${deck.lettera} · HOT CUE", Colori.Spento, 11.sp)
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (i in 0 until 4) {
                        val punto = deck.hotCue[i]
                        Assegnabile(app, c, AZIONI_HOT_CUE[i], d, Modifier.weight(1f).fillMaxHeight()) {
                            Tasto(
                                Modifier.fillMaxSize(),
                                bordo = if (punto != null) Colori.HotCue[i] else null,
                                onTocco = { c.hotCue(d, i) },
                                onTieni = { c.cancellaHotCue(d, i) },
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    if (punto != null) Canvas(Modifier.size(9.dp)) { drawCircle(Colori.HotCue[i]) }
                                    Text("${i + 1}", color = if (punto != null) Colori.Testo else Colori.Spento, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                                    Text(
                                        punto?.let { durataDecimi(it) } ?: "vuoto",
                                        color = Colori.Spento, fontSize = 10.5.sp, fontFamily = if (punto != null) FontFamily.Monospace else null,
                                    )
                                }
                            }
                        }
                    }
                }
                Etichetta("LOOP", Colori.Spento, 11.sp)
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Assegnabile(app, c, AzioneMidi.LOOP_MEZZO, d, Modifier.weight(1f).fillMaxHeight()) {
                        Tasto(Modifier.fillMaxSize(), onTocco = { c.loopLunghezza(d, -1) }) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Freccia(sinistra = true, lato = 18.dp)
                                Text("½", color = Colori.Spento, fontSize = 11.sp)
                            }
                        }
                    }
                    Assegnabile(app, c, AzioneMidi.LOOP, d, Modifier.weight(2f).fillMaxHeight()) {
                        Tasto(Modifier.fillMaxSize(), bordo = if (deck.loopAttivo) Colori.Verde else null, onTocco = { c.loopTocco(d) }) {
                            Text(
                                etichettaLoop(LUNGHEZZE_LOOP[deck.loopIndice]),
                                color = if (deck.loopAttivo) Colori.Verde else Colori.Testo,
                                fontSize = 26.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.ExtraBold,
                            )
                        }
                    }
                    Assegnabile(app, c, AzioneMidi.LOOP_DOPPIO, d, Modifier.weight(1f).fillMaxHeight()) {
                        Tasto(Modifier.fillMaxSize(), onTocco = { c.loopLunghezza(d, +1) }) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Freccia(sinistra = false, lato = 18.dp)
                                Text("×2", color = Colori.Spento, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun durataDecimi(secondi: Double): String {
    val s = secondi.coerceAtLeast(0.0)
    return "%d:%04.1f".format((s / 60).toInt(), s % 60)
}

