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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.toniaudio.djaaay.Brano
import com.toniaudio.djaaay.Controllore
import com.toniaudio.djaaay.StatoApp

/** Libreria aperta da un deck, dentro lo spazio di quel deck: un tocco su un brano lo carica lì. */
@Composable
fun LibreriaDeck(app: StatoApp, d: Int, c: Controllore, modifier: Modifier = Modifier) {
    val deck = app.deck[d]
    var cerca by remember { mutableStateOf("") }
    var scheda by remember { mutableIntStateOf(0) }  // 0 tutti, 1 cartelle, 2 playlist, 3 recenti
    var cartella by remember { mutableStateOf<String?>(null) }

    Column(
        modifier
            .background(Color(0xFF10131B), RoundedCornerShape(10.dp))
            .border(1.dp, Colori.Linea, RoundedCornerShape(10.dp))
            .pointerInput(Unit) { detectTapGestures { } },  // i tocchi non passano al disco sotto
    ) {
        Row(Modifier.padding(start = 7.dp, end = 7.dp, top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .weight(1f)
                    .height(26.dp)
                    .background(Colori.Pannello, RoundedCornerShape(7.dp))
                    .border(1.dp, Colori.Linea, RoundedCornerShape(7.dp))
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (cerca.isEmpty()) Text("Cerca nel deck ${deck.lettera}…", color = Colori.Spento, fontSize = 10.sp)
                BasicTextField(
                    cerca, { cerca = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Colori.Testo, fontSize = 11.sp),
                    cursorBrush = SolidColor(Colori.Testo),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier.size(26.dp).background(Colori.Pannello2, RoundedCornerShape(6.dp))
                    .pointerInput(Unit) { detectTapGestures { c.chiudiLibreria(d) } },
                contentAlignment = Alignment.Center,
            ) { Text("✕", color = Colori.Spento, fontSize = 12.sp) }
        }
        Schede(
            listOf("Tutti", "Cartelle", "Playlist", "Recenti"), scheda,
            { scheda = it; cartella = null },
            Modifier.padding(horizontal = 7.dp).fillMaxWidth(),
        )
        Spacer(Modifier.height(3.dp))

        if (!app.permessoLibreria) {
            Avviso("DVS ha bisogno del permesso per leggere la musica del telefono.", "Consenti") { c.chiediPermessoLibreria() }
            return@Column
        }
        val filtro = cerca.trim()
        fun corrisponde(b: Brano) = filtro.isEmpty() || b.titolo.contains(filtro, true) || b.artista.contains(filtro, true) ||
            (app.bpmNoti[b.chiave]?.let { "%.0f".format(it).startsWith(filtro) } ?: false)

        Box(Modifier.weight(1f)) {
            when {
                app.libreria.isEmpty() -> Avviso("Nessun brano trovato sul telefono.\nCopia dei file musicali (mp3, wav, m4a, flac…) nella memoria del telefono.")
                scheda == 2 -> Avviso("Le playlist arriveranno in un prossimo aggiornamento.")
                scheda == 1 && cartella == null -> {
                    val cartelle = app.libreria.groupBy { it.cartella }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(cartelle.keys.toList()) { nome ->
                            RigaCartella(nome.ifEmpty { "Memoria del telefono" }, cartelle[nome]?.size ?: 0) { cartella = nome }
                        }
                    }
                }
                else -> {
                    val brani = when (scheda) {
                        1 -> app.libreria.filter { it.cartella == cartella }
                        3 -> app.recenti.mapNotNull { id -> app.libreria.firstOrNull { it.id == id } }
                        else -> app.libreria
                    }.filter(::corrisponde)
                    LazyColumn(Modifier.fillMaxSize()) {
                        if (scheda == 1) item { RigaCartella("◂ ${cartella!!.ifEmpty { "Memoria del telefono" }}", brani.size) { cartella = null } }
                        items(brani, key = { it.id }) { b ->
                            RigaBrano(b, app.bpmNoti[b.chiave], caricato = deck.brano?.id == b.id) { c.caricaBrano(d, b) }
                        }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().background(Colori.Barra).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("tocca un brano: si carica nel deck ${deck.lettera}", color = Colori.Spento, fontSize = 8.5.sp)
            Text("${app.libreria.size} brani", color = Colori.Spento, fontSize = 8.5.sp)
        }
    }
}

@Composable
private fun RigaBrano(b: Brano, bpm: Double?, caricato: Boolean, onTocco: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(30.dp)
            .background(if (caricato) Color.White.copy(alpha = 0.04f) else Color.Transparent)
            .pointerInput(b.id) { detectTapGestures { onTocco() } }
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val colore = if (caricato) Colori.Spento else Colori.Testo
        Column(Modifier.weight(1f)) {
            Text(b.titolo, color = colore, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (b.artista.isNotEmpty()) Text(b.artista, color = Colori.Spento, fontSize = 8.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(bpm?.let { "%.1f".format(it) } ?: "—", color = colore, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(40.dp), textAlign = TextAlign.End)
        Text(durata(b.durataMs / 1000.0), color = Colori.Spento, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(36.dp), textAlign = TextAlign.End)
    }
}

@Composable
private fun RigaCartella(nome: String, quanti: Int, onTocco: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(28.dp).pointerInput(nome) { detectTapGestures { onTocco() } }.padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(nome, color = Colori.Testo, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text("$quanti", color = Colori.Spento, fontSize = 9.sp)
    }
}

@Composable
private fun Avviso(testo: String, pulsante: String? = null, onPulsante: () -> Unit = {}) {
    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(testo, color = Colori.Spento, fontSize = 10.5.sp, textAlign = TextAlign.Center)
        if (pulsante != null) {
            Spacer(Modifier.height(10.dp))
            Tasto(Modifier.height(28.dp).width(110.dp), acceso = true, onTocco = onPulsante) {
                Text(pulsante, color = Colori.Sfondo, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
