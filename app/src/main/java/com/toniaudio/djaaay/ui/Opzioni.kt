package com.toniaudio.djaaay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.toniaudio.djaaay.Controllore
import com.toniaudio.djaaay.StatoApp
import kotlinx.coroutines.delay

/** Opzioni, aperte toccando "DVS" in alto: Generale · Audio · MIDI · DVS. */
@Composable
fun FinestraOpzioni(app: StatoApp, c: Controllore) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC05070B))
            .pointerInput(Unit) { detectTapGestures { c.chiudiOpzioni() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 60.dp, vertical = 16.dp)
                .background(Colori.Pannello, RoundedCornerShape(14.dp))
                .border(1.dp, Colori.Linea, RoundedCornerShape(14.dp))
                .pointerInput(Unit) { detectTapGestures { } }  // i tocchi dentro non chiudono
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Opzioni", color = Colori.Testo, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(14.dp))
                Schede(listOf("Generale", "Audio", "MIDI", "DVS"), app.schedaOpzioni, { app.schedaOpzioni = it }, Modifier.width(300.dp), 10.sp)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(28.dp).background(Colori.Pannello2, RoundedCornerShape(7.dp))
                        .pointerInput(Unit) { detectTapGestures { c.chiudiOpzioni() } },
                    contentAlignment = Alignment.Center,
                ) { Text("✕", color = Colori.Spento, fontSize = 13.sp) }
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (app.schedaOpzioni) {
                    0 -> SchedaGenerale(app, c)
                    1 -> SchedaAudio(app, c)
                    2 -> InArrivo(
                        "Controller MIDI",
                        "Qui si collegherà l'M-Audio X-Session Pro via USB: preset già pronto e tasto LEARN per assegnare " +
                            "ogni comando (crossfader, volumi, EQ, filtri, Play, Cue, Sync, hot cue, loop, effetti…). In arrivo.",
                    )
                    else -> InArrivo(
                        "DVS con vinile timecode",
                        "Con una scheda audio USB e un vinile timecode Serato, il tasto DVS di ogni deck lo farà seguire il " +
                            "giradischi. Qui ci saranno lo scope del segnale, il livello d'ingresso e la calibrazione. In arrivo.",
                    )
                }
            }
        }
    }
}

@Composable
private fun SchedaGenerale(app: StatoApp, c: Controllore) {
    val o = app.opzioni
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(26.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Titolo("PIATTO E SCRATCH")
            RigaCursore("Frizione slipmat", "quanto scivola il disco rilasciato", o.frizione, "${(o.frizione * 100).toInt()}%") {
                o.frizione = it; c.applicaOpzioni()
            }
            RigaCursore("Avvio motore", "tempo per partire con Play", o.avvio / 2f, "%.2f s".format(o.avvio)) {
                o.avvio = it * 2f; c.applicaOpzioni()
            }
            RigaCursore("Frenata", "tempo per fermarsi con Stop", o.frenata / 2f, "%.2f s".format(o.frenata)) {
                o.frenata = it * 2f; c.applicaOpzioni()
            }
            RigaCursore("Sensibilità piatto", "audio per giro di dito", (o.sensibilita - 0.25f) / 1.75f, "×%.2f".format(o.sensibilita)) {
                o.sensibilita = 0.25f + it * 1.75f
            }
            RigaInterruttore("Scratch sulla waveform", o.scratchWaveform) { o.scratchWaveform = it }
            Titolo("PITCH")
            RigaScelta("Escursione", listOf("±8%", "±16%", "±50%"), listOf(8, 16, 50).indexOf(o.escursione)) {
                c.escursione(listOf(8, 16, 50)[it])
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Titolo("CROSSFADER")
            RigaScelta("Curva", listOf("Taglio", "Lineare", "Morbida"), app.mixer.curva) { c.curva(it) }
            RigaInterruttore("Inverti (hamster)", app.mixer.inverti) { c.inverti(it) }
            Titolo("REGISTRAZIONE")
            Riga("REC", "il tasto REC sotto il master registra il mix in Musica/DVS") {}
        }
    }
}

@Composable
private fun SchedaAudio(app: StatoApp, c: Controllore) {
    LaunchedEffect(Unit) {
        while (true) {
            c.aggiornaDiagnostica()
            delay(500)
        }
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(26.dp)) {
        Column(Modifier.weight(1f)) {
            Titolo("USCITA")
            RigaScelta("Buffer", listOf("1×", "2×", "4×"), listOf(1, 2, 4).indexOf(app.opzioni.buffer)) {
                c.impostaBuffer(listOf(1, 2, 4)[it])
            }
            Text(
                "Più piccolo = meno ritardo tra dito e suono. Se \"Interruzioni del suono\" cresce mentre suona, scegline uno più grande.",
                color = Colori.Spento, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Titolo("DIAGNOSTICA")
            Text(app.diagnostica, color = Colori.Spento, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun InArrivo(titolo: String, testo: String) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text(titolo, color = Colori.Testo, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(testo, color = Colori.Spento, fontSize = 11.sp)
    }
}

@Composable
private fun Titolo(testo: String) {
    Etichetta(testo, Colori.Neutro, 9.sp, Modifier.padding(top = 9.dp, bottom = 3.dp))
}

@Composable
private fun Riga(nome: String, sotto: String?, controllo: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(150.dp)) {
            Text(nome, color = Colori.Testo, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            if (sotto != null) Text(sotto, color = Colori.Spento, fontSize = 9.sp)
        }
        Spacer(Modifier.weight(1f))
        controllo()
    }
}

@Composable
private fun RigaCursore(nome: String, sotto: String, valore: Float, testo: String, onCambia: (Float) -> Unit) {
    Riga(nome, sotto) {
        CursoreSottile(valore, onCambia, Modifier.width(150.dp))
        Text(testo, color = Colori.Testo, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(58.dp).padding(start = 6.dp))
    }
}

@Composable
private fun RigaInterruttore(nome: String, acceso: Boolean, onCambia: (Boolean) -> Unit) {
    Riga(nome, null) {
        Switch(
            acceso, onCambia,
            colors = SwitchDefaults.colors(checkedTrackColor = Colori.Verde, checkedThumbColor = Color.White, uncheckedTrackColor = Colori.Linea),
        )
    }
}

@Composable
private fun RigaScelta(nome: String, voci: List<String>, scelta: Int, onScegli: (Int) -> Unit) {
    Riga(nome, null) {
        Schede(voci, scelta, onScegli, Modifier.width(60.dp * voci.size), 10.sp)
    }
}
