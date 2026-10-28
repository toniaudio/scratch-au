package com.toniaudio.djaaay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.toniaudio.djaaay.AzioneMidi
import com.toniaudio.djaaay.Controllore
import com.toniaudio.djaaay.ParMixer
import com.toniaudio.djaaay.StatoApp

@Composable
fun PannelloMixer(app: StatoApp, c: Controllore, modifier: Modifier = Modifier) {
    val m = app.mixer
    Column(
        modifier
            .fillMaxHeight()
            .background(Colori.Pannello)
            .border(1.dp, Colori.Linea)
            .padding(start = 8.dp, end = 8.dp, top = 5.dp, bottom = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Schede(listOf("MIXER", "FX"), m.tab, { m.tab = it }, Modifier.width(150.dp))
        Spacer(Modifier.height(4.dp))
        if (m.tab == 0) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ColonnaManopole(app, 0, c)
                Row(Modifier.weight(1f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                    ColonnaFader(app, 0, c)
                    ColonnaMaster(app, c)
                    ColonnaFader(app, 1, c)
                }
                ColonnaManopole(app, 1, c)
            }
        } else {
            PannelloFx(app, c, Modifier.weight(1f).fillMaxWidth())
        }
        // crossfader corto, lettere ai lati, sotto solo il simbolo della curva (tocco = curva successiva)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Etichetta("A", Colori.Spento, 9.sp)
            Spacer(Modifier.width(7.dp))
            Assegnabile(app, c, AzioneMidi.CROSSFADER, 0) {
                Crossfader(m.crossfader, { c.crossfader(it) }, Modifier.width(150.dp).height(26.dp))
            }
            Spacer(Modifier.width(7.dp))
            Etichetta("B", Colori.Spento, 9.sp)
        }
        Assegnabile(app, c, AzioneMidi.CURVA, 0, Modifier.padding(top = 3.dp)) {
            Box(Modifier.pointerInput(Unit) { detectTapGestures { c.curvaSuccessiva() } }.padding(2.dp)) {
                SimboloCurva(m.curva)
            }
        }
    }
}

/** GAIN (sottile), HI, MID, LOW, FILTRO (grande con arco). A ha le scritte a sinistra, B a destra. */
@Composable
private fun ColonnaManopole(app: StatoApp, canale: Int, c: Controllore) {
    val s = app.mixer.canale[canale]
    val allineamento = if (canale == 0) Alignment.End else Alignment.Start
    Column(Modifier.width(70.dp).fillMaxHeight(), horizontalAlignment = allineamento, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        RigaManopola("GAIN", canale) {
            Assegnabile(app, c, AzioneMidi.GAIN, canale) {
                Manopola(s.gain, { c.mixer(ParMixer.GAIN, canale, it) }, tipo = TipoManopola.Sottile, diametro = 16.dp, modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
        RigaManopola("HI", canale) { Assegnabile(app, c, AzioneMidi.ALTI, canale) { Manopola(s.alti, { c.mixer(ParMixer.ALTI, canale, it) }) } }
        RigaManopola("MID", canale) { Assegnabile(app, c, AzioneMidi.MEDI, canale) { Manopola(s.medi, { c.mixer(ParMixer.MEDI, canale, it) }) } }
        RigaManopola("LOW", canale) { Assegnabile(app, c, AzioneMidi.BASSI, canale) { Manopola(s.bassi, { c.mixer(ParMixer.BASSI, canale, it) }) } }
        Column(Modifier.width(56.dp).padding(top = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Assegnabile(app, c, AzioneMidi.FILTRO, canale) {
                Manopola(s.filtro, { c.mixer(ParMixer.FILTRO, canale, it) }, tipo = TipoManopola.Filtro, diametro = 40.dp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Etichetta("LP", Colori.Scuro, 7.5.sp)
                Etichetta("FILTRO", Colori.Spento, 7.5.sp)
                Etichetta("HP", Colori.Scuro, 7.5.sp)
            }
            Spacer(Modifier.height(4.dp))
            TastoCuffia(app, c, canale, Modifier.width(34.dp).height(20.dp), 11.dp)
        }
    }
}

@Composable
private fun RigaManopola(nome: String, canale: Int, manopola: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        if (canale == 0) {
            Etichetta(nome)
            manopola()
        } else {
            manopola()
            Etichetta(nome)
        }
    }
}

/** Volume del canale con il suo meter, lettera sotto. */
@Composable
private fun ColonnaFader(app: StatoApp, canale: Int, c: Controllore) {
    val s = app.mixer.canale[canale]
    Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val meter = @Composable { Meter(app.deck[canale].livello, Modifier.width(5.dp).fillMaxHeight()) }
            if (canale == 1) meter()
            Assegnabile(app, c, AzioneMidi.VOLUME, canale, Modifier.fillMaxHeight()) {
                FaderVerticale(s.volume, { c.mixer(ParMixer.VOLUME, canale, it) }, Modifier.fillMaxHeight())
            }
            if (canale == 0) meter()
        }
        Etichetta(if (canale == 0) "A" else "B", Colori.Spento, 9.sp, Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun ColonnaMaster(app: StatoApp, c: Controllore) {
    val m = app.mixer
    Column(Modifier.width(44.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Etichetta("MASTER")
        Assegnabile(app, c, AzioneMidi.MASTER, 0) {
            Manopola(m.master, { c.master(it) }, tipo = TipoManopola.Sottile, diametro = 20.dp, predefinito = 0.8f)
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Meter(m.livelloL, Modifier.width(5.dp).fillMaxHeight())
            Meter(m.livelloR, Modifier.width(5.dp).fillMaxHeight())
        }
        // cuffie: CUE/MASTER e volume
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Assegnabile(app, c, AzioneMidi.MIX_CUFFIE, 0) {
                Manopola(m.mixCuffie, { c.mixCuffie(it) }, tipo = TipoManopola.Sottile, diametro = 16.dp, predefinito = 0f)
            }
            Assegnabile(app, c, AzioneMidi.VOLUME_CUFFIE, 0) {
                Manopola(m.volumeCuffie, { c.volumeCuffie(it) }, tipo = TipoManopola.Sottile, diametro = 16.dp, predefinito = 0.8f)
            }
        }
        IconaCuffia(Colori.Spento, 9.dp)
        // REC: tocco = avvia/ferma la registrazione del mix in Musica/DVS
        Row(
            Modifier
                .background(if (m.rec) Colori.Rec.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(5.dp))
                .pointerInput(Unit) { detectTapGestures { c.rec() } }
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Etichetta(if (m.rec) tempoRec(m.recSecondi) else "REC", if (m.rec) Colori.Rec else Colori.Spento)
            Spacer(Modifier.width(3.dp))
            androidx.compose.foundation.Canvas(Modifier.size(6.dp)) { drawCircle(if (m.rec) Colori.Rec else Colori.Spento) }
        }
    }
}

internal fun tempoRec(secondi: Double): String {
    val s = secondi.toInt()
    return "%d:%02d".format(s / 60, s % 60)
}
