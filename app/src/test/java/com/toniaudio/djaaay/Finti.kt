package com.toniaudio.djaaay

import android.net.Uri
import kotlin.math.exp
import kotlin.math.sin

/** Ponte finto: ricorda i comandi e restituisce uno stato fisso. */
class PonteFinto : Ponte {
    data class Comando(val deck: Int, val tipo: Int, val a: Double, val b: Double)

    val comandi = mutableListOf<Comando>()
    val deck = mutableMapOf<Pair<Int, Int>, Float>()
    val mixer = mutableMapOf<Pair<Int, Int>, Float>()
    val istantanea = DoubleArray(20)

    override fun comando(deck: Int, tipo: Int, a: Double, b: Double) {
        comandi += Comando(deck, tipo, a, b)
    }

    override fun impostaDeck(deck: Int, parametro: Int, valore: Float) {
        this.deck[deck to parametro] = valore
    }

    override fun impostaMixer(parametro: Int, canale: Int, valore: Float) {
        mixer[parametro to canale] = valore
    }

    override fun stato(dest: DoubleArray) = istantanea.copyInto(dest)
}

class PiattaformaFinta(var brani: List<Brano> = emptyList()) : Piattaforma {
    val preferenze = mutableMapOf<String, String>()
    var caricati = mutableListOf<Pair<Long, Int>>()
    var rec = false

    override fun haPermessoLibreria() = true
    override fun chiediPermessoLibreria() {}
    override suspend fun leggiLibreria() = brani
    override suspend fun carica(brano: Brano, progresso: (Float) -> Unit): BranoCaricato {
        progresso(0.5f)
        return BranoCaricato(brano.id, 98.0, 0.37, brano.durataMs / 1000.0, waveformFinta(brano.durataMs / 1000.0, 98.0, 0.37, 0))
    }
    override fun mettiNelDeck(caricato: BranoCaricato, deck: Int) {
        caricati += caricato.handle to deck
    }
    override fun scarta(caricato: BranoCaricato) {}
    override fun avviaRec(): String? = "DVS-prova.wav".also { rec = true }
    override fun fermaRec() {
        rec = false
    }
    override fun impostaBuffer(multiplo: Int) {}
    override fun diagnostica() = "Frequenza: 48000 Hz\nBuffer: 192 frame (2 × 96) = 4,0 ms\nRitardo totale stimato: 21 ms\n" +
        "Interruzioni del suono: 0\nCarico audio: 6% (picco 11%)\nModo: AAudio, esclusivo\nKeylock deck A: spento\nREC: spenta"
    override fun leggiPreferenza(chiave: String) = preferenze[chiave]
    override fun salvaPreferenza(chiave: String, valore: String) {
        preferenze[chiave] = valore
    }
}

fun branoFinto(id: Long, titolo: String, artista: String, secondi: Int, cartella: String = "Music") = Brano(
    id, Uri.parse("content://media/external/audio/media/$id"), titolo, artista, secondi * 1000L, cartella, 1_000_000L + id, 1_700_000_000L,
)

/** Waveform finta con cassa, rullante e charleston a tempo, simile a quella vera (stessi livelli di zoom). */
fun waveformFinta(secondi: Double, bpm: Double, primo: Double, seme: Int, frequenza: Int = 44100): Waveform {
    val colonne = (secondi * frequenza / 64).toInt()
    val base = ByteArray(colonne * 3)
    val battito = 60.0 / bpm
    var caso = 12345 + seme * 777
    fun rnd(): Double {
        caso = caso * 1103515245 + 12345
        return ((caso ushr 8) and 0xFFFF) / 65535.0
    }
    for (c in 0 until colonne) {
        val t = c * 64.0 / frequenza
        val fase = ((t - primo) % battito + battito) % battito
        val mezzo = ((t - primo) % (battito / 2) + battito / 2) % (battito / 2)
        val cassa = exp(-fase * 18)
        val charleston = exp(-mezzo * 40) * 0.5
        val sezione = 0.75 + 0.25 * sin(t / 7.0)
        val b = (0.25 + 0.75 * cassa) * (0.8 + 0.2 * rnd()) * sezione
        val m = (0.3 + 0.35 * rnd()) * (0.7 + 0.3 * sin(t * 1.3)) * sezione
        val a = (0.15 + charleston) * (0.6 + 0.4 * rnd()) * sezione
        base[c] = (kotlin.math.sqrt(b.coerceIn(0.0, 1.0)) * 255).toInt().toByte()
        base[colonne + c] = (kotlin.math.sqrt(m.coerceIn(0.0, 1.0)) * 255).toInt().toByte()
        base[2 * colonne + c] = (kotlin.math.sqrt(a.coerceIn(0.0, 1.0)) * 255).toInt().toByte()
    }
    val livelli = mutableListOf(base)
    while (livelli.last().size / 3 > 256) {
        val prima = livelli.last()
        val n = prima.size / 3
        val m = (n + 1) / 2
        val l = ByteArray(m * 3)
        for (banda in 0 until 3) for (i in 0 until m) {
            val x = prima[banda * n + 2 * i].toInt() and 0xFF
            val y = prima[banda * n + minOf(2 * i + 1, n - 1)].toInt() and 0xFF
            l[banda * m + i] = maxOf(x, y).toByte()
        }
        livelli += l
    }
    return Waveform(livelli, frequenza)
}
