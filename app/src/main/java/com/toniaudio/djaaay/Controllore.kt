package com.toniaudio.djaaay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor

/** Brano decodificato e analizzato, pronto per un deck (handle nativo). */
class BranoCaricato(
    val handle: Long,
    val bpm: Double,
    val primoBattito: Double,
    val durata: Double,
    val waveform: Waveform,
)

/** Le cose che dipendono da Android (in app: PiattaformaAndroid; nei test: una finta). */
interface Piattaforma {
    fun haPermessoLibreria(): Boolean
    fun chiediPermessoLibreria()
    suspend fun leggiLibreria(): List<Brano>
    suspend fun carica(brano: Brano, progresso: (Float) -> Unit): BranoCaricato
    fun mettiNelDeck(caricato: BranoCaricato, deck: Int)
    fun scarta(caricato: BranoCaricato)
    fun avviaRec(): String?
    fun fermaRec()
    fun impostaBuffer(multiplo: Int)
    fun diagnostica(): String
    fun leggiPreferenza(chiave: String): String?
    fun salvaPreferenza(chiave: String, valore: String)
}

/**
 * La logica dei comandi della schermata: aggiorna lo stato e lo traduce in comandi per il motore.
 * Non tocca mai direttamente Android né il C++ (passa da [Ponte] e [Piattaforma]).
 */
class Controllore(
    val stato: StatoApp,
    private val ponte: Ponte,
    private val piattaforma: Piattaforma,
    private val scope: CoroutineScope,
) {
    private val istantanea = DoubleArray(20)
    private val caricamenti = arrayOfNulls<Job>(2)

    init {
        caricaOpzioni()
        applicaOpzioni()
        stato.permessoLibreria = piattaforma.haPermessoLibreria()
        stato.recenti = piattaforma.leggiPreferenza("recenti")?.split(',')?.mapNotNull { it.toLongOrNull() } ?: emptyList()
        stato.bpmNoti = piattaforma.leggiPreferenza("bpm")?.split(';')?.mapNotNull { voce ->
            val parti = voce.split('=')
            if (parti.size == 2) parti[1].toDoubleOrNull()?.let { parti[0] to it } else null
        }?.toMap() ?: emptyMap()
    }

    // ------------------------------------------------------------------ fotogramma
    /** A ogni fotogramma: legge posizione, velocità e livelli dal motore. */
    fun aggiornaDaMotore() {
        ponte.stato(istantanea)
        for (d in stato.deck) {
            val o = d.indice * 8
            d.posizione = istantanea[o]
            d.durata = istantanea[o + 1]
            d.velocita = istantanea[o + 2]
            d.inPlay = istantanea[o + 3] > 0.5
            d.cue = istantanea[o + 4]
            d.keylockInUso = istantanea[o + 5] > 0.5
            d.livello = istantanea[o + 6].toFloat()
        }
        stato.mixer.livelloL = istantanea[16].toFloat()
        stato.mixer.livelloR = istantanea[17].toFloat()
        stato.mixer.rec = istantanea[18] > 0.5
        stato.mixer.recSecondi = istantanea[19]
    }

    fun mostra(testo: String) {
        stato.messaggio = testo
    }

    // ------------------------------------------------------------------ trasporto
    fun playPausa(d: Int) {
        if (stato.deck[d].brano == null) return mostra("Carica prima un brano nel deck ${stato.deck[d].lettera}")
        ponte.comando(d, Cmd.PLAY_PAUSA)
    }

    fun cueGiu(d: Int) = ponte.comando(d, Cmd.CUE_GIU)
    fun cueSu(d: Int) = ponte.comando(d, Cmd.CUE_SU)

    fun toccoInizio(d: Int) = ponte.comando(d, Cmd.TOCCO_INIZIO)
    /** `secondi` = spostamento del brano dal punto in cui il dito si è appoggiato. */
    fun toccoSposta(d: Int, secondi: Double) = ponte.comando(d, Cmd.TOCCO_SPOSTA, secondi)
    fun toccoFine(d: Int) = ponte.comando(d, Cmd.TOCCO_FINE)

    fun vai(d: Int, secondi: Double) = ponte.comando(d, Cmd.VAI, secondi)

    /** SYNC: stesso tempo dell'altro deck e battute allineate. */
    fun sync(d: Int) {
        val questo = stato.deck[d]
        val altro = stato.deck[1 - d]
        if (questo.bpm <= 0 || altro.bpm <= 0) return mostra("SYNC: serve il BPM di entrambi i brani")
        val nuovoPitch = (altro.bpmEffettivi / questo.bpm - 1.0).toFloat()
        val limite = stato.opzioni.escursione / 100f
        if (abs(nuovoPitch) > limite + 1e-4f) {
            return mostra("SYNC: servirebbe un pitch del ${"%+.1f".format(nuovoPitch * 100)}%, oltre ±${stato.opzioni.escursione}% (Opzioni › Pitch)")
        }
        impostaPitch(d, nuovoPitch)
        if (altro.inPlay) {
            // fase: stessa frazione di battuta dell'altro deck
            val faseAltro = frazioneBattuta(altro.posizione, altro.primoBattito, altro.battitoSecondi)
            val faseQuesto = frazioneBattuta(questo.posizione, questo.primoBattito, questo.battitoSecondi)
            var diff = faseAltro - faseQuesto
            if (diff > 0.5) diff -= 1.0
            if (diff < -0.5) diff += 1.0
            if (abs(diff) > 0.005) vai(d, questo.posizione + diff * questo.battitoSecondi)
        }
    }

    // ------------------------------------------------------------------ pitch e keylock
    fun impostaPitch(d: Int, valore: Float) {
        val limite = stato.opzioni.escursione / 100f
        val v = valore.coerceIn(-limite, limite)
        stato.deck[d].pitch = v
        ponte.impostaDeck(d, ParDeck.PITCH, v)
    }

    fun keylock(d: Int) {
        val deck = stato.deck[d]
        deck.keylock = !deck.keylock
        ponte.impostaDeck(d, ParDeck.KEYLOCK, if (deck.keylock) 1f else 0f)
    }

    fun dvs(d: Int) = mostra("DVS: arriverà con la scheda audio USB e il vinile timecode")

    // ------------------------------------------------------------------ hot cue e loop
    /** Tocco su un hot cue: vuoto → lo fissa qui; pieno → ci salta. */
    fun hotCue(d: Int, i: Int) {
        val deck = stato.deck[d]
        if (deck.brano == null) return
        val punto = deck.hotCue[i]
        if (punto == null) deck.hotCue[i] = deck.posizione else vai(d, punto)
    }

    fun cancellaHotCue(d: Int, i: Int) {
        stato.deck[d].hotCue[i] = null
    }

    fun loopTocco(d: Int) {
        val deck = stato.deck[d]
        if (deck.brano == null) return
        if (deck.loopAttivo) {
            deck.loopAttivo = false
            ponte.comando(d, Cmd.LOOP, 0.0, 0.0)
            return
        }
        // il loop parte dalla battuta in cui si è (o da qui se il tempo non è noto)
        val battito = deck.battitoSecondi
        val inizio = if (deck.bpm > 0) {
            deck.primoBattito + floor((deck.posizione - deck.primoBattito) / battito + 1e-6) * battito
        } else {
            deck.posizione
        }
        deck.loopInizio = inizio
        deck.loopAttivo = true
        ponte.comando(d, Cmd.LOOP, inizio, inizio + LUNGHEZZE_LOOP[deck.loopIndice] * battito)
    }

    /** Frecce del loop: −1 dimezza, +1 raddoppia (con il loop acceso cambia subito). */
    fun loopLunghezza(d: Int, verso: Int) {
        val deck = stato.deck[d]
        deck.loopIndice = (deck.loopIndice + verso).coerceIn(0, LUNGHEZZE_LOOP.size - 1)
        if (deck.loopAttivo) {
            ponte.comando(d, Cmd.LOOP, deck.loopInizio, deck.loopInizio + LUNGHEZZE_LOOP[deck.loopIndice] * deck.battitoSecondi)
        }
    }

    fun effetto(nome: String) = mostra("Effetti ($nome): in arrivo nel prossimo aggiornamento")

    // ------------------------------------------------------------------ mixer
    fun mixer(parametro: Int, canale: Int, valore: Float) {
        val c = stato.mixer.canale[canale]
        when (parametro) {
            ParMixer.GAIN -> c.gain = valore
            ParMixer.ALTI -> c.alti = valore
            ParMixer.MEDI -> c.medi = valore
            ParMixer.BASSI -> c.bassi = valore
            ParMixer.FILTRO -> c.filtro = valore
            ParMixer.VOLUME -> c.volume = valore
        }
        ponte.impostaMixer(parametro, canale, valore)
    }

    fun crossfader(valore: Float) {
        stato.mixer.crossfader = valore.coerceIn(0f, 1f)
        ponte.impostaMixer(ParMixer.CROSSFADER, 0, stato.mixer.crossfader)
    }

    fun curva(valore: Int) {
        stato.mixer.curva = valore.mod(3)
        ponte.impostaMixer(ParMixer.CURVA, 0, stato.mixer.curva.toFloat())
        salvaOpzioni()
    }

    fun curvaSuccessiva() = curva(stato.mixer.curva + 1)

    fun inverti(acceso: Boolean) {
        stato.mixer.inverti = acceso
        ponte.impostaMixer(ParMixer.INVERTI, 0, if (acceso) 1f else 0f)
        salvaOpzioni()
    }

    fun master(valore: Float) {
        stato.mixer.master = valore.coerceIn(0f, 1f)
        ponte.impostaMixer(ParMixer.MASTER, 0, stato.mixer.master)
    }

    fun rec() {
        if (stato.mixer.rec) {
            piattaforma.fermaRec()
            stato.mixer.rec = false
            mostra("Registrazione salvata in Musica/DVS")
        } else {
            val nome = piattaforma.avviaRec()
            if (nome == null) mostra("REC: non riesco a creare il file") else {
                stato.mixer.rec = true
                mostra("REC: registro in Musica/DVS/$nome")
            }
        }
    }

    // ------------------------------------------------------------------ libreria
    fun apriLibreria(d: Int) {
        val deck = stato.deck[d]
        deck.libreriaAperta = !deck.libreriaAperta
        if (deck.libreriaAperta) {
            if (!piattaforma.haPermessoLibreria()) piattaforma.chiediPermessoLibreria() else aggiornaLibreria()
        }
    }

    fun chiediPermessoLibreria() = piattaforma.chiediPermessoLibreria()

    fun chiudiLibreria(d: Int) {
        stato.deck[d].libreriaAperta = false
    }

    /** Dopo il permesso, o a ogni apertura: rilegge i brani del telefono. */
    fun aggiornaLibreria() {
        stato.permessoLibreria = piattaforma.haPermessoLibreria()
        if (!stato.permessoLibreria) return
        scope.launch { stato.libreria = piattaforma.leggiLibreria() }
    }

    fun caricaBrano(d: Int, brano: Brano) {
        val deck = stato.deck[d]
        if (deck.inPlay) return mostra("Il deck ${deck.lettera} sta suonando: fermalo prima di caricare un altro brano")
        caricamenti[d]?.cancel()
        deck.libreriaAperta = false
        deck.caricamento = 0f
        caricamenti[d] = scope.launch {
            val caricato = try {
                piattaforma.carica(brano) { deck.caricamento = it }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                deck.caricamento = null
                return@launch mostra("Non riesco a leggere \"${brano.titolo}\" (${e.message ?: "formato non supportato"})")
            }
            piattaforma.mettiNelDeck(caricato, d)
            deck.brano = brano
            deck.waveform = caricato.waveform
            deck.bpm = caricato.bpm
            deck.primoBattito = caricato.primoBattito
            deck.durata = caricato.durata
            deck.posizione = 0.0
            deck.hotCue.indices.forEach { deck.hotCue[it] = null }
            deck.loopAttivo = false
            deck.caricamento = null
            ricordaBrano(brano, caricato.bpm)
        }
    }

    private fun ricordaBrano(brano: Brano, bpm: Double) {
        stato.recenti = (listOf(brano.id) + stato.recenti.filter { it != brano.id }).take(50)
        piattaforma.salvaPreferenza("recenti", stato.recenti.joinToString(","))
        if (bpm > 0) {
            stato.bpmNoti = stato.bpmNoti + (brano.chiave to bpm)
            piattaforma.salvaPreferenza("bpm", stato.bpmNoti.entries.joinToString(";") { "${it.key}=${it.value}" })
        }
    }

    // ------------------------------------------------------------------ opzioni
    fun apriOpzioni() {
        stato.opzioniAperte = true
    }

    fun chiudiOpzioni() {
        stato.opzioniAperte = false
        salvaOpzioni()
    }

    fun aggiornaDiagnostica() {
        stato.diagnostica = piattaforma.diagnostica()
    }

    fun impostaBuffer(multiplo: Int) {
        stato.opzioni.buffer = multiplo
        piattaforma.impostaBuffer(multiplo)
        salvaOpzioni()
    }

    fun escursione(percento: Int) {
        stato.opzioni.escursione = percento
        for (d in stato.deck) impostaPitch(d.indice, d.pitch)  // riporta il pitch entro la nuova escursione
        salvaOpzioni()
    }

    /** Dopo aver cambiato frizione, avvio, frenata: li manda ai due deck. */
    fun applicaOpzioni() {
        val o = stato.opzioni
        for (d in 0..1) {
            ponte.impostaDeck(d, ParDeck.FRIZIONE, o.frizione)
            ponte.impostaDeck(d, ParDeck.AVVIO, o.avvio)
            ponte.impostaDeck(d, ParDeck.FRENATA, o.frenata)
        }
        ponte.impostaMixer(ParMixer.CURVA, 0, stato.mixer.curva.toFloat())
        ponte.impostaMixer(ParMixer.INVERTI, 0, if (stato.mixer.inverti) 1f else 0f)
    }

    fun salvaOpzioni() {
        val o = stato.opzioni
        piattaforma.salvaPreferenza(
            "opzioni",
            listOf(o.frizione, o.avvio, o.frenata, o.sensibilita, if (o.scratchWaveform) 1f else 0f, o.escursione.toFloat(),
                o.buffer.toFloat(), stato.mixer.curva.toFloat(), if (stato.mixer.inverti) 1f else 0f).joinToString(","),
        )
    }

    private fun caricaOpzioni() {
        val v = piattaforma.leggiPreferenza("opzioni")?.split(',')?.mapNotNull { it.toFloatOrNull() } ?: return
        if (v.size < 9) return
        val o = stato.opzioni
        o.frizione = v[0]
        o.avvio = v[1]
        o.frenata = v[2]
        o.sensibilita = v[3]
        o.scratchWaveform = v[4] > 0.5f
        o.escursione = v[5].toInt()
        o.buffer = v[6].toInt()
        stato.mixer.curva = v[7].toInt()
        stato.mixer.inverti = v[8] > 0.5f
    }

    companion object {
        /** Frazione di battuta (0…1) in cui si trova `posizione`. */
        fun frazioneBattuta(posizione: Double, primoBattito: Double, battito: Double): Double {
            val x = (posizione - primoBattito) / battito
            return x - floor(x)
        }
    }
}
