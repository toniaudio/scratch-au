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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.toniaudio.djaaay.AZIONI_CAMPIONE
import com.toniaudio.djaaay.Controllore
import com.toniaudio.djaaay.MODI_PAD
import com.toniaudio.djaaay.SIMBOLI_MODO
import com.toniaudio.djaaay.StatoApp

/**
 * One Deck: il campionatore al posto del deck B. 8 pad: vuoto = scegli il suono dalla libreria; pieno = suona
 * (una volta / loop / tieni). Con MODIFICA un tocco apre il menu del pad (cambia suono, modo, togli).
 * Il suono esce sul canale B del mixer: il crossfader taglia tra il deck A e il campionatore.
 */
@Composable
fun PannelloCampionatore(app: StatoApp, c: Controllore, modifier: Modifier = Modifier) {
    val camp = app.campionatore
    val m = LocalMisure.current
    Box(modifier.fillMaxHeight().padding(horizontal = 10.dp, vertical = 6.dp)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Etichetta("CAMPIONATORE", Colori.Spento, if (m.telefono) 11.sp else 9.sp)
                Spacer(Modifier.weight(1f))
                Tasto(Modifier.width(if (m.telefono) 54.dp else 42.dp).height(if (m.telefono) 28.dp else 20.dp), onTocco = { c.fermaCampionatore() }) {
                    Etichetta("STOP", Colori.Spento, if (m.telefono) 10.5.sp else 8.sp)
                }
                Spacer(Modifier.width(6.dp))
                Tasto(
                    Modifier.width(if (m.telefono) 86.dp else 64.dp).height(if (m.telefono) 28.dp else 20.dp),
                    acceso = camp.modifica, onTocco = { c.modificaCampionatore() },
                ) {
                    Etichetta("MODIFICA", if (camp.modifica) Colori.Sfondo else Colori.Spento, if (m.telefono) 10.5.sp else 8.sp)
                }
            }
            GrigliaCampionatore(app, c, Modifier.weight(1f).fillMaxWidth(), grande = m.telefono)
        }
        val scegli = camp.scegliPer
        val menu = camp.menu
        if (scegli != null) LibreriaDeck(app, 1, c, Modifier.fillMaxSize(), perCampione = scegli)
        else if (menu != null) MenuPad(app, c, menu, Modifier.fillMaxSize())
    }
}

/** Gli 8 pad (2 righe × 4). Si usa anche, più grande, nella vista PAD del telefono. */
@Composable
fun GrigliaCampionatore(app: StatoApp, c: Controllore, modifier: Modifier = Modifier, grande: Boolean) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (grande) 8.dp else 5.dp)) {
        for (riga in 0..1) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (grande) 8.dp else 5.dp)) {
                for (colonna in 0..3) {
                    val i = riga * 4 + colonna
                    Assegnabile(app, c, AZIONI_CAMPIONE[i], 0, Modifier.weight(1f).fillMaxHeight()) {
                        PadCampione(app, c, i, Modifier.fillMaxSize(), grande)
                    }
                }
            }
        }
    }
}

@Composable
private fun PadCampione(app: StatoApp, c: Controllore, i: Int, modifier: Modifier, grande: Boolean) {
    val pad = app.campionatore.pad[i]
    val brano = pad.brano
    // il suono parte appena il dito tocca (onPremi), non al rilascio: niente ritardo
    Tasto(
        modifier,
        bordo = if (pad.suona) Colori.Neutro else if (app.campionatore.modifica && brano != null) Colori.Scuro else null,
        onPremi = { c.padCampione(i) },
        onRilascia = { c.padCampioneRilascia(i) },
    ) {
        Box(Modifier.fillMaxSize()) {
            val carica = pad.caricamento
            when {
                carica != null -> Text(
                    "${(carica * 100).toInt()}%", color = Colori.Spento, fontSize = if (grande) 12.sp else 9.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
                brano == null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("+", color = Colori.Scuro, fontSize = if (grande) 20.sp else 14.sp, fontWeight = FontWeight.Bold)
                    Text("${i + 1}", color = Colori.Scuro, fontSize = if (grande) 10.sp else 8.sp)
                }
                else -> {
                    Text(
                        brano.titolo,
                        color = if (pad.suona) Colori.Testo else Colori.Neutro,
                        fontSize = if (grande) 11.5.sp else 8.5.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 5.dp),
                    )
                    Text(
                        SIMBOLI_MODO[pad.modo], color = Colori.Spento, fontSize = if (grande) 9.sp else 7.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.TopEnd).padding(horizontal = 5.dp, vertical = 3.dp),
                    )
                    Text(
                        "${i + 1}", color = Colori.Scuro, fontSize = if (grande) 9.sp else 7.sp,
                        modifier = Modifier.align(Alignment.TopStart).padding(horizontal = 5.dp, vertical = 3.dp),
                    )
                    if (pad.suona) {
                        // avanzamento del suono, in basso
                        Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp).padding(horizontal = 6.dp)) {
                            drawRect(Colori.Linea, size = size)
                            drawRect(Colori.Neutro, size = Size(size.width * pad.avanzamento, size.height))
                        }
                    }
                }
            }
        }
    }
}

/** Menu di un pad (in MODIFICA): cambia suono, modo, togli. */
@Composable
private fun MenuPad(app: StatoApp, c: Controllore, i: Int, modifier: Modifier) {
    val pad = app.campionatore.pad[i]
    val forma = RoundedCornerShape(10.dp)
    Column(
        modifier
            .background(Color(0xFF10131B), forma)
            .border(1.dp, Colori.Linea, forma)
            .pointerInput(Unit) { detectTapGestures { } }  // i tocchi non passano ai pad sotto
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Etichetta("PAD ${i + 1}", Colori.Spento, 10.sp)
                Text(pad.brano?.titolo ?: "vuoto", color = Colori.Testo, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            Tasto(Modifier.width(34.dp).height(30.dp), onTocco = { app.campionatore.menu = null }) {
                Text("✕", color = Colori.Spento, fontSize = 13.sp)
            }
        }
        Schede(MODI_PAD, pad.modo, { c.campioneModo(i, it) }, Modifier.fillMaxWidth(), 11.sp, altezza = 34.dp)
        Text(
            when (pad.modo) {
                0 -> "Suona tutto il suono; ritoccato riparte dall'inizio."
                1 -> "Si ripete finché non lo ritocchi."
                else -> "Suona finché tieni premuto il pad."
            },
            color = Colori.Spento, fontSize = 10.5.sp,
        )
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tasto(Modifier.weight(1f).height(36.dp), onTocco = { c.apriSceltaCampione(i) }) {
                Etichetta("CAMBIA SUONO", Colori.Testo, 11.sp)
            }
            Tasto(Modifier.weight(1f).height(36.dp), onTocco = { c.campioneTogli(i) }) {
                Etichetta("TOGLI", Colori.Spento, 11.sp)
            }
        }
    }
}

/** Icona delle cuffie (pre-ascolto). */
@Composable
fun IconaCuffia(colore: Color, lato: androidx.compose.ui.unit.Dp) {
    Canvas(Modifier.width(lato).height(lato)) {
        val w = size.width
        val h = size.height
        val s = w * 0.12f
        drawArc(colore, 180f, 180f, false, Offset(w * 0.12f, h * 0.1f), Size(w * 0.76f, h * 0.8f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(s))
        drawRoundRect(colore, Offset(w * 0.04f, h * 0.5f), Size(w * 0.24f, h * 0.42f), androidx.compose.ui.geometry.CornerRadius(s))
        drawRoundRect(colore, Offset(w * 0.72f, h * 0.5f), Size(w * 0.24f, h * 0.42f), androidx.compose.ui.geometry.CornerRadius(s))
    }
}

/** Tasto cuffia di un canale: acceso = quel canale si sente in cuffia. */
@Composable
fun TastoCuffia(app: StatoApp, c: Controllore, canale: Int, modifier: Modifier, lato: androidx.compose.ui.unit.Dp = 14.dp) {
    val acceso = app.mixer.canale[canale].cuffia
    Assegnabile(app, c, com.toniaudio.djaaay.AzioneMidi.CUFFIA, canale, modifier) {
        Tasto(Modifier.fillMaxSize(), acceso = acceso, onTocco = { c.cuffia(canale) }) {
            IconaCuffia(if (acceso) Colori.Sfondo else Colori.Spento, lato)
        }
    }
}
