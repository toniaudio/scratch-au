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

    // DVS: ingresso della scheda audio
    fun haPermessoIngresso(): Boolean
    fun chiediPermessoIngresso()
    fun schedeIngresso(): List<SchedaAudio>
    /** "" se l'ingresso è aperto, altrimenti il problema. `coppia` 0 = ingressi 1-2, 1 = 3-4. */
    fun apriIngresso(id: Int, coppia: Int): String
    fun chiudiIngresso()

    // Pre-ascolto: schede con almeno 4 uscite; "" = uscita aperta a 4 canali (o tornata stereo)
    fun schedeUscita(): List<SchedaAudio>
    fun apriCuffie(attiva: Boolean, id: Int): String

    // Campionatore (One Deck)
    fun mettiNelCampionatore(caricato: BranoCaricato, pad: Int)
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
    private val istantanea = DoubleArray(37)
    // usati già nell'init (caricaCampionatore): vanno dichiarati prima
    private var campionatoreRipristinato = false
    private val campioniSalvati = arrayOfNulls<Long>(8)
    private val caricamenti = arrayOfNulls<Job>(2)

    init {
        caricaOpzioni()
        applicaOpzioni()
        applicaFx()
        caricaMidi()
        caricaDvs()
        applicaDvs()
        caricaCampionatore()
        if (stato.opzioni.cuffie) attivaCuffie(true, silenzioso = true)
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
        for ((i, p) in stato.campionatore.pad.withIndex()) {
            p.suona = istantanea[20 + i] > 0.5
            p.avanzamento = istantanea[28 + i].toFloat()
        }
        if (stato.cuffieAttive && istantanea[36] < 3.5) {
            // l'uscita è tornata stereo da sola: scheda scollegata
            stato.cuffieAttive = false
            mostra("Pre-ascolto: la scheda audio è stata scollegata")
        }
        aggiornaBattitoFx()
        aggiornaDvs()
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

    // ------------------------------------------------------------------ effetti
    private val battitoInviato = doubleArrayOf(-1.0, -1.0)

    fun fxAcceso(d: Int, acceso: Boolean) {
        val fx = stato.deck[d].fx
        fx.acceso = acceso
        fx.momentaneo = false
        if (!acceso) fx.hold = false
        ponte.impostaFx(d, ParFx.ACCESO, fx.tipo, if (acceso) 1f else 0f)
    }

    fun fxOnOff(d: Int) = fxAcceso(d, !stato.deck[d].fx.acceso)

    fun fxTipo(d: Int, tipo: Int) {
        val fx = stato.deck[d].fx
        fx.tipo = tipo.coerceIn(0, EFFETTI.size - 1)
        ponte.impostaFx(d, ParFx.TIPO, fx.tipo, fx.tipo.toFloat())
    }

    /** Manopole dell'effetto scelto: `quale` 0 = DRY/WET, 1 e 2 = parametri. */
    fun fxManopola(d: Int, quale: Int, valore: Float) {
        val fx = stato.deck[d].fx
        val v = valore.coerceIn(0f, 1f)
        val t = fx.tipo
        when (quale) {
            0 -> { fx.dryWet[t] = v; ponte.impostaFx(d, ParFx.DRY_WET, t, v) }
            1 -> { fx.param1[t] = v; ponte.impostaFx(d, ParFx.PARAM1, t, v) }
            else -> { fx.param2[t] = v; ponte.impostaFx(d, ParFx.PARAM2, t, v) }
        }
    }

    /** Riga in basso (tempo, coda, rapporto): `voce` = posizione nella riga. */
    fun fxScelta(d: Int, voce: Int) {
        val fx = stato.deck[d].fx
        val indici = EFFETTI[fx.tipo].indici
        val indice = indici[voce.coerceIn(0, indici.size - 1)]
        fx.scelta[fx.tipo] = indice
        ponte.impostaFx(d, ParFx.SCELTA, fx.tipo, indice.toFloat())
    }

    /** Pad FX del deck: tocco = accende quell'effetto (o lo spegne se è già acceso). */
    fun fxPadTocco(d: Int, tipo: Int) {
        val fx = stato.deck[d].fx
        if (fx.acceso && fx.tipo == tipo) return fxAcceso(d, false)
        fxTipo(d, tipo)
        fxAcceso(d, true)
    }

    /** Pad tenuto premuto: effetto acceso finché non si lascia. */
    fun fxPadTieni(d: Int, tipo: Int) {
        fxTipo(d, tipo)
        fxAcceso(d, true)
        stato.deck[d].fx.momentaneo = true
    }

    fun fxPadRilascia(d: Int) {
        if (stato.deck[d].fx.momentaneo) fxAcceso(d, false)
    }

    /** Tasto PAD dell'unità FX: trackpad al posto delle manopole (o di nuovo le manopole). */
    fun fxPad(d: Int) {
        val fx = stato.deck[d].fx
        fx.padAperto = !fx.padAperto
    }

    /**
     * Trackpad: X = dry/wet, Y = il parametro principale dell'effetto. Il dito appoggiato accende l'effetto,
     * staccandolo l'effetto svanisce con la sua coda. Se era già acceso (dal pad FX del deck) resta acceso.
     */
    fun fxTrackpadGiu(d: Int, x: Float, y: Float) {
        val fx = stato.deck[d].fx
        fx.toccato = true
        when {
            fx.hold -> {
                // bloccato: questo tocco lo libera, e alzando il dito svanisce
                fx.hold = false
                fx.spegniAlRilascio = true
            }
            !fx.acceso -> {
                fxAcceso(d, true)
                fx.spegniAlRilascio = true
            }
            else -> fx.spegniAlRilascio = false
        }
        fxTrackpadSposta(d, x, y)
    }

    fun fxTrackpadSposta(d: Int, x: Float, y: Float) {
        val fx = stato.deck[d].fx
        fxManopola(d, 0, x)
        fxManopola(d, EFFETTI[fx.tipo].asseY, y)
    }

    /** Un secondo dito sul trackpad: l'effetto resta acceso anche staccando le dita. */
    fun fxTrackpadTieni(d: Int) {
        val fx = stato.deck[d].fx
        if (fx.acceso) fx.hold = true
    }

    fun fxTrackpadSu(d: Int) {
        val fx = stato.deck[d].fx
        fx.toccato = false
        if (!fx.hold && fx.spegniAlRilascio) fxAcceso(d, false)
        fx.spegniAlRilascio = false
    }

    /** Manda al motore tutte le regolazioni degli effetti (all'avvio). */
    private fun applicaFx() {
        for (d in 0..1) {
            val fx = stato.deck[d].fx
            for (t in EFFETTI.indices) {
                ponte.impostaFx(d, ParFx.DRY_WET, t, fx.dryWet[t])
                ponte.impostaFx(d, ParFx.PARAM1, t, fx.param1[t])
                ponte.impostaFx(d, ParFx.PARAM2, t, fx.param2[t])
                ponte.impostaFx(d, ParFx.SCELTA, t, fx.scelta[t].toFloat())
            }
            ponte.impostaFx(d, ParFx.TIPO, fx.tipo, fx.tipo.toFloat())
            ponte.impostaFx(d, ParFx.ACCESO, fx.tipo, if (fx.acceso) 1f else 0f)
        }
    }

    /** Gli echi vanno a tempo: durata di un battito come si sente (BPM del brano e pitch). */
    private fun aggiornaBattitoFx() {
        for (d in stato.deck) {
            val secondi = d.battitoSecondi / (1.0 + d.pitch)
            if (abs(secondi - battitoInviato[d.indice]) > 1e-5) {
                battitoInviato[d.indice] = secondi
                ponte.impostaFx(d.indice, ParFx.BATTITO, 0, secondi.toFloat())
            }
        }
    }

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
        stato.vista = 0  // telefono: la libreria si apre nella vista dei deck
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
        scope.launch {
            stato.libreria = piattaforma.leggiLibreria()
            ripristinaCampionatore()
        }
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

    // ------------------------------------------------------------------ libreria col controller
    /** Sposta la riga scelta della libreria del deck (la apre se è chiusa). */
    fun libreriaSposta(d: Int, passi: Int) {
        val deck = stato.deck[d]
        if (!deck.libreriaAperta) apriLibreria(d)
        val n = braniVisibili(stato, deck).size
        if (n > 0) deck.libSelezione = (deck.libSelezione + passi).coerceIn(0, n - 1)
    }

    fun libreriaCarica(d: Int) {
        val deck = stato.deck[d]
        if (!deck.libreriaAperta) return apriLibreria(d)
        braniVisibili(stato, deck).getOrNull(deck.libSelezione)?.let { caricaBrano(d, it) }
    }

    // ------------------------------------------------------------------ MIDI
    private val ultimoValore = HashMap<SorgenteMidi, Int>()
    private val assegnatiProcedura = HashSet<SorgenteMidi>()

    fun midiCollegato(nome: String?) {
        if (nome != null && nome != stato.midi.dispositivo) mostra("Controller MIDI collegato: $nome")
        stato.midi.dispositivo = nome
    }

    /** Un messaggio dal controller (sul thread dell'interfaccia). */
    fun midi(m: MessaggioMidi) {
        val s = m.sorgente
        val prima = ultimoValore[s]
        ultimoValore[s] = m.valore
        val midi = stato.midi
        midi.ultimo = "${s.testo} = ${m.valore}"
        val bersaglio = midi.bersaglio
        if (bersaglio != null) {
            // LEARN: il primo controllo mosso (tasto premuto o manopola girata) va al comando scelto;
            // nella procedura guidata i controlli appena assegnati non contano (la manopola sta ancora girando)
            val giaUsato = midi.procedura != null && s in assegnatiProcedura
            if (!giaUsato && !(s.nota && m.valore == 0)) {
                assegna(s, bersaglio.first, bersaglio.second)
                return
            }
        }
        for (a in midi.assegnazioni.toList()) if (a.sorgente == s) esegui(a, m.valore, prima)
    }

    private fun esegui(a: Assegnazione, valore: Int, prima: Int?) {
        val d = a.deck
        val v = valore / 127f
        fun giu(x: Int) = if (a.sorgente.nota) x > 0 else x >= 64
        val premuto = giu(valore)
        val eraPremuto = prima != null && giu(prima)
        // pressione: da su a giù (o un tasto che manda solo "premuto" ogni volta)
        val pressione = premuto && (!eraPremuto || (!a.sorgente.nota && prima == valore))
        // manopole con lo zero al centro (filtro, pitch): 63-64 = centro
        val centro = if (valore in 63..64) 0f else ((valore - 63.5f) / 63.5f).coerceIn(-1f, 1f)
        when (a.azione) {
            AzioneMidi.CROSSFADER -> crossfader(v)
            AzioneMidi.MASTER -> master(v)
            AzioneMidi.VOLUME -> mixer(ParMixer.VOLUME, d, v)
            AzioneMidi.GAIN -> mixer(ParMixer.GAIN, d, v)
            AzioneMidi.ALTI -> mixer(ParMixer.ALTI, d, v)
            AzioneMidi.MEDI -> mixer(ParMixer.MEDI, d, v)
            AzioneMidi.BASSI -> mixer(ParMixer.BASSI, d, v)
            AzioneMidi.FILTRO -> mixer(ParMixer.FILTRO, d, centro)
            AzioneMidi.PITCH -> impostaPitch(d, centro * stato.opzioni.escursione / 100f)
            AzioneMidi.FX_DRY_WET -> fxManopola(d, 0, v)
            AzioneMidi.FX_PARAM1 -> fxManopola(d, 1, v)
            AzioneMidi.FX_PARAM2 -> fxManopola(d, 2, v)
            AzioneMidi.MIX_CUFFIE -> mixCuffie(v)
            AzioneMidi.VOLUME_CUFFIE -> volumeCuffie(v)
            in AZIONI_CAMPIONE -> {
                val pad = AZIONI_CAMPIONE.indexOf(a.azione)
                if (pressione) padCampione(pad) else if (!premuto && eraPremuto) padCampioneRilascia(pad)
            }
            AzioneMidi.LIB_SCORRI -> if (prima != null && valore != prima) libreriaSposta(d, if (valore > prima) 1 else -1)
            AzioneMidi.CUE -> if (pressione) cueGiu(d) else if (!premuto && eraPremuto) cueSu(d)
            else -> if (pressione) when (a.azione) {
                AzioneMidi.CURVA -> curvaSuccessiva()
                AzioneMidi.PLAY -> playPausa(d)
                AzioneMidi.SYNC -> sync(d)
                AzioneMidi.KEY -> keylock(d)
                AzioneMidi.DVS -> dvs(d)
                AzioneMidi.HOT_CUE_1 -> hotCue(d, 0)
                AzioneMidi.HOT_CUE_2 -> hotCue(d, 1)
                AzioneMidi.HOT_CUE_3 -> hotCue(d, 2)
                AzioneMidi.HOT_CUE_4 -> hotCue(d, 3)
                AzioneMidi.LOOP -> loopTocco(d)
                AzioneMidi.LOOP_MEZZO -> loopLunghezza(d, -1)
                AzioneMidi.LOOP_DOPPIO -> loopLunghezza(d, +1)
                AzioneMidi.FX_ON -> fxOnOff(d)
                AzioneMidi.FX_SUCCESSIVO -> fxTipo(d, (stato.deck[d].fx.tipo + 1) % EFFETTI.size)
                AzioneMidi.FX_ECHO -> fxPadTocco(d, 0)
                AzioneMidi.FX_DUB -> fxPadTocco(d, 1)
                AzioneMidi.FX_REVERB -> fxPadTocco(d, 2)
                AzioneMidi.FX_STRETCH -> fxPadTocco(d, 3)
                AzioneMidi.LIB_APRI -> apriLibreria(d)
                AzioneMidi.LIB_SU -> libreriaSposta(d, -1)
                AzioneMidi.LIB_GIU -> libreriaSposta(d, +1)
                AzioneMidi.LIB_CARICA -> libreriaCarica(d)
                AzioneMidi.CUFFIA -> cuffia(d)
                else -> Unit
            }
        }
    }

    private fun assegna(s: SorgenteMidi, azione: AzioneMidi, deck: Int) {
        val lista = stato.midi.assegnazioni
        // un controllo fa una cosa sola, e ogni comando ha un controllo solo
        lista.removeAll { it.sorgente == s || (it.azione == azione && it.deck == deck) }
        lista += Assegnazione(s, azione, deck)
        salvaMidi()
        mostra("${nomeComando(azione, deck)} ← ${s.testo}")
        if (stato.midi.procedura != null) {
            assegnatiProcedura += s
            proceduraSalta()
        } else {
            stato.midi.bersaglio = null
        }
    }

    /** LEARN sulla schermata principale: si toccano i comandi e si muovono i controlli. */
    fun learnAttiva(acceso: Boolean) {
        stato.midi.learn = acceso
        stato.midi.bersaglio = null
        stato.midi.procedura = null
        if (acceso) {
            stato.opzioniAperte = false
            if (stato.midi.dispositivo == null) mostra("Nessun controller MIDI collegato: collegalo via USB")
        }
    }

    /** Sceglie il comando che aspetta un controllo (di nuovo lo stesso = annulla). */
    fun learnScegli(azione: AzioneMidi, deck: Int) {
        val nuovo = azione to (if (azione.perDeck) deck else 0)
        stato.midi.bersaglio = if (stato.midi.bersaglio == nuovo) null else nuovo
    }

    fun midiRimuovi(a: Assegnazione) {
        stato.midi.assegnazioni.remove(a)
        salvaMidi()
    }

    fun midiAzzera() {
        stato.midi.assegnazioni.clear()
        salvaMidi()
    }

    /** Configurazione guidata dell'X-Session Pro: un controllo alla volta. */
    fun proceduraInizia() {
        stato.opzioniAperte = false
        stato.midi.learn = false
        assegnatiProcedura.clear()
        stato.midi.procedura = PROCEDURA_XSESSION
        stato.midi.passo = 0
        stato.midi.bersaglio = PROCEDURA_XSESSION[0]
    }

    fun proceduraSalta() {
        val procedura = stato.midi.procedura ?: return
        stato.midi.passo++
        if (stato.midi.passo >= procedura.size) {
            proceduraFine()
            mostra("Configurazione finita: ${stato.midi.assegnazioni.size} comandi assegnati")
        } else {
            stato.midi.bersaglio = procedura[stato.midi.passo]
        }
    }

    fun proceduraFine() {
        stato.midi.procedura = null
        stato.midi.bersaglio = null
    }

    private fun salvaMidi() {
        piattaforma.salvaPreferenza(
            "midi",
            stato.midi.assegnazioni.joinToString(";") { "${it.sorgente.codice()}=${it.azione.name},${it.deck}" },
        )
    }

    private fun caricaMidi() {
        val testo = piattaforma.leggiPreferenza("midi") ?: return
        for (voce in testo.split(';')) {
            val (codice, resto) = voce.split('=').takeIf { it.size == 2 } ?: continue
            val (nome, deck) = resto.split(',').takeIf { it.size == 2 } ?: continue
            val sorgente = SorgenteMidi.daCodice(codice) ?: continue
            val azione = AzioneMidi.entries.firstOrNull { it.name == nome } ?: continue
            stato.midi.assegnazioni += Assegnazione(sorgente, azione, deck.toIntOrNull()?.coerceIn(0, 1) ?: 0)
        }
    }

    // ------------------------------------------------------------------ DVS
    private val istantaneaDvs = FloatArray(8 + 2 * StatoDvs.PUNTI_SCOPE)
    private var deckInAttesaPermesso = -1

    /** Tasto DVS del deck: il deck segue il vinile timecode (o torna normale). */
    fun dvs(d: Int) {
        val deck = stato.deck[d]
        if (deck.dvs) {
            deck.dvs = false
            ponte.impostaDeck(d, ParDeck.DVS, 0f)
            return
        }
        if (!apriIngressoDvs(d)) return
        deck.dvs = true
        ponte.impostaDeck(d, ParDeck.DVS, 1f)
        mostra("DVS acceso sul deck ${deck.lettera}: ora segue il vinile")
    }

    /** Apre l'ingresso della scheda se non è già aperto; false = non si può (e lo dice). */
    fun apriIngressoDvs(deckInAttesa: Int = -1): Boolean {
        val dvs = stato.dvs
        if (dvs.aperto) return true
        if (!piattaforma.haPermessoIngresso()) {
            deckInAttesaPermesso = deckInAttesa
            piattaforma.chiediPermessoIngresso()
            mostra("DVS: consenti l'uso dell'ingresso audio (serve per leggere il giradischi)")
            return false
        }
        dvs.schede = piattaforma.schedeIngresso()
        val scheda = dvs.scheda?.let { s -> dvs.schede.firstOrNull { it.id == s.id || it.nome == s.nome } }
            ?: dvs.schede.firstOrNull { it.usb }
        if (scheda == null) {
            mostra("DVS: collega la scheda audio USB con il giradischi, poi tocca di nuovo DVS")
            return false
        }
        val errore = piattaforma.apriIngresso(scheda.id, dvs.coppia)
        if (errore.isNotEmpty()) {
            mostra("DVS: $errore")
            return false
        }
        dvs.scheda = scheda
        dvs.aperto = true
        return true
    }

    fun permessoIngresso(concesso: Boolean) {
        val d = deckInAttesaPermesso
        deckInAttesaPermesso = -1
        if (!concesso) return mostra("Senza il permesso dell'ingresso audio il DVS non può leggere il giradischi")
        if (d >= 0) dvs(d) else apriIngressoDvs()
    }

    /** Schede collegate o scollegate (e all'apertura della scheda DVS delle opzioni). */
    fun dvsAggiornaSchede() {
        // pre-ascolto scelto ma senza scheda: appena ne arriva una si riprova
        if (stato.opzioni.cuffie && !stato.cuffieAttive) attivaCuffie(true, silenzioso = true)
        val dvs = stato.dvs
        dvs.schede = if (piattaforma.haPermessoIngresso()) piattaforma.schedeIngresso() else emptyList()
        val s = dvs.scheda
        if (dvs.aperto && s != null && dvs.schede.none { it.id == s.id }) {
            chiudiIngressoDvs()
            mostra("DVS: la scheda audio è stata scollegata")
        }
    }

    private fun chiudiIngressoDvs() {
        piattaforma.chiudiIngresso()
        stato.dvs.aperto = false
        for (deck in stato.deck) if (deck.dvs) {
            deck.dvs = false
            ponte.impostaDeck(deck.indice, ParDeck.DVS, 0f)
        }
    }

    private fun riapriIngressoDvs() {
        if (!stato.dvs.aperto) return
        piattaforma.chiudiIngresso()
        stato.dvs.aperto = false
        if (!apriIngressoDvs()) chiudiIngressoDvs()
    }

    fun dvsScegliScheda(s: SchedaAudio) {
        stato.dvs.scheda = s
        riapriIngressoDvs()
        salvaDvs()
    }

    fun dvsCoppia(coppia: Int) {
        stato.dvs.coppia = coppia.coerceIn(0, 1)
        riapriIngressoDvs()
        salvaDvs()
    }

    fun dvsGuadagno(db: Float) {
        stato.dvs.guadagnoDb = db.coerceIn(-12f, 24f)
        ponte.impostaDvs(ParDvs.GUADAGNO, stato.dvs.guadagnoDb)
        salvaDvs()
    }

    fun dvsTipo(tipo: Int) {
        stato.dvs.tipo = tipo.coerceIn(0, TIPI_TIMECODE.size - 1)
        ponte.impostaDvs(ParDvs.TIPO, stato.dvs.tipo.toFloat())
        salvaDvs()
    }

    fun dvsGiri45(acceso: Boolean) {
        stato.dvs.giri45 = acceso
        ponte.impostaDvs(ParDvs.GIRI45, if (acceso) 1f else 0f)
        salvaDvs()
    }

    fun dvsAssoluta(acceso: Boolean) {
        stato.dvs.assoluta = acceso
        ponte.impostaDvs(ParDvs.ASSOLUTA, if (acceso) 1f else 0f)
        salvaDvs()
    }

    fun dvsSensibilita(v: Float) {
        stato.dvs.sensibilita = v.coerceIn(0f, 1f)
        ponte.impostaDvs(ParDvs.SENSIBILITA, stato.dvs.sensibilita)
        salvaDvs()
    }

    fun dvsProtezione(acceso: Boolean) {
        stato.dvs.protezione = acceso
        ponte.impostaDvs(ParDvs.PROTEZIONE, if (acceso) 1f else 0f)
        salvaDvs()
    }

    fun dvsCalibra() {
        if (!stato.dvs.aperto) return mostra("DVS: prima attiva l'ingresso della scheda")
        ponte.impostaDvs(ParDvs.CALIBRA, 1f)
        mostra("Calibrazione: lascia girare il disco a velocità normale per un secondo")
    }

    fun dvsAzzera() = ponte.impostaDvs(ParDvs.AZZERA, 1f)

    private fun aggiornaDvs() {
        val dvs = stato.dvs
        if (!dvs.aperto) return
        ponte.statoDvs(istantaneaDvs)
        if (istantaneaDvs[4] < 0.5f) {
            // l'ingresso si è chiuso da solo (scheda scollegata)
            chiudiIngressoDvs()
            return mostra("DVS: la scheda audio non risponde più")
        }
        dvs.qualita = istantaneaDvs[0]
        dvs.velocita = istantaneaDvs[1]
        dvs.posizione = istantaneaDvs[2].toDouble()
        dvs.livello = istantaneaDvs[3]
        dvs.calibrato = istantaneaDvs[5] > 0.5f
        dvs.invertiti = istantaneaDvs[6] > 0.5f
        istantaneaDvs.copyInto(dvs.scope, 0, 8, 8 + 2 * StatoDvs.PUNTI_SCOPE)
        dvs.versioneScope++
    }

    private fun applicaDvs() {
        val dvs = stato.dvs
        ponte.impostaDvs(ParDvs.TIPO, dvs.tipo.toFloat())
        ponte.impostaDvs(ParDvs.GIRI45, if (dvs.giri45) 1f else 0f)
        ponte.impostaDvs(ParDvs.ASSOLUTA, if (dvs.assoluta) 1f else 0f)
        ponte.impostaDvs(ParDvs.SENSIBILITA, dvs.sensibilita)
        ponte.impostaDvs(ParDvs.PROTEZIONE, if (dvs.protezione) 1f else 0f)
        ponte.impostaDvs(ParDvs.GUADAGNO, dvs.guadagnoDb)
    }

    private fun salvaDvs() {
        val d = stato.dvs
        piattaforma.salvaPreferenza(
            "dvs",
            listOf(d.coppia.toFloat(), d.guadagnoDb, d.tipo.toFloat(), if (d.giri45) 1f else 0f, if (d.assoluta) 1f else 0f,
                d.sensibilita, if (d.protezione) 1f else 0f).joinToString(","),
        )
        d.scheda?.let { piattaforma.salvaPreferenza("dvsScheda", it.nome) }
    }

    private fun caricaDvs() {
        val d = stato.dvs
        piattaforma.leggiPreferenza("dvsScheda")?.let { d.scheda = SchedaAudio(-1, it, 2, true) }
        val v = piattaforma.leggiPreferenza("dvs")?.split(',')?.mapNotNull { it.toFloatOrNull() } ?: return
        if (v.size < 7) return
        d.coppia = v[0].toInt().coerceIn(0, 1)
        d.guadagnoDb = v[1]
        d.tipo = v[2].toInt().coerceIn(0, TIPI_TIMECODE.size - 1)
        d.giri45 = v[3] > 0.5f
        d.assoluta = v[4] > 0.5f
        d.sensibilita = v[5]
        d.protezione = v[6] > 0.5f
    }

    // ------------------------------------------------------------------ pre-ascolto
    /** Tasto cuffia del canale: lo si ascolta in cuffia (prima del fader). */
    fun cuffia(canale: Int) {
        val c = stato.mixer.canale[canale]
        c.cuffia = !c.cuffia
        ponte.impostaMixer(ParMixer.CUFFIA, canale, if (c.cuffia) 1f else 0f)
        if (c.cuffia && !stato.cuffieAttive) mostra("Il pre-ascolto si sente con una scheda audio a 4 uscite: Opzioni › Audio")
    }

    fun mixCuffie(v: Float) {
        stato.mixer.mixCuffie = v.coerceIn(0f, 1f)
        ponte.impostaMixer(ParMixer.MIX_CUFFIE, 0, stato.mixer.mixCuffie)
    }

    fun volumeCuffie(v: Float) {
        stato.mixer.volumeCuffie = v.coerceIn(0f, 1f)
        ponte.impostaMixer(ParMixer.VOLUME_CUFFIE, 0, stato.mixer.volumeCuffie)
    }

    /** Pre-ascolto sulla scheda: master sulle uscite 1-2, cuffie sulle 3-4. */
    fun attivaCuffie(acceso: Boolean, silenzioso: Boolean = false) {
        if (!acceso) {
            stato.opzioni.cuffie = false
            if (stato.cuffieAttive) piattaforma.apriCuffie(false, 0)
            stato.cuffieAttive = false
            return salvaOpzioni()
        }
        stato.opzioni.cuffie = true
        salvaOpzioni()
        val scheda = piattaforma.schedeUscita().firstOrNull()
        if (scheda == null) {
            if (!silenzioso) mostra("Pre-ascolto: collega una scheda audio USB con 4 uscite")
            return
        }
        val errore = piattaforma.apriCuffie(true, scheda.id)
        if (errore.isNotEmpty()) {
            if (!silenzioso) mostra("Pre-ascolto: $errore")
            return
        }
        stato.cuffieAttive = true
        ponte.impostaMixer(ParMixer.MIX_CUFFIE, 0, stato.mixer.mixCuffie)
        ponte.impostaMixer(ParMixer.VOLUME_CUFFIE, 0, stato.mixer.volumeCuffie)
        if (!silenzioso) mostra("Pre-ascolto attivo su ${scheda.nome}: casse sulle uscite 1-2, cuffie sulle 3-4")
    }

    // ------------------------------------------------------------------ One Deck e campionatore
    /** One Deck: deck A + campionatore al posto del deck B. */
    fun unDeck(acceso: Boolean) {
        stato.opzioni.unDeck = acceso
        if (acceso) {
            val b = stato.deck[1]
            if (b.inPlay) ponte.comando(1, Cmd.PLAY, 0.0)
            if (b.dvs) dvs(1)
            b.libreriaAperta = false
        } else {
            ponte.campionatore(CmdCampione.FERMA, 0)
            stato.campionatore.scegliPer = null
            stato.campionatore.menu = null
        }
        salvaOpzioni()
    }

    /** Pad premuto: vuoto = sceglie il suono; in MODIFICA = menu del pad; altrimenti suona. */
    fun padCampione(i: Int) {
        val camp = stato.campionatore
        val pad = camp.pad[i]
        when {
            pad.brano == null -> if (pad.caricamento == null) apriSceltaCampione(i)
            camp.modifica -> camp.menu = i
            else -> ponte.campionatore(CmdCampione.PREMI, i)
        }
    }

    fun padCampioneRilascia(i: Int) {
        val camp = stato.campionatore
        if (camp.pad[i].brano != null && !camp.modifica) ponte.campionatore(CmdCampione.LASCIA, i)
    }

    fun modificaCampionatore() {
        val camp = stato.campionatore
        camp.modifica = !camp.modifica
        if (!camp.modifica) camp.menu = null
    }

    fun fermaCampionatore() = ponte.campionatore(CmdCampione.FERMA, 0)

    fun apriSceltaCampione(i: Int) {
        val camp = stato.campionatore
        camp.menu = null
        camp.scegliPer = i
        if (!piattaforma.haPermessoLibreria()) piattaforma.chiediPermessoLibreria() else aggiornaLibreria()
    }

    fun chiudiSceltaCampione() {
        stato.campionatore.scegliPer = null
    }

    fun campioneModo(i: Int, modo: Int) {
        val pad = stato.campionatore.pad[i]
        pad.modo = modo.coerceIn(0, MODI_PAD.size - 1)
        ponte.campionatore(CmdCampione.MODO, i, pad.modo)
        salvaCampionatore()
    }

    fun campioneTogli(i: Int) {
        val pad = stato.campionatore.pad[i]
        ponte.campionatore(CmdCampione.TOGLI, i)
        pad.brano = null
        pad.bpm = 0.0
        stato.campionatore.menu = null
        salvaCampionatore()
    }

    /** Un brano della libreria va nel pad `i` (decodificato e analizzato come per i deck). */
    fun caricaCampione(i: Int, brano: Brano) {
        val pad = stato.campionatore.pad[i]
        stato.campionatore.scegliPer = null
        pad.caricamento = 0f
        scope.launch {
            val caricato = try {
                piattaforma.carica(brano) { pad.caricamento = it }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                pad.caricamento = null
                return@launch mostra("Non riesco a leggere \"${brano.titolo}\" (${e.message ?: "formato non supportato"})")
            }
            piattaforma.mettiNelCampionatore(caricato, i)
            ponte.campionatore(CmdCampione.MODO, i, pad.modo)
            pad.brano = brano
            pad.bpm = caricato.bpm
            pad.caricamento = null
            salvaCampionatore()
        }
    }

    private fun salvaCampionatore() {
        piattaforma.salvaPreferenza(
            "campionatore",
            stato.campionatore.pad.joinToString(";") { "${it.brano?.id ?: -1}:${it.modo}" },
        )
    }

    private fun caricaCampionatore() {
        val voci = piattaforma.leggiPreferenza("campionatore")?.split(';') ?: return
        for ((i, voce) in voci.take(8).withIndex()) {
            val parti = voce.split(':')
            campioniSalvati[i] = parti.getOrNull(0)?.toLongOrNull()?.takeIf { it >= 0 }
            val modo = parti.getOrNull(1)?.toIntOrNull()?.coerceIn(0, MODI_PAD.size - 1) ?: 0
            stato.campionatore.pad[i].modo = modo
            ponte.campionatore(CmdCampione.MODO, i, modo)
        }
    }

    /** Dopo aver letto la libreria: i suoni del campionatore della volta prima tornano nei loro pad. */
    private fun ripristinaCampionatore() {
        if (campionatoreRipristinato || stato.libreria.isEmpty()) return
        campionatoreRipristinato = true
        for ((i, id) in campioniSalvati.withIndex()) {
            val brano = stato.libreria.firstOrNull { it.id == id } ?: continue
            if (stato.campionatore.pad[i].brano == null) caricaCampione(i, brano)
        }
    }

    // ------------------------------------------------------------------ opzioni
    fun apriOpzioni() {
        stato.opzioniAperte = true
    }

    fun chiudiOpzioni() {
        stato.opzioniAperte = false
        if (!stato.midi.learn && stato.midi.procedura == null) stato.midi.bersaglio = null
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
                o.buffer.toFloat(), stato.mixer.curva.toFloat(), if (stato.mixer.inverti) 1f else 0f,
                if (o.unDeck) 1f else 0f, if (o.cuffie) 1f else 0f).joinToString(","),
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
        if (v.size >= 11) {
            o.unDeck = v[9] > 0.5f
            o.cuffie = v[10] > 0.5f
        }
    }

    companion object {
        /** Frazione di battuta (0…1) in cui si trova `posizione`. */
        fun frazioneBattuta(posizione: Double, primoBattito: Double, battito: Double): Double {
            val x = (posizione - primoBattito) / battito
            return x - floor(x)
        }
    }
}
