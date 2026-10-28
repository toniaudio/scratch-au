package com.toniaudio.djaaay.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import android.os.Build
import android.view.RoundedCorner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.toniaudio.djaaay.AzioneMidi
import com.toniaudio.djaaay.Controllore
import com.toniaudio.djaaay.StatoApp
import com.toniaudio.djaaay.StatoDeck
import kotlin.math.sqrt

/** In alto: per deck libreria (all'estremità), lettera, titolo, BPM, tempo che manca. Al centro DVS = opzioni. */
@Composable
fun BarraAlta(app: StatoApp, c: Controllore, modifier: Modifier = Modifier) {
    val telefono = LocalMisure.current.telefono
    val altezza = if (telefono) 44.dp else 38.dp
    // gli angoli arrotondati dello schermo non devono mangiare i tasti della libreria alle estremità
    val (angoloS, angoloD) = marginiAngoli(altezza)
    val base = if (telefono) 6.dp else 10.dp
    Row(
        modifier.fillMaxWidth().height(altezza).background(Colori.Barra)
            .padding(start = maxOf(base, angoloS), end = maxOf(base, angoloD)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        InfoDeck(app, app.deck[0], c, Modifier.weight(1f))
        Box(
            Modifier
                .background(Colori.Pannello2, RoundedCornerShape(7.dp))
                .border(1.dp, Colori.Linea, RoundedCornerShape(7.dp))
                .pointerInput(Unit) { detectTapGestures { c.apriOpzioni() } }
                .padding(horizontal = 14.dp, vertical = 3.dp),
        ) {
            Text("DVS", color = Colori.Testo, fontSize = if (telefono) 17.sp else 15.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
        }
        if (app.opzioni.unDeck) InfoCampionatore(app, Modifier.weight(1f)) else InfoDeck(app, app.deck[1], c, Modifier.weight(1f))
    }
}

/**
 * Quanto stare lontani dai bordi sinistro e destro perché un tasto alto quasi quanto la barra non finisca nella
 * curva degli angoli dello schermo (Android 12+; prima, o se lo schermo non lo dice, 0). Si tiene conto del foro della
 * fotocamera, che la schermata lascia già fuori.
 */
@Composable
private fun marginiAngoli(altezza: Dp): Pair<Dp, Dp> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return 0.dp to 0.dp
    // dalla View (null se non è su uno schermo): Context.getDisplay() invece può lanciare un'eccezione
    val display = LocalView.current.display ?: return 0.dp to 0.dp
    val densita = LocalDensity.current
    val direzione = LocalLayoutDirection.current
    val fotoS = with(densita) { WindowInsets.displayCutout.getLeft(densita, direzione).toDp() }
    val fotoD = with(densita) { WindowInsets.displayCutout.getRight(densita, direzione).toDp() }
    fun margine(posizione: Int): Dp {
        val raggio = with(densita) { (display.getRoundedCorner(posizione)?.radius ?: 0).toFloat().toDp() }.value
        // il tasto parte ~6 dp sotto il bordo alto: dove la curva dell'angolo è a quell'altezza, più 3 dp di aria
        val y = minOf(raggio, (altezza.value - 32f) / 2f)
        if (raggio <= 0f) return 0.dp
        val dentro = raggio - sqrt(maxOf(0f, raggio * raggio - (raggio - y) * (raggio - y)))
        return (dentro + 3f).dp
    }
    return maxOf(0.dp, margine(RoundedCorner.POSITION_TOP_LEFT) - fotoS) to maxOf(0.dp, margine(RoundedCorner.POSITION_TOP_RIGHT) - fotoD)
}

/** One Deck: al posto del deck B, quanti suoni ha il campionatore e quanti ne suonano. */
@Composable
private fun InfoCampionatore(app: StatoApp, modifier: Modifier) {
    val telefono = LocalMisure.current.telefono
    val pad = app.campionatore.pad
    Column(modifier, horizontalAlignment = Alignment.End) {
        Text("CAMPIONATORE", color = Colori.Testo, fontSize = if (telefono) 13.5.sp else 11.5.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "${pad.count { it.brano != null }} suoni · ${pad.count { it.suona }} in play",
            color = Colori.Spento, fontSize = if (telefono) 11.5.sp else 10.sp,
        )
    }
}

@Composable
private fun InfoDeck(app: StatoApp, deck: StatoDeck, c: Controllore, modifier: Modifier) {
    val b = deck.indice == 1
    val telefono = LocalMisure.current.telefono
    Row(modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val libreria = @Composable {
            Assegnabile(app, c, AzioneMidi.LIB_APRI, deck.indice) {
                Box(
                    Modifier
                        .size(width = if (telefono) 42.dp else 30.dp, height = if (telefono) 32.dp else 26.dp)
                        .background(if (deck.libreriaAperta) Colori.Neutro else Colori.Pannello2, RoundedCornerShape(7.dp))
                        .pointerInput(Unit) { detectTapGestures { c.apriLibreria(deck.indice) } },
                    contentAlignment = Alignment.Center,
                ) { IconaLibreria(if (deck.libreriaAperta) Colori.Sfondo else Colori.Testo, if (telefono) 19.dp else 15.dp) }
            }
        }
        val lettera = @Composable {
            Box(Modifier.size(22.dp).background(Colori.Pannello2, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                Text(deck.lettera, color = Colori.Testo, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
        val titolo = @Composable { m: Modifier ->
            Column(m, horizontalAlignment = if (b) Alignment.End else Alignment.Start) {
                val brano = deck.brano
                Text(
                    brano?.titolo ?: if (deck.caricamento != null) "Caricamento…" else "Nessun brano",
                    color = if (brano != null) Colori.Testo else Colori.Spento,
                    fontSize = if (telefono) 13.5.sp else 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = if (b) TextAlign.End else TextAlign.Start,
                )
                if (brano != null) {
                    Text(
                        listOf(brano.artista, durata(brano.durataMs / 1000.0)).filter { it.isNotEmpty() }.joinToString(" · "),
                        color = Colori.Spento, fontSize = if (telefono) 11.5.sp else 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        val tempo = @Composable {
            Column(horizontalAlignment = if (b) Alignment.Start else Alignment.End) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(if (deck.bpm > 0) "%.1f".format(deck.bpmEffettivi) else "—", color = Colori.Testo, fontSize = if (telefono) 16.sp else 13.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text(" BPM", color = Colori.Spento, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                }
                Text(
                    if (deck.brano != null) "-" + durata(deck.durata - deck.posizione) else "",
                    color = Colori.Spento, fontSize = if (telefono) 12.sp else 10.5.sp, fontFamily = FontFamily.Monospace,
                )
            }
        }
        // sul telefono niente lettera del deck: il colore della waveform e la posizione bastano
        if (!b) {
            libreria(); if (!telefono) lettera(); titolo(Modifier.weight(1f)); tempo()
        } else {
            tempo(); titolo(Modifier.weight(1f)); if (!telefono) lettera(); libreria()
        }
    }
}

@Composable
private fun IconaLibreria(colore: androidx.compose.ui.graphics.Color, lato: androidx.compose.ui.unit.Dp) {
    Canvas(Modifier.size(lato)) {
        val s = 2.dp.toPx()
        val w = size.width
        val h = size.height
        drawLine(colore, Offset(w * 0.05f, h * 0.2f), Offset(w * 0.6f, h * 0.2f), s, StrokeCap.Round)
        drawLine(colore, Offset(w * 0.05f, h * 0.5f), Offset(w * 0.6f, h * 0.5f), s, StrokeCap.Round)
        drawLine(colore, Offset(w * 0.05f, h * 0.8f), Offset(w * 0.4f, h * 0.8f), s, StrokeCap.Round)
        drawCircle(colore, w * 0.12f, Offset(w * 0.78f, h * 0.78f), style = androidx.compose.ui.graphics.drawscope.Stroke(s))
        drawLine(colore, Offset(w * 0.9f, h * 0.78f), Offset(w * 0.9f, h * 0.15f), s, StrokeCap.Round)
    }
}

fun durata(secondi: Double): String {
    val s = maxOf(0, secondi.toInt())
    return "%d:%02d".format(s / 60, s % 60)
}
