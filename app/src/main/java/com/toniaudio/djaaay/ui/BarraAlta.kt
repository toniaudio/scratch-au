package com.toniaudio.djaaay.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.toniaudio.djaaay.Controllore
import com.toniaudio.djaaay.StatoApp
import com.toniaudio.djaaay.StatoDeck

/** In alto: per deck libreria (all'estremità), lettera, titolo, BPM, tempo che manca. Al centro DVS = opzioni. */
@Composable
fun BarraAlta(app: StatoApp, c: Controllore, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().height(34.dp).background(Colori.Barra).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        InfoDeck(app.deck[0], c, Modifier.weight(1f))
        Box(
            Modifier
                .background(Colori.Pannello2, RoundedCornerShape(7.dp))
                .border(1.dp, Colori.Linea, RoundedCornerShape(7.dp))
                .pointerInput(Unit) { detectTapGestures { c.apriOpzioni() } }
                .padding(horizontal = 14.dp, vertical = 3.dp),
        ) {
            Text("DVS", color = Colori.Testo, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
        }
        InfoDeck(app.deck[1], c, Modifier.weight(1f))
    }
}

@Composable
private fun InfoDeck(deck: StatoDeck, c: Controllore, modifier: Modifier) {
    val b = deck.indice == 1
    Row(modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val libreria = @Composable {
            Box(
                Modifier
                    .size(width = 30.dp, height = 26.dp)
                    .background(if (deck.libreriaAperta) Colori.Neutro else Colori.Pannello2, RoundedCornerShape(7.dp))
                    .pointerInput(Unit) { detectTapGestures { c.apriLibreria(deck.indice) } },
                contentAlignment = Alignment.Center,
            ) { IconaLibreria(if (deck.libreriaAperta) Colori.Sfondo else Colori.Testo) }
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
                    fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = if (b) TextAlign.End else TextAlign.Start,
                )
                if (brano != null) {
                    Text(
                        listOf(brano.artista, durata(brano.durataMs / 1000.0)).filter { it.isNotEmpty() }.joinToString(" · "),
                        color = Colori.Spento, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        val tempo = @Composable {
            Column(horizontalAlignment = if (b) Alignment.Start else Alignment.End) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(if (deck.bpm > 0) "%.1f".format(deck.bpmEffettivi) else "—", color = Colori.Testo, fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text(" BPM", color = Colori.Spento, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                }
                Text(
                    if (deck.brano != null) "-" + durata(deck.durata - deck.posizione) else "",
                    color = Colori.Spento, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace,
                )
            }
        }
        if (!b) {
            libreria(); lettera(); titolo(Modifier.weight(1f)); tempo()
        } else {
            tempo(); titolo(Modifier.weight(1f)); lettera(); libreria()
        }
    }
}

@Composable
private fun IconaLibreria(colore: androidx.compose.ui.graphics.Color) {
    Canvas(Modifier.size(15.dp)) {
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
