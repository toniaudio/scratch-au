package com.toniaudio.djaaay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Tutti i comandi assegnabili a un controllo MIDI. `continuo` = manopola/fader (valore 0…127), altrimenti tasto. */
enum class AzioneMidi(val nome: String, val perDeck: Boolean, val continuo: Boolean) {
    CROSSFADER("Crossfader", false, true),
    CURVA("Curva crossfader", false, false),
    MASTER("Master", false, true),
    VOLUME("Volume", true, true),
    GAIN("Gain", true, true),
    ALTI("EQ HI", true, true),
    MEDI("EQ MID", true, true),
    BASSI("EQ LOW", true, true),
    FILTRO("Filtro", true, true),
    PLAY("Play", true, false),
    CUE("Cue", true, false),
    SYNC("Sync", true, false),
    KEY("Key", true, false),
    DVS("DVS", true, false),
    PITCH("Pitch", true, true),
    HOT_CUE_1("Hot cue 1", true, false),
    HOT_CUE_2("Hot cue 2", true, false),
    HOT_CUE_3("Hot cue 3", true, false),
    HOT_CUE_4("Hot cue 4", true, false),
    LOOP("Loop on/off", true, false),
    LOOP_MEZZO("Loop ◀ (dimezza)", true, false),
    LOOP_DOPPIO("Loop ▶ (raddoppia)", true, false),
    FX_ON("FX on/off", true, false),
    FX_SUCCESSIVO("FX: effetto successivo", true, false),
    FX_ECHO("Pad FX Echo", true, false),
    FX_DUB("Pad FX Dub", true, false),
    FX_REVERB("Pad FX Reverb", true, false),
    FX_STRETCH("Pad FX Stretch", true, false),
    FX_DRY_WET("FX dry/wet", true, true),
    FX_PARAM1("FX manopola 1", true, true),
    FX_PARAM2("FX manopola 2", true, true),
    LIB_APRI("Libreria apri/chiudi", true, false),
    LIB_SU("Libreria su", true, false),
    LIB_GIU("Libreria giù", true, false),
    LIB_SCORRI("Libreria scorri (manopola)", true, true),
    LIB_CARICA("Libreria carica", true, false),
    CUFFIA("Cuffia (pre-ascolto)", true, false),
    MIX_CUFFIE("Cuffie CUE/MASTER", false, true),
    VOLUME_CUFFIE("Volume cuffie", false, true),
    CAMPIONE_1("Campionatore pad 1", false, false),
    CAMPIONE_2("Campionatore pad 2", false, false),
    CAMPIONE_3("Campionatore pad 3", false, false),
    CAMPIONE_4("Campionatore pad 4", false, false),
    CAMPIONE_5("Campionatore pad 5", false, false),
    CAMPIONE_6("Campionatore pad 6", false, false),
    CAMPIONE_7("Campionatore pad 7", false, false),
    CAMPIONE_8("Campionatore pad 8", false, false),
}

val AZIONI_CAMPIONE = listOf(
    AzioneMidi.CAMPIONE_1, AzioneMidi.CAMPIONE_2, AzioneMidi.CAMPIONE_3, AzioneMidi.CAMPIONE_4,
    AzioneMidi.CAMPIONE_5, AzioneMidi.CAMPIONE_6, AzioneMidi.CAMPIONE_7, AzioneMidi.CAMPIONE_8,
)

val AZIONI_HOT_CUE = listOf(AzioneMidi.HOT_CUE_1, AzioneMidi.HOT_CUE_2, AzioneMidi.HOT_CUE_3, AzioneMidi.HOT_CUE_4)
val AZIONI_PAD_FX = listOf(AzioneMidi.FX_ECHO, AzioneMidi.FX_DUB, AzioneMidi.FX_REVERB, AzioneMidi.FX_STRETCH)

/** Un controllo del controller: CC o nota, su un canale (0…15). */
data class SorgenteMidi(val nota: Boolean, val canale: Int, val numero: Int) {
    val testo: String get() = "${if (nota) "Nota" else "CC"} $numero · ch ${canale + 1}"
    fun codice() = "${if (nota) 'N' else 'C'}$canale.$numero"

    companion object {
        fun daCodice(s: String): SorgenteMidi? {
            if (s.length < 4) return null
            val parti = s.substring(1).split('.')
            val canale = parti.getOrNull(0)?.toIntOrNull() ?: return null
            val numero = parti.getOrNull(1)?.toIntOrNull() ?: return null
            if (canale !in 0..15 || numero !in 0..127) return null
            return when (s[0]) {
                'N' -> SorgenteMidi(true, canale, numero)
                'C' -> SorgenteMidi(false, canale, numero)
                else -> null
            }
        }
    }
}

data class Assegnazione(val sorgente: SorgenteMidi, val azione: AzioneMidi, val deck: Int) {
    val nomeComando: String get() = if (azione.perDeck) "${azione.nome} ${if (deck == 0) "A" else "B"}" else azione.nome
}

/** Un messaggio ricevuto: per una nota `valore` è la velocity (note off = 0). */
data class MessaggioMidi(val sorgente: SorgenteMidi, val valore: Int)

/**
 * Legge i byte MIDI che arrivano dal controller (anche a pezzi, con "running status") e restituisce
 * solo note e CC; il resto (clock, sysex, pitch bend…) viene saltato.
 */
class LettoreMidi {
    private var stato = 0
    private val dati = IntArray(2)
    private var quanti = 0
    private var sysex = false

    fun leggi(byte: ByteArray, inizio: Int, conta: Int, esito: (MessaggioMidi) -> Unit) {
        for (i in inizio until inizio + conta) {
            val b = byte[i].toInt() and 0xFF
            when {
                b >= 0xF8 -> Unit  // tempo reale (clock…): può arrivare in mezzo a un messaggio
                b == 0xF0 -> { sysex = true; stato = 0 }
                b == 0xF7 -> sysex = false
                b >= 0xF0 -> { stato = 0; sysex = false }  // messaggi di sistema: interrompono il running status
                b >= 0x80 -> { sysex = false; stato = b; quanti = 0 }
                sysex || stato == 0 -> Unit
                else -> {
                    dati[quanti++] = b
                    val servono = if ((stato and 0xF0) == 0xC0 || (stato and 0xF0) == 0xD0) 1 else 2
                    if (quanti == servono) {
                        quanti = 0
                        val canale = stato and 0x0F
                        when (stato and 0xF0) {
                            0x90 -> esito(MessaggioMidi(SorgenteMidi(true, canale, dati[0]), dati[1]))
                            0x80 -> esito(MessaggioMidi(SorgenteMidi(true, canale, dati[0]), 0))
                            0xB0 -> esito(MessaggioMidi(SorgenteMidi(false, canale, dati[0]), dati[1]))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Configurazione guidata per l'M-Audio X-Session Pro: si muove un controllo alla volta.
 * (Il manuale con i numeri CC non è disponibile qui: così la mappa viene giusta anche se il controller è stato
 * riprogrammato.) Ogni passo si può saltare.
 */
val PROCEDURA_XSESSION: List<Pair<AzioneMidi, Int>> = buildList {
    add(AzioneMidi.CROSSFADER to 0)
    for (d in 0..1) {
        add(AzioneMidi.VOLUME to d)
        add(AzioneMidi.GAIN to d)
        add(AzioneMidi.ALTI to d)
        add(AzioneMidi.MEDI to d)
        add(AzioneMidi.BASSI to d)
        add(AzioneMidi.FILTRO to d)
        add(AzioneMidi.PITCH to d)
        add(AzioneMidi.PLAY to d)
        add(AzioneMidi.CUE to d)
        add(AzioneMidi.SYNC to d)
        add(AzioneMidi.HOT_CUE_1 to d)
        add(AzioneMidi.HOT_CUE_2 to d)
        add(AzioneMidi.LOOP to d)
        add(AzioneMidi.FX_ON to d)
        add(AzioneMidi.FX_DRY_WET to d)
    }
    add(AzioneMidi.MASTER to 0)
}

class StatoMidi {
    var dispositivo by mutableStateOf<String?>(null)  // nome del controller collegato
    val assegnazioni = mutableStateListOf<Assegnazione>()
    var learn by mutableStateOf(false)                // LEARN sulla schermata principale
    var bersaglio by mutableStateOf<Pair<AzioneMidi, Int>?>(null)  // comando che aspetta un controllo
    var procedura by mutableStateOf<List<Pair<AzioneMidi, Int>>?>(null)
    var passo by mutableIntStateOf(0)
    var ultimo by mutableStateOf("")                  // ultimo messaggio ricevuto (per vedere che arriva)
}

fun nomeComando(azione: AzioneMidi, deck: Int) = Assegnazione(SorgenteMidi(false, 0, 0), azione, deck).nomeComando
