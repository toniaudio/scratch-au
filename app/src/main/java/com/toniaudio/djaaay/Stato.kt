package com.toniaudio.djaaay

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlin.math.roundToInt

/** Un brano della libreria del telefono. */
data class Brano(
    val id: Long,
    val uri: Uri,
    val titolo: String,
    val artista: String,
    val durataMs: Long,
    val cartella: String,
    val dimensione: Long,
    val modificato: Long,
) {
    /** Identifica il file così com'è: se viene modificato l'analisi si rifà. */
    val chiave: String get() = "$id-$dimensione-$modificato"
}

/** Waveform di un brano: per livello di zoom [bassi…, medi…, alti…] (0…255), colonne di 64·2^livello frame. */
class Waveform(val livelli: List<ByteArray>, val frequenza: Int) {
    fun colonne(livello: Int) = livelli[livello].size / 3
    fun framePerColonna(livello: Int) = 64 shl livello
}

/** Lunghezze del loop in battute, scelte con le frecce. */
val LUNGHEZZE_LOOP = doubleArrayOf(0.125, 0.25, 0.5, 1.0, 2.0, 4.0, 8.0, 16.0, 32.0)

fun etichettaLoop(battute: Double): String = when (battute) {
    0.125 -> "1/8"
    0.25 -> "1/4"
    0.5 -> "1/2"
    else -> battute.toInt().toString()
}

/** Come si presenta un effetto: nome, manopole oltre a DRY/WET, riga in basso (voci e indice nel motore). */
class DescrizioneFx(
    val nome: String,
    val corto: String,
    val parametri: List<String>,
    val titoloRiga: String,
    val voci: List<String>,
    val indici: List<Int>,
)

/** I quattro effetti, nello stesso ordine di TipoEffetto in engine/Effetti.h. */
val EFFETTI = listOf(
    // tempi in battiti: indici di kBattute = ⅛ ¼ ½ ¾ 1 2 4
    DescrizioneFx("ECHO", "ECHO", listOf("FEEDBACK"), "TEMPO (BATTUTE)", listOf("1/8", "1/4", "1/2", "1", "2"), listOf(0, 1, 2, 4, 5)),
    DescrizioneFx("DUB DELAY", "DUB", listOf("FEEDBACK", "TONO"), "TEMPO (BATTUTE)", listOf("1/4", "1/2", "3/4", "1", "2"), listOf(1, 2, 3, 4, 5)),
    DescrizioneFx("REVERB", "VERB", listOf("STANZA", "DAMP"), "CODA", listOf("corta", "media", "lunga"), listOf(0, 1, 2)),
    DescrizioneFx("STRETCH", "STRETCH", listOf("GRANA", "TONO"), "RALLENTA · TONO FISSO", listOf("½×", "¼×", "⅛×", "FREEZE"), listOf(0, 1, 2, 3)),
)

/** Testo sotto una manopola dell'effetto: `quale` 0 = DRY/WET, 1 e 2 = parametri. */
fun testoFx(tipo: Int, quale: Int, v: Float): String {
    val percento = "${(v * 100).roundToInt()}%"
    if (quale == 0) return percento
    return when (tipo to quale) {
        1 to 2 -> if (v < 0.35f) "scuro" else if (v > 0.65f) "brillante" else "medio"
        2 to 1 -> if (v < 0.35f) "piccola" else if (v > 0.65f) "grande" else "media"
        3 to 1 -> "${(10 + 110 * v).roundToInt()} ms"
        3 to 2 -> if (abs(v - 0.5f) < 0.03f) "fisso" else "%+d st".format(((v - 0.5f) * 24).roundToInt())
        else -> percento
    }
}

/** Effetti di un deck. Ogni tipo ricorda le sue regolazioni (indice 0 echo, 1 dub, 2 reverb, 3 stretch). */
class StatoFx {
    var acceso by mutableStateOf(false)
    var tipo by mutableIntStateOf(0)
    val dryWet = mutableStateListOf(0.35f, 0.45f, 0.30f, 1.0f)
    val param1 = mutableStateListOf(0.4f, 0.7f, 0.7f, 0.3f)
    val param2 = mutableStateListOf(0f, 0.2f, 0.5f, 0.5f)
    val scelta = mutableStateListOf(1, 3, 2, 0)  // indice nel motore (vedi DescrizioneFx.indici)
    var momentaneo = false  // acceso tenendo premuto il pad: si spegne al rilascio
}

class StatoDeck(val indice: Int) {
    val lettera: String get() = if (indice == 0) "A" else "B"

    // brano
    var brano by mutableStateOf<Brano?>(null)
    var waveform by mutableStateOf<Waveform?>(null)
    var bpm by mutableDoubleStateOf(0.0)
    var primoBattito by mutableDoubleStateOf(0.0)
    var caricamento by mutableStateOf<Float?>(null)  // 0…1 mentre carica, null altrimenti

    // dal motore, a ogni fotogramma
    var posizione by mutableDoubleStateOf(0.0)
    var durata by mutableDoubleStateOf(0.0)
    var velocita by mutableDoubleStateOf(0.0)
    var inPlay by mutableStateOf(false)
    var cue by mutableDoubleStateOf(0.0)
    var keylockInUso by mutableStateOf(false)
    var livello by mutableFloatStateOf(0f)

    // comandi
    var pitch by mutableFloatStateOf(0f)  // −0,5…+0,5
    var keylock by mutableStateOf(false)
    val hotCue = mutableStateListOf<Double?>(null, null, null, null)
    var loopAttivo by mutableStateOf(false)
    var loopInizio by mutableDoubleStateOf(0.0)
    var loopIndice by mutableIntStateOf(5)  // 4 battute
    var tabPad by mutableIntStateOf(0)  // 0 hot cue, 1 loop, 2 fx
    var libreriaAperta by mutableStateOf(false)
    val fx = StatoFx()

    /** Durata di una battuta in secondi del brano (120 BPM se il tempo non è noto). */
    val battitoSecondi: Double get() = 60.0 / (if (bpm > 0) bpm else 120.0)

    /** BPM che si sentono davvero (con il pitch). */
    val bpmEffettivi: Double get() = bpm * (1.0 + pitch)
}

class StatoCanale {
    var gain by mutableFloatStateOf(0.5f)
    var alti by mutableFloatStateOf(0.5f)
    var medi by mutableFloatStateOf(0.5f)
    var bassi by mutableFloatStateOf(0.5f)
    var filtro by mutableFloatStateOf(0f)  // −1 passa-basso … +1 passa-alto
    var volume by mutableFloatStateOf(0.8f)
}

class StatoMixer {
    val canale = listOf(StatoCanale(), StatoCanale())
    var crossfader by mutableFloatStateOf(0.5f)
    var curva by mutableIntStateOf(0)  // 0 taglio, 1 lineare, 2 morbida
    var inverti by mutableStateOf(false)
    var master by mutableFloatStateOf(0.8f)
    var livelloL by mutableFloatStateOf(0f)
    var livelloR by mutableFloatStateOf(0f)
    var rec by mutableStateOf(false)
    var recSecondi by mutableDoubleStateOf(0.0)
    var tab by mutableIntStateOf(0)  // 0 mixer, 1 fx
}

class Opzioni {
    var frizione by mutableFloatStateOf(0.35f)
    var avvio by mutableFloatStateOf(0.15f)
    var frenata by mutableFloatStateOf(0.40f)
    var sensibilita by mutableFloatStateOf(1.0f)  // 1 = un giro di dito = un giro di vinile a 33⅓
    var scratchWaveform by mutableStateOf(true)
    var escursione by mutableIntStateOf(8)  // pitch ±8, ±16, ±50 %
    var buffer by mutableIntStateOf(2)
}

class StatoApp {
    val deck = listOf(StatoDeck(0), StatoDeck(1))
    val mixer = StatoMixer()
    val opzioni = Opzioni()
    var opzioniAperte by mutableStateOf(false)
    var schedaOpzioni by mutableIntStateOf(0)  // 0 generale, 1 audio, 2 midi, 3 dvs
    var zoomSecondi by mutableFloatStateOf(6f)  // secondi visibili nelle waveform
    var messaggio by mutableStateOf<String?>(null)
    var diagnostica by mutableStateOf("")

    var libreria by mutableStateOf<List<Brano>>(emptyList())
    var permessoLibreria by mutableStateOf(false)
    var bpmNoti by mutableStateOf<Map<String, Double>>(emptyMap())  // chiave del brano → BPM già analizzati
    var recenti by mutableStateOf<List<Long>>(emptyList())  // id dei brani caricati, il più recente prima
}
