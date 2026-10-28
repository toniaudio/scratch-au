package com.toniaudio.djaaay.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.toniaudio.djaaay.Controllore
import com.toniaudio.djaaay.StatoApp
import com.toniaudio.djaaay.nomeComando
import kotlinx.coroutines.delay

/** La schermata principale, come nelle bozze in design/. */
@Composable
fun SchermataDJ(app: StatoApp, c: Controllore) {
    Box(Modifier.fillMaxSize().background(Colori.Sfondo)) {
        Column(Modifier.fillMaxSize()) {
            BarraAlta(app, c)
            Box(Modifier.fillMaxWidth().height(132.dp)) {
                Column(Modifier.fillMaxSize()) {
                    for (d in 0..1) {
                        WaveformDeck(
                            app.deck[d], app.zoomSecondi, app.opzioni.scratchWaveform,
                            onZoom = { app.zoomSecondi = it },
                            onToccoInizio = { c.toccoInizio(d) },
                            onToccoSposta = { c.toccoSposta(d, it) },
                            onToccoFine = { c.toccoFine(d) },
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    }
                }
                // la puntina: fissa al centro
                Canvas(Modifier.fillMaxSize()) {
                    drawLine(Color.White.copy(alpha = 0.35f), Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 6.dp.toPx())
                    drawLine(Color.White, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 2.dp.toPx())
                }
            }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                PannelloDeck(app, 0, c, Modifier.weight(0.33f))
                PannelloMixer(app, c, Modifier.weight(0.34f))
                PannelloDeck(app, 1, c, Modifier.weight(0.33f))
            }
        }
        if (app.midi.learn || app.midi.procedura != null) BarraLearn(app, c)
        if (app.opzioniAperte) FinestraOpzioni(app, c)
        Messaggio(app)
    }
}

/** Avviso in basso che sparisce da solo. */
@Composable
private fun Messaggio(app: StatoApp) {
    val testo = app.messaggio ?: return
    LaunchedEffect(testo) {
        delay(3000)
        if (app.messaggio == testo) app.messaggio = null
    }
    Box(Modifier.fillMaxSize().padding(bottom = 50.dp), contentAlignment = Alignment.BottomCenter) {
        Text(
            testo, color = Colori.Testo, fontSize = 12.sp,
            modifier = Modifier
                .width(420.dp)
                .background(Colori.Pannello2.copy(alpha = 0.97f), RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}

/** Barra verde del LEARN MIDI (e della configurazione guidata), sopra le waveform. */
@Composable
private fun BarraLearn(app: StatoApp, c: Controllore) {
    val midi = app.midi
    val procedura = midi.procedura
    val bersaglio = midi.bersaglio
    val testo = when {
        procedura != null && bersaglio != null ->
            "Configurazione ${midi.passo + 1}/${procedura.size} · muovi sul controller: ${nomeComando(bersaglio.first, bersaglio.second)}"
        bersaglio != null -> "In attesa… muovi sul controller il comando per ${nomeComando(bersaglio.first, bersaglio.second)}"
        else -> "LEARN · tocca un comando, poi muovi quello del controller"
    }
    Box(Modifier.fillMaxSize().padding(top = 44.dp), contentAlignment = Alignment.TopCenter) {
        Row(
            Modifier
                .width(520.dp)
                .background(Colori.Verde, RoundedCornerShape(10.dp))
                .padding(start = 14.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(testo, color = Colori.Sfondo, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                val ultimo = midi.ultimo
                Text(
                    if (midi.dispositivo == null) "nessun controller collegato" else "${midi.dispositivo}${if (ultimo.isNotEmpty()) " · ultimo: $ultimo" else ""}",
                    color = Colori.Sfondo.copy(alpha = 0.7f), fontSize = 9.sp,
                )
            }
            if (procedura != null) {
                PillolaLearn("SALTA") { c.proceduraSalta() }
                Spacer(Modifier.width(6.dp))
            }
            PillolaLearn("FINE") { if (procedura != null) c.proceduraFine() else c.learnAttiva(false) }
        }
    }
}

@Composable
private fun PillolaLearn(testo: String, onTocco: () -> Unit) {
    Box(
        Modifier
            .background(Colori.Sfondo, RoundedCornerShape(7.dp))
            .pointerInput(Unit) { detectTapGestures { onTocco() } }
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) { Text(testo, color = Colori.Verde, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
}
