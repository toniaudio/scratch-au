package com.toniaudio.djaaay

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
    val asseY: Int,  // parametro sull'asse Y del trackpad: 1 o 2 (l'asse X è il dry/wet)
)

/** I quattro effetti, nello stesso ordine di TipoEffetto in engine/Effetti.h. */
val EFFETTI = listOf(
    // tempi in battiti: indici di kBattute = ⅛ ¼ ½ ¾ 1 2 4
    DescrizioneFx("ECHO", "ECHO", listOf("FEEDBACK"), "TEMPO (BATTUTE)", listOf("1/8", "1/4", "1/2", "1", "2"), listOf(0, 1, 2, 4, 5), 1),
    DescrizioneFx("DUB DELAY", "DUB", listOf("FEEDBACK", "TONO"), "TEMPO (BATTUTE)", listOf("1/4", "1/2", "3/4", "1", "2"), listOf(1, 2, 3, 4, 5), 1),
    DescrizioneFx("REVERB", "VERB", listOf("STANZA", "DAMP"), "CODA", listOf("corta", "media", "lunga"), listOf(0, 1, 2), 1),
    // ROLL: l'asse Y sceglie quanto si ripete (1/16 … 1 battito), la riga il verso
    DescrizioneFx("ROLL", "ROLL", listOf("LUNGHEZZA"), "VERSO", listOf("AVANTI", "INDIETRO"), listOf(0, 1), 1),
)

/** Testo sotto una manopola dell'effetto: `quale` 0 = DRY/WET, 1 e 2 = parametri. */
fun testoFx(tipo: Int, quale: Int, v: Float): String {
    val percento = "${(v * 100).roundToInt()}%"
    if (quale == 0) return percento
    return when (tipo to quale) {
        1 to 2 -> if (v < 0.35f) "scuro" else if (v > 0.65f) "brillante" else "medio"
        2 to 1 -> if (v < 0.35f) "piccola" else if (v > 0.65f) "grande" else "media"
        3 to 1 -> LUNGHEZZE_ROLL[(v * LUNGHEZZE_ROLL.size).toInt().coerceIn(0, LUNGHEZZE_ROLL.size - 1)]
        else -> percento
    }
}

/** Lunghezze del ROLL in battiti, come kBattitiRoll in engine/Effetti.h. */
val LUNGHEZZE_ROLL = listOf("1/16", "1/8", "1/4", "1/2", "1")

/** Effetti di un deck. Ogni tipo ricorda le sue regolazioni (indice 0 echo, 1 dub, 2 reverb, 3 roll). */
class StatoFx {
    var acceso by mutableStateOf(false)
    var tipo by mutableIntStateOf(0)
    val dryWet = mutableStateListOf(0.35f, 0.45f, 0.30f, 1.0f)
    val param1 = mutableStateListOf(0.4f, 0.7f, 0.7f, 0.5f)
    val param2 = mutableStateListOf(0f, 0.2f, 0.5f, 0f)
    val scelta = mutableStateListOf(1, 3, 2, 0)  // indice nel motore (vedi DescrizioneFx.indici)
    var hold by mutableStateOf(false)       // bloccato (secondo dito o tasto MIDI): resta acceso finché non si ritocca
    var toccato by mutableStateOf(false)    // un dito sul trackpad
    var spegniAlRilascio = false
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
    var tabPad by mutableIntStateOf(0)  // 0 hot cue, 1 loop
    var libreriaAperta by mutableStateOf(false)
    var libCerca by mutableStateOf("")
    var libScheda by mutableIntStateOf(0)  // 0 tutti, 1 cartelle, 2 playlist, 3 recenti
    var libCartella by mutableStateOf<String?>(null)
    var libSelezione by mutableIntStateOf(0)  // riga scelta col controller MIDI
    val fx = StatoFx()
    var dvs by mutableStateOf(false)  // il deck segue il vinile timecode

    /** Durata di una battuta in secondi del brano (120 BPM se il tempo non è noto). */
    val battitoSecondi: Double get() = 60.0 / (if (bpm > 0) bpm else 120.0)

    /** BPM che si sentono davvero (con il pitch). */
    val bpmEffettivi: Double get() = bpm * (1.0 + pitch)
}

/** I brani che la libreria del deck mostra adesso (scheda, cartella e ricerca); vuota per l'elenco delle cartelle. */
fun braniVisibili(app: StatoApp, deck: StatoDeck): List<Brano> {
    val filtro = deck.libCerca.trim()
    fun corrisponde(b: Brano) = filtro.isEmpty() || b.titolo.contains(filtro, true) || b.artista.contains(filtro, true) ||
        (app.bpmNoti[b.chiave]?.let { "%.0f".format(it).startsWith(filtro) } ?: false)
    return when (deck.libScheda) {
        0 -> app.libreria
        1 -> deck.libCartella?.let { c -> app.libreria.filter { it.cartella == c } } ?: emptyList()
        3 -> app.recenti.mapNotNull { id -> app.libreria.firstOrNull { it.id == id } }
        else -> emptyList()
    }.filter(::corrisponde)
}

class StatoCanale {
    var gain by mutableFloatStateOf(0.5f)
    var alti by mutableFloatStateOf(0.5f)
    var medi by mutableFloatStateOf(0.5f)
    var bassi by mutableFloatStateOf(0.5f)
    var filtro by mutableFloatStateOf(0f)  // −1 passa-basso … +1 passa-alto
    var volume by mutableFloatStateOf(0.8f)
    var cuffia by mutableStateOf(false)  // pre-ascolto
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
    var mixCuffie by mutableFloatStateOf(0f)  // 0 solo pre-ascolto … 1 solo master
    var volumeCuffie by mutableFloatStateOf(0.8f)
}

class Opzioni {
    var frizione by mutableFloatStateOf(0.35f)
    var avvio by mutableFloatStateOf(0.15f)
    var frenata by mutableFloatStateOf(0.40f)
    var sensibilita by mutableFloatStateOf(1.0f)  // 1 = un giro di dito = un giro di vinile a 33⅓
    var scratchWaveform by mutableStateOf(true)
    var escursione by mutableIntStateOf(8)  // pitch ±8, ±16, ±50 %
    var buffer by mutableIntStateOf(2)
    var unDeck by mutableStateOf(false)   // One Deck: deck A + campionatore al posto del deck B
    var cuffie by mutableStateOf(false)   // pre-ascolto sulla scheda a 4 uscite (scelta salvata)
}

/** Modi dei pad del campionatore (stesso ordine di ModoPad in engine/Campionatore.h). */
val MODI_PAD = listOf("UNA VOLTA", "LOOP", "TIENI")
val SIMBOLI_MODO = listOf("1×", "∞", "TIENI")

class StatoPad {
    var brano by mutableStateOf<Brano?>(null)
    var bpm by mutableDoubleStateOf(0.0)
    var modo by mutableIntStateOf(0)
    var suona by mutableStateOf(false)
    var avanzamento by mutableFloatStateOf(0f)
    var caricamento by mutableStateOf<Float?>(null)
}

class StatoCampionatore {
    val pad = List(8) { StatoPad() }
    var modifica by mutableStateOf(false)    // tocco su un pad = menu (cambia suono, modo, togli) invece di suonare
    var scegliPer by mutableStateOf<Int?>(null)  // pad per cui è aperta la libreria
    var menu by mutableStateOf<Int?>(null)       // pad di cui è aperto il menu
}

/** Una scheda audio con ingressi (per il DVS). */
data class SchedaAudio(val id: Int, val nome: String, val canali: Int, val usb: Boolean)

val TIPI_TIMECODE = listOf("Serato CV02", "Traktor", "MixVibes")

class StatoDvs {
    var schede by mutableStateOf<List<SchedaAudio>>(emptyList())
    var scheda by mutableStateOf<SchedaAudio?>(null)  // quella aperta (o da aprire)
    var aperto by mutableStateOf(false)
    var coppia by mutableIntStateOf(0)  // 0 ingressi 1-2, 1 ingressi 3-4
    var guadagnoDb by mutableFloatStateOf(0f)
    var tipo by mutableIntStateOf(0)
    var giri45 by mutableStateOf(false)
    var assoluta by mutableStateOf(false)
    var sensibilita by mutableFloatStateOf(0.45f)
    var protezione by mutableStateOf(true)
    // letture dal motore
    var qualita by mutableFloatStateOf(0f)
    var velocita by mutableFloatStateOf(0f)
    var posizione by mutableDoubleStateOf(-1.0)
    var livello by mutableFloatStateOf(0f)
    var calibrato by mutableStateOf(false)
    var invertiti by mutableStateOf(false)
    val scope = FloatArray(2 * PUNTI_SCOPE)
    var versioneScope by mutableIntStateOf(0)  // cambia a ogni lettura: lo scope si ridisegna

    companion object {
        const val PUNTI_SCOPE = 256
    }
}

class StatoApp {
    var vista by mutableIntStateOf(0)  // telefono: 0 DECK, 1 MIXER, 2 PAD, 3 FX
    val campionatore = StatoCampionatore()
    var cuffieAttive by mutableStateOf(false)  // uscita aperta a 4 canali sulla scheda
    val midi = StatoMidi()
    val dvs = StatoDvs()
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
