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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.toniaudio.djaaay.AzioneMidi
import com.toniaudio.djaaay.Controllore
import com.toniaudio.djaaay.nomeComando
import com.toniaudio.djaaay.StatoApp
import com.toniaudio.djaaay.StatoDvs
import com.toniaudio.djaaay.TIPI_TIMECODE
import kotlin.math.abs
import kotlin.math.roundToInt
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
                    2 -> SchedaMidi(app, c)
                    else -> SchedaDvs(app, c)
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
            Titolo("ONE DECK")
            Riga("One Deck", "deck A + campionatore (8 pad) al posto del deck B") {
                Switch(
                    o.unDeck, { c.unDeck(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Colori.Verde, checkedThumbColor = Color.White, uncheckedTrackColor = Colori.Linea),
                )
            }
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
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Titolo("USCITA")
            RigaScelta("Buffer", listOf("1×", "2×", "4×"), listOf(1, 2, 4).indexOf(app.opzioni.buffer)) {
                c.impostaBuffer(listOf(1, 2, 4)[it])
            }
            Text(
                "Più piccolo = meno ritardo tra dito e suono. Se \"Interruzioni del suono\" cresce mentre suona, scegline uno più grande.",
                color = Colori.Spento, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp),
            )
            Titolo("PRE-ASCOLTO IN CUFFIA")
            Riga(
                "Cuffie sulle uscite 3–4",
                if (app.cuffieAttive) "attivo: casse sulle uscite 1–2, cuffie sulle 3–4"
                else "serve una scheda audio USB con 4 uscite",
            ) {
                Switch(
                    app.opzioni.cuffie, { c.attivaCuffie(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Colori.Verde, checkedThumbColor = Color.White, uncheckedTrackColor = Colori.Linea),
                )
            }
            RigaCursore("CUE ↔ MASTER", "cosa senti in cuffia", app.mixer.mixCuffie, "${(app.mixer.mixCuffie * 100).toInt()}%") {
                c.mixCuffie(it)
            }
            RigaCursore("Volume cuffie", "", app.mixer.volumeCuffie, "${(app.mixer.volumeCuffie * 100).toInt()}%") {
                c.volumeCuffie(it)
            }
            Text(
                "Col tasto cuffia di ogni canale (nel mixer) scegli cosa pre-ascoltare.",
                color = Colori.Spento, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp),
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
private fun RigaScelta(nome: String, voci: List<String>, scelta: Int, larghezzaVoce: Dp = 60.dp, onScegli: (Int) -> Unit) {
    Riga(nome, null) {
        Schede(voci, scelta, onScegli, Modifier.width(larghezzaVoce * voci.size), 10.sp)
    }
}

@Composable
private fun SchedaMidi(app: StatoApp, c: Controllore) {
    val midi = app.midi
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(26.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Titolo("CONTROLLER")
            Riga(midi.dispositivo ?: "Nessun controller", if (midi.dispositivo != null) "collegato via USB" else "collega l'X-Session Pro con il cavo USB (adattatore USB-C)") {
                if (midi.dispositivo != null) {
                    Row(
                        Modifier.background(Colori.Verde.copy(alpha = 0.15f), RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(6.dp).background(Colori.Verde, RoundedCornerShape(3.dp)))
                        Spacer(Modifier.width(5.dp))
                        Text("collegato", color = Colori.Verde, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (midi.ultimo.isNotEmpty()) Text("ultimo messaggio: ${midi.ultimo}", color = Colori.Spento, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            Riga("Configurazione guidata", "X-Session Pro: muovi un controllo alla volta (2 minuti)") {
                PulsanteOpzioni("INIZIA", acceso = false) { c.proceduraInizia() }
            }
            Riga("Assegna comandi", "LEARN: tocchi un comando dell'app e muovi quello del controller") {
                PulsanteOpzioni("LEARN", acceso = true) { c.learnAttiva(true) }
            }
            Riga("Togli tutte le assegnazioni", null) {
                PulsanteOpzioni("Azzera", acceso = false) { c.midiAzzera() }
            }
            Titolo("COMANDI ASSEGNABILI")
            Text(
                "Tutti quelli nell'elenco a destra: toccane uno e muovi il controllo. Ci sono anche quelli senza tasto " +
                    "sullo schermo (libreria su/giù/scorri/carica, effetto successivo).",
                color = Colori.Spento, fontSize = 10.sp,
            )
        }
        Column(Modifier.weight(1f)) {
            Titolo("ASSEGNAZIONI")
            Column(Modifier.fillMaxSize().border(1.dp, Colori.Linea, RoundedCornerShape(8.dp))) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Etichetta("FUNZIONE", Colori.Spento, 8.sp, Modifier.weight(1f))
                    Etichetta("CONTROLLER", Colori.Spento, 8.sp, Modifier.weight(1f))
                }
                val righe = AzioneMidi.entries.flatMap { a -> if (a.perDeck) listOf(a to 0, a to 1) else listOf(a to 0) }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(righe) { (azione, deck) ->
                        val assegnata = midi.assegnazioni.firstOrNull { it.azione == azione && it.deck == deck }
                        val inAttesa = midi.bersaglio == (azione to deck)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(26.dp)
                                .background(if (inAttesa) Colori.Verde.copy(alpha = 0.15f) else Color.Transparent)
                                .pointerInput(azione, deck) { detectTapGestures { c.learnScegli(azione, deck) } }
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(nomeComando(azione, deck), color = Colori.Testo, fontSize = 10.sp, modifier = Modifier.weight(1f), maxLines = 1)
                            Text(
                                when {
                                    inAttesa -> "muovi il controllo…"
                                    assegnata != null -> assegnata.sorgente.testo
                                    else -> "—"
                                },
                                color = if (inAttesa) Colori.Verde else if (assegnata != null) Colori.Neutro else Colori.Scuro,
                                fontSize = 9.5.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f), maxLines = 1,
                            )
                            Box(
                                Modifier.size(22.dp).pointerInput(assegnata) { detectTapGestures { assegnata?.let { c.midiRimuovi(it) } } },
                                contentAlignment = Alignment.Center,
                            ) { if (assegnata != null) Text("✕", color = Colori.Spento, fontSize = 10.sp) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PulsanteOpzioni(testo: String, acceso: Boolean, onTocco: () -> Unit) {
    Tasto(Modifier.height(26.dp).width(78.dp), acceso = acceso, onTocco = onTocco) {
        Text(testo, color = if (acceso) Colori.Sfondo else Colori.Testo, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SchedaDvs(app: StatoApp, c: Controllore) {
    val dvs = app.dvs
    LaunchedEffect(Unit) { c.dvsAggiornaSchede() }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(26.dp)) {
        // a sinistra: scope circolare del segnale e letture
        Column(Modifier.width(190.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            ScopeTimecode(app, Modifier.size(150.dp))
            Spacer(Modifier.height(6.dp))
            val buono = dvs.qualita >= 0.7f
            RigaLettura(
                "Segnale",
                if (!dvs.aperto) "—" else "${(dvs.qualita * 100).roundToInt()}% · ${if (buono) "buono" else if (dvs.qualita > 0.3f) "debole" else "assente"}",
                if (buono) Colori.Verde else Colori.Testo,
            )
            // scarto dalla velocità normale e verso (▶ avanti, ◀ indietro, ■ fermo)
            val verso = if (dvs.velocita < -0.01f) "◀" else if (dvs.velocita > 0.01f) "▶" else "■"
            RigaLettura("Velocità", if (!dvs.aperto || verso == "■") "— $verso" else "%+.1f%% %s".format((abs(dvs.velocita) - 1f) * 100f, verso))
            RigaLettura("Posizione", if (dvs.posizione < 0) "—" else durataDecimi(dvs.posizione))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PulsanteOpzioni("Calibra", acceso = true) { c.dvsCalibra() }
                PulsanteOpzioni("Azzera", acceso = false) { c.dvsAzzera() }
            }
            if (dvs.calibrato) {
                Text(
                    if (dvs.invertiti) "calibrato · canali scambiati e corretti" else "calibrato",
                    color = Colori.Spento, fontSize = 9.sp, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        // a destra: scheda, ingresso, livello, formato, come segue il vinile
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Titolo("SEGNALE TIMECODE")
            val scheda = dvs.scheda
            Riga(
                "Scheda audio",
                when {
                    dvs.aperto && scheda != null -> "${scheda.nome} · ${if (scheda.usb) "USB" else "linea"}"
                    dvs.schede.isEmpty() -> "collega una scheda audio USB (es. con ingressi phono)"
                    else -> dvs.schede.joinToString(" · ") { it.nome }
                },
            ) {
                if (dvs.aperto) {
                    Row(
                        Modifier.background(Colori.Verde.copy(alpha = 0.15f), RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(6.dp).background(Colori.Verde, RoundedCornerShape(3.dp)))
                        Spacer(Modifier.width(5.dp))
                        Text("collegata", color = Colori.Verde, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    PulsanteOpzioni("Attiva", acceso = true) { c.apriIngressoDvs() }
                }
            }
            if (dvs.schede.size > 1) {
                RigaScelta("Scheda", dvs.schede.map { it.nome.take(12) }, dvs.schede.indexOfFirst { it.id == dvs.scheda?.id }, larghezzaVoce = 90.dp) {
                    c.dvsScegliScheda(dvs.schede[it])
                }
            }
            RigaScelta("Ingresso", listOf("1–2", "3–4"), dvs.coppia) { c.dvsCoppia(it) }
            Riga("Livello ingresso", "porta il segnale nel verde") {
                MeterOrizzontale(dvs.livello, Modifier.width(90.dp).height(6.dp))
                Spacer(Modifier.width(10.dp))
                CursoreSottile((dvs.guadagnoDb + 12f) / 36f, { c.dvsGuadagno(it * 36f - 12f) }, Modifier.width(110.dp))
                Text("%+d dB".format(dvs.guadagnoDb.roundToInt()), color = Colori.Testo, fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.width(52.dp).padding(start = 6.dp))
            }
            RigaScelta("Tipo di timecode", TIPI_TIMECODE, dvs.tipo, larghezzaVoce = 88.dp) { c.dvsTipo(it) }
            RigaScelta("Giri", listOf("33⅓", "45"), if (dvs.giri45) 1 else 0) { c.dvsGiri45(it == 1) }
            Titolo("COME SEGUE IL VINILE")
            Riga("Modalità", "assoluta = posizione del vinile; relativa = solo velocità") {
                Schede(listOf("Assoluta", "Relativa"), if (dvs.assoluta) 0 else 1, { c.dvsAssoluta(it == 0) }, Modifier.width(140.dp), 10.sp)
            }
            RigaCursore("Sensibilità a disco fermo", "più alta = si ferma prima", dvs.sensibilita, "${(dvs.sensibilita * 100).roundToInt()}%") {
                c.dvsSensibilita(it)
            }
            Riga("Protezione salto puntina", "in assoluta: se la puntina salta mentre suona, il brano non salta") {
                Switch(
                    dvs.protezione, { c.dvsProtezione(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Colori.Verde, checkedThumbColor = Color.White, uncheckedTrackColor = Colori.Linea),
                )
            }
            Text(
                "Poi tocca DVS sul deck A, sul B o su tutti e due: quei deck seguono il giradischi.",
                color = Colori.Spento, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** Scope circolare: un buon segnale timecode è un anello verde regolare. */
@Composable
private fun ScopeTimecode(app: StatoApp, modifier: Modifier = Modifier) {
    val dvs = app.dvs
    val versione = dvs.versioneScope  // si ridisegna a ogni lettura
    Canvas(modifier) {
        if (versione < 0) return@Canvas
        val r = size.minDimension / 2
        val centro = Offset(size.width / 2, size.height / 2)
        drawCircle(Color(0xFF0B0E14), r, centro)
        drawCircle(Colori.Linea, r, centro, style = Stroke(1.dp.toPx()))
        drawLine(Colori.Linea, Offset(centro.x, centro.y - r), Offset(centro.x, centro.y + r), 0.5.dp.toPx())
        drawLine(Colori.Linea, Offset(centro.x - r, centro.y), Offset(centro.x + r, centro.y), 0.5.dp.toPx())
        val colore = if (dvs.qualita >= 0.7f) Colori.Verde else if (dvs.qualita > 0.3f) Colori.Arancio else Colori.Spento
        val raggioPunti = r * 0.85f
        val punto = 1.6.dp.toPx()
        for (i in 0 until StatoDvs.PUNTI_SCOPE) {
            val x = dvs.scope[2 * i]
            val y = dvs.scope[2 * i + 1]
            drawCircle(colore.copy(alpha = 0.3f + 0.7f * i / StatoDvs.PUNTI_SCOPE), punto, Offset(centro.x + x * raggioPunti, centro.y - y * raggioPunti))
        }
    }
}

@Composable
private fun RigaLettura(nome: String, valore: String, colore: Color = Colori.Testo) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(nome, color = Colori.Testo, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Text(valore, color = colore, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

/** Meter orizzontale del livello d'ingresso: verde fino a −6 dB, poi arancio, rosso vicino al massimo. */
@Composable
private fun MeterOrizzontale(livello: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawRoundRect(Colori.Linea, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
        val v = kotlin.math.sqrt(livello.coerceIn(0f, 1f))
        val colore = if (livello > 0.9f) Colori.RossoMeter else if (livello > 0.5f) Colori.Arancio else Colori.Verde
        drawRoundRect(colore, size = androidx.compose.ui.geometry.Size(size.width * v, size.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
    }
}

private fun durataDecimi(secondi: Double): String {
    val s = secondi.coerceAtLeast(0.0)
    return "%02d:%04.1f".format((s / 60).toInt(), s % 60)
}
