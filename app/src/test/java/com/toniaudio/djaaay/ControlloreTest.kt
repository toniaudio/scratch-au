package com.toniaudio.djaaay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ControlloreTest {
    private val ponte = PonteFinto()
    private val piattaforma = PiattaformaFinta()
    private val stato = StatoApp()
    private val c = Controllore(stato, ponte, piattaforma, CoroutineScope(Dispatchers.Unconfined))

    private fun carica(d: Int, bpm: Double, primo: Double) {
        val deck = stato.deck[d]
        deck.brano = branoFinto(d + 1L, "Brano $d", "", 300)
        deck.bpm = bpm
        deck.primoBattito = primo
        deck.durata = 300.0
    }

    @Test
    fun syncPareggiaIlTempoEAllineaLeBattute() {
        carica(0, 100.0, 0.0)
        carica(1, 98.0, 0.2)
        c.impostaPitch(0, 0.02f)  // A suona a 102 BPM
        stato.deck[0].inPlay = true
        stato.deck[0].posizione = 6.0 + 0.25 * 0.6  // A a un quarto di battuta (battuta = 0,6 s; 6 s = 10 battute)
        stato.deck[1].posizione = 0.2 + 5 * (60.0 / 98.0)  // B esattamente su una battuta
        c.sync(1)
        assertEquals(102.0 / 98.0 - 1.0, stato.deck[1].pitch.toDouble(), 1e-5)
        assertEquals(102.0, stato.deck[1].bpmEffettivi, 1e-3)
        val salto = ponte.comandi.last()
        assertEquals(Cmd.VAI, salto.tipo)
        // B va avanti di un quarto della sua battuta
        assertEquals(stato.deck[1].posizione + 0.25 * 60.0 / 98.0, salto.a, 1e-6)
    }

    @Test
    fun syncOltreLEscursioneNonCambiaNiente() {
        carica(0, 128.0, 0.0)
        carica(1, 90.0, 0.0)
        c.sync(1)
        assertEquals(0f, stato.deck[1].pitch)
        assertNotNull(stato.messaggio)
        assertTrue(stato.messaggio!!.contains("±8%"))
    }

    @Test
    fun loopParteDallaBattutaEAccorciaConLeFrecce() {
        carica(0, 120.0, 0.25)  // battuta = 0,5 s, battute a 0,25 0,75 1,25…
        stato.deck[0].posizione = 10.4  // dentro la battuta che inizia a 10,25
        c.loopTocco(0)
        val loop = ponte.comandi.last()
        assertEquals(Cmd.LOOP, loop.tipo)
        assertEquals(10.25, loop.a, 1e-9)
        assertEquals(10.25 + 4 * 0.5, loop.b, 1e-9)  // 4 battute
        c.loopLunghezza(0, -1)
        assertEquals(10.25 + 2 * 0.5, ponte.comandi.last().b, 1e-9)
        assertEquals("2", etichettaLoop(LUNGHEZZE_LOOP[stato.deck[0].loopIndice]))
        repeat(10) { c.loopLunghezza(0, -1) }
        assertEquals("1/8", etichettaLoop(LUNGHEZZE_LOOP[stato.deck[0].loopIndice]))
        c.loopTocco(0)
        assertEquals(0.0, ponte.comandi.last().b, 0.0)
        assertTrue(!stato.deck[0].loopAttivo)
    }

    @Test
    fun loopSullaGrigliaVariabileDallaBattutaPiuVicina() {
        carica(0, 110.0, 0.3)
        val deck = stato.deck[0]
        // brano che rallenta: ogni battuta 10 ms più lunga della precedente
        val b = DoubleArray(60)
        b[0] = 0.3
        for (k in 1 until b.size) b[k] = b[k - 1] + 0.5 + 0.01 * k
        deck.battiti = b
        assertEquals(12.5, deck.battutaA(deck.tempoDellaBattuta(12.5)), 1e-9)
        assertEquals(b[21] - b[20], deck.battitoA(b[20] + 0.1), 1e-9)
        // premuto un attimo prima della battuta 20: il loop parte da lì e dura 4 battute vere
        deck.posizione = b[20] - 0.03
        c.loopTocco(0)
        assertEquals(b[20], ponte.comandi.last().a, 1e-9)
        assertEquals(b[24], ponte.comandi.last().b, 1e-9)
        c.loopLunghezza(0, -1)
        assertEquals(b[22], ponte.comandi.last().b, 1e-9)
        // mezza battuta: si aggancia alla mezza più vicina
        c.loopTocco(0)
        repeat(2) { c.loopLunghezza(0, -1) }  // 2 → 1 → 1/2
        deck.posizione = b[30] + 0.55 * (b[31] - b[30])
        c.loopTocco(0)
        assertEquals((b[30] + b[31]) / 2, ponte.comandi.last().a, 1e-9)
        assertEquals(b[31], ponte.comandi.last().b, 1e-9)
    }

    @Test
    fun hotCuePrimaFissaPoiSalta() {
        carica(0, 120.0, 0.0)
        stato.deck[0].posizione = 42.0
        c.hotCue(0, 2)
        assertEquals(42.0, stato.deck[0].hotCue[2]!!, 0.0)
        stato.deck[0].posizione = 80.0
        c.hotCue(0, 2)
        assertEquals(PonteFinto.Comando(0, Cmd.VAI, 42.0, 0.0), ponte.comandi.last())
        c.cancellaHotCue(0, 2)
        assertNull(stato.deck[0].hotCue[2])
    }

    @Test
    fun escursionePiuPiccolaRiportaIlPitchNelLimite() {
        c.escursione(16)
        c.impostaPitch(0, 0.12f)
        assertEquals(0.12f, stato.deck[0].pitch)
        c.escursione(8)
        assertEquals(0.08f, stato.deck[0].pitch)
        assertEquals(0.08f, ponte.deck[0 to ParDeck.PITCH])
    }

    @Test
    fun nonCaricaSuUnDeckCheSuona() {
        val brano = branoFinto(7, "Funky", "", 200)
        stato.deck[0].inPlay = true
        c.caricaBrano(0, brano)
        assertTrue(piattaforma.caricati.isEmpty())
        stato.deck[0].inPlay = false
        c.caricaBrano(0, brano)
        assertEquals(listOf(7L to 0), piattaforma.caricati)
        assertEquals(98.0, stato.deck[0].bpm, 0.0)
        assertNull(stato.deck[0].caricamento)
        assertEquals(listOf(7L), stato.recenti)
        assertEquals(98.0, stato.bpmNoti[brano.chiave]!!, 0.0)
    }

    @Test
    fun ilBranoSuonaGiaMentreSiCarica() {
        val pausa = kotlinx.coroutines.CompletableDeferred<Unit>()
        piattaforma.pausa = pausa
        c.caricaBrano(1, branoFinto(9, "Lungo", "", 400))
        // l'inizio è pronto: il brano è già nel deck, il resto arriva
        assertEquals(listOf(9L to 1), piattaforma.caricati)
        assertEquals("Lungo", stato.deck[1].brano?.titolo)
        assertEquals(0.1f, stato.deck[1].caricamento)
        assertEquals(0.0, stato.deck[1].bpm, 0.0)
        pausa.complete(Unit)
        assertNull(stato.deck[1].caricamento)
        assertEquals(98.0, stato.deck[1].bpm, 0.0)
        assertEquals(1, piattaforma.caricati.size)  // nel deck una volta sola
    }

    @Test
    fun opzioniSalvateERilette() {
        stato.opzioni.frizione = 0.8f
        c.curva(2)
        c.escursione(16)
        c.chiudiOpzioni()
        val nuovo = StatoApp()
        Controllore(nuovo, PonteFinto(), piattaforma, CoroutineScope(Dispatchers.Unconfined))
        assertEquals(0.8f, nuovo.opzioni.frizione)
        assertEquals(2, nuovo.mixer.curva)
        assertEquals(16, nuovo.opzioni.escursione)
    }

    @Test
    fun statoDalMotoreAOgniFotogramma() {
        ponte.istantanea[0] = 12.5
        ponte.istantanea[3] = 1.0
        ponte.istantanea[8 + 1] = 200.0
        ponte.istantanea[18] = 1.0
        c.aggiornaDaMotore()
        assertEquals(12.5, stato.deck[0].posizione, 0.0)
        assertTrue(stato.deck[0].inPlay)
        assertEquals(200.0, stato.deck[1].durata, 0.0)
        assertTrue(stato.mixer.rec)
    }

    @Test
    fun effettiIndipendentiCheSuonanoInsieme() {
        val fx = stato.deck[0].fx
        // ECHO bloccato con il secondo dito
        c.fxTipo(0, 0)
        c.fxTrackpadGiu(0, 0, 0.5f, 0.5f)
        c.fxTrackpadTieni(0, 0)
        c.fxTrackpadSu(0, 0)
        // si passa al ROLL: l'echo resta acceso e bloccato
        c.fxTipo(0, 3)
        assertTrue(fx.accesi[0])
        assertTrue(fx.tenuti[0])
        c.fxTrackpadGiu(0, 3, 0.8f, 0.9f)
        assertTrue(fx.accesi[3])
        assertEquals(1f, ponte.fx[Triple(0, ParFx.ACCESO, 0)])
        assertEquals(1f, ponte.fx[Triple(0, ParFx.ACCESO, 3)])
        assertEquals(0.9f, fx.param1[3])  // il dito comanda solo il roll
        assertEquals(0.5f, fx.param1[0])
        c.fxTrackpadSu(0, 3)
        assertEquals(false, fx.accesi[3])
        assertTrue(fx.accesi[0])
        // due dita su due trackpad (roll e reverb) insieme: ognuno si spegne quando si alza il suo dito
        c.fxTrackpadGiu(0, 3, 0.5f, 0.5f)
        c.fxTrackpadGiu(0, 2, 0.5f, 0.5f)
        assertTrue(fx.accesi[2] && fx.accesi[3])
        assertEquals(2, fx.tipo)  // la riga in basso segue l'ultimo toccato
        c.fxTrackpadSu(0, 3)
        assertEquals(false, fx.accesi[3])
        assertTrue(fx.accesi[2])
        c.fxTrackpadSu(0, 2)
        assertEquals(false, fx.accesi[2])
        assertTrue(fx.accesi[0])
        // un tocco sull'echo lo sblocca e lasciando svanisce
        c.fxTipo(0, 0)
        c.fxTrackpadGiu(0, 0, 0.5f, 0.5f)
        c.fxTrackpadSu(0, 0)
        assertEquals(false, fx.accesi[0])
        assertEquals(0f, ponte.fx[Triple(0, ParFx.ACCESO, 0)])
    }

    @Test
    fun fxDalMidiSuonaFinchePremutoEHold() {
        // tasto "FX Roll": sceglie il ROLL e suona finché è premuto
        c.learnScegli(AzioneMidi.FX_ROLL, 0)
        nota(40, 127)
        nota(40, 0)  // assegnato
        nota(40, 127)
        assertTrue(stato.deck[0].fx.acceso)
        assertEquals(3, stato.deck[0].fx.tipo)
        assertEquals(1f, ponte.fx[Triple(0, ParFx.ACCESO, 3)])
        nota(40, 0)
        assertEquals(false, stato.deck[0].fx.acceso)
        // tasto HOLD mentre suona: resta acceso anche lasciando; premuto di nuovo lo spegne
        c.learnScegli(AzioneMidi.FX_HOLD, 0)
        nota(41, 127)
        nota(41, 0)
        nota(40, 127)
        nota(41, 127)
        nota(41, 0)
        nota(40, 0)
        assertTrue(stato.deck[0].fx.acceso)
        assertTrue(stato.deck[0].fx.hold)
        nota(41, 127)
        assertEquals(false, stato.deck[0].fx.acceso)
        // i valori restano comandabili da manopole MIDI
        c.learnScegli(AzioneMidi.FX_DRY_WET, 0)
        cc(0, 30, 10)
        cc(0, 30, 0)
        assertEquals(0f, stato.deck[0].fx.dryWet[3])
    }

    @Test
    fun fxOgniTipoRicordaLeRegolazioniEIlTempoSegueBpmEPitch() {
        c.fxTipo(1, 0)
        c.fxManopola(1, 1, 0.9f)  // feedback dell'echo
        c.fxScelta(1, 3)  // quarta voce dell'echo = 1 battito (indice 4 nel motore)
        assertEquals(4f, ponte.fx[Triple(1, ParFx.SCELTA, 0)])
        c.fxTipo(1, 1)
        c.fxManopola(1, 1, 0.2f)  // feedback del dub
        assertEquals(0.9f, stato.deck[1].fx.param1[0])
        assertEquals(0.2f, stato.deck[1].fx.param1[1])
        carica(1, 100.0, 0.0)
        c.impostaPitch(1, 0.05f)
        c.aggiornaDaMotore()
        assertEquals((0.6 / 1.05).toFloat(), ponte.fx[Triple(1, ParFx.BATTITO, 0)]!!, 1e-5f)
        assertEquals("-12 st", testoFx(3, 1, 0f))
        assertEquals("tono normale", testoFx(3, 1, 0.52f))
        assertEquals("+12 st", testoFx(3, 1, 1f))
    }

    private fun cc(canale: Int, numero: Int, valore: Int) = c.midi(MessaggioMidi(SorgenteMidi(false, canale, numero), valore))
    private fun nota(numero: Int, velocity: Int) = c.midi(MessaggioMidi(SorgenteMidi(true, 0, numero), velocity))

    @Test
    fun lettoreMidiCapisceRunningStatusEIgnoraIlResto() {
        val letti = mutableListOf<MessaggioMidi>()
        val l = LettoreMidi()
        // CC 7 = 100 sul canale 2, poi (running status) CC 8 = 5, clock in mezzo, sysex, note on/off
        val byte = byteArrayOf(0xB1.toByte(), 7, 100, 8, 0xF8.toByte(), 5, 0xF0.toByte(), 1, 2, 3, 0xF7.toByte(),
            0x90.toByte(), 60, 127, 0x80.toByte(), 60, 64, 0x90.toByte(), 61, 0)
        l.leggi(byte, 0, 6) { letti += it }  // arriva a pezzi
        l.leggi(byte, 6, byte.size - 6) { letti += it }
        assertEquals(
            listOf(
                MessaggioMidi(SorgenteMidi(false, 1, 7), 100),
                MessaggioMidi(SorgenteMidi(false, 1, 8), 5),
                MessaggioMidi(SorgenteMidi(true, 0, 60), 127),
                MessaggioMidi(SorgenteMidi(true, 0, 60), 0),
                MessaggioMidi(SorgenteMidi(true, 0, 61), 0),
            ),
            letti,
        )
    }

    @Test
    fun learnAssegnaEPoiIlControllerComanda() {
        c.learnAttiva(true)
        c.learnScegli(AzioneMidi.CROSSFADER, 0)
        cc(0, 8, 64)  // assegnato
        assertNull(stato.midi.bersaglio)
        cc(0, 8, 127)
        assertEquals(1f, stato.mixer.crossfader)
        assertEquals(1f, ponte.mixer[ParMixer.CROSSFADER to 0])
        // Play A su una nota: si assegna alla pressione, il rilascio non fa niente
        c.learnScegli(AzioneMidi.PLAY, 0)
        nota(46, 127)
        nota(46, 0)
        carica(0, 120.0, 0.0)
        nota(46, 100)
        assertEquals(Cmd.PLAY_PAUSA, ponte.comandi.last().tipo)
        // CUE B: premuto e rilasciato
        c.learnScegli(AzioneMidi.CUE, 1)
        cc(0, 20, 127)
        cc(0, 20, 0)
        cc(0, 20, 127)
        assertEquals(Cmd.CUE_GIU, ponte.comandi.last().tipo)
        cc(0, 20, 0)
        assertEquals(Cmd.CUE_SU, ponte.comandi.last().tipo)
        // filtro: 64 = centro (spento)
        c.learnScegli(AzioneMidi.FILTRO, 1)
        cc(0, 14, 10)
        cc(0, 14, 64)
        assertEquals(0f, stato.mixer.canale[1].filtro)
        // lo stesso controllo riassegnato toglie il vecchio comando
        c.learnScegli(AzioneMidi.VOLUME, 0)
        cc(0, 8, 30)
        assertEquals(listOf(AzioneMidi.VOLUME), stato.midi.assegnazioni.filter { it.sorgente == SorgenteMidi(false, 0, 8) }.map { it.azione })
        // salvate e ricaricate
        val altro = Controllore(StatoApp(), PonteFinto(), piattaforma, CoroutineScope(Dispatchers.Unconfined))
        assertEquals(stato.midi.assegnazioni.toSet(), altro.stato.midi.assegnazioni.toSet())
    }

    @Test
    fun proceduraGuidataUnControlloAllaVolta() {
        c.proceduraInizia()
        assertEquals(AzioneMidi.CROSSFADER to 0, stato.midi.bersaglio)
        cc(0, 8, 60)
        cc(0, 8, 61)  // il crossfader si sta ancora muovendo: non va al passo dopo
        cc(0, 8, 62)
        assertEquals(AzioneMidi.VOLUME to 0, stato.midi.bersaglio)
        cc(0, 1, 100)
        c.proceduraSalta()  // GAIN A saltato
        assertEquals(AzioneMidi.ALTI to 0, stato.midi.bersaglio)
        c.proceduraFine()
        assertNull(stato.midi.bersaglio)
        assertEquals(2, stato.midi.assegnazioni.size)
        assertEquals(62f / 127f, stato.mixer.crossfader, 1e-6f)
    }

    @Test
    fun libreriaColController() {
        piattaforma.brani = (1..5).map { branoFinto(it.toLong(), "Brano $it", "", 200) }  // letti all'apertura
        c.libreriaSposta(1, 2)
        assertTrue(stato.deck[1].libreriaAperta)
        assertEquals(2, stato.deck[1].libSelezione)
        c.libreriaSposta(1, 10)
        assertEquals(4, stato.deck[1].libSelezione)
        c.libreriaCarica(1)
        assertEquals("Brano 5", stato.deck[1].brano?.titolo)
    }

    @Test
    fun dvsAccendeIlDeckSoloConLaSchedaEIlPermesso() {
        piattaforma.permessoIngresso = false
        c.dvs(0)
        assertEquals(false, stato.deck[0].dvs)  // prima il permesso
        piattaforma.permessoIngresso = true
        c.permessoIngresso(true)  // concesso: il deck A si accende da solo
        assertTrue(stato.deck[0].dvs)
        assertEquals(7 to 0, piattaforma.ingressoAperto)
        assertEquals(1f, ponte.deck[0 to ParDeck.DVS])
        c.dvs(1)
        assertTrue(stato.deck[1].dvs)  // un solo timecode, due deck
        c.dvsCoppia(1)  // ingressi 3-4: si riapre la scheda
        assertEquals(7 to 1, piattaforma.ingressoAperto)
        c.dvs(0)
        assertEquals(false, stato.deck[0].dvs)
        assertEquals(0f, ponte.deck[0 to ParDeck.DVS])
        // scheda scollegata: si chiude tutto
        piattaforma.schede = emptyList()
        c.dvsAggiornaSchede()
        assertEquals(false, stato.dvs.aperto)
        assertEquals(false, stato.deck[1].dvs)
        assertNull(piattaforma.ingressoAperto)
        // senza scheda il DVS non si accende
        c.dvs(0)
        assertEquals(false, stato.deck[0].dvs)
        assertTrue(stato.messaggio!!.contains("scheda audio USB"))
    }

    @Test
    fun dvsOpzioniSalvateEMandateAlMotore() {
        c.dvsTipo(1)
        c.dvsAssoluta(true)
        c.dvsGuadagno(6f)
        assertEquals(1f, ponte.dvs[ParDvs.TIPO])
        assertEquals(1f, ponte.dvs[ParDvs.ASSOLUTA])
        val altroPonte = PonteFinto()
        val altro = Controllore(StatoApp(), altroPonte, piattaforma, CoroutineScope(Dispatchers.Unconfined))
        assertEquals(1, altro.stato.dvs.tipo)
        assertTrue(altro.stato.dvs.assoluta)
        assertEquals(6f, altroPonte.dvs[ParDvs.GUADAGNO])
    }

    @Test
    fun trackpadFxToccoHoldERitocco() {
        c.fxTipo(0, 1)  // DUB DELAY: Y = feedback
        c.fxTrackpadGiu(0, 1, 0.3f, 0.8f)
        assertTrue(stato.deck[0].fx.acceso)
        assertEquals(0.3f, stato.deck[0].fx.dryWet[1])
        assertEquals(0.8f, stato.deck[0].fx.param1[1])
        c.fxTrackpadSposta(0, 1, 0.6f, 0.2f)
        assertEquals(0.6f, ponte.fx[Triple(0, ParFx.DRY_WET, 1)])
        c.fxTrackpadSu(0, 1)  // dito alzato: si spegne (il motore lascia la coda)
        assertEquals(false, stato.deck[0].fx.acceso)
        // hold: secondo dito, poi si alzano tutte e due
        c.fxTrackpadGiu(0, 1, 0.5f, 0.5f)
        c.fxTrackpadTieni(0, 1)
        c.fxTrackpadSu(0, 1)
        assertTrue(stato.deck[0].fx.acceso)
        assertTrue(stato.deck[0].fx.hold)
        // ritocco: al rilascio svanisce
        c.fxTrackpadGiu(0, 1, 0.5f, 0.5f)
        assertEquals(false, stato.deck[0].fx.hold)
        assertTrue(stato.deck[0].fx.acceso)
        c.fxTrackpadSu(0, 1)
        assertEquals(false, stato.deck[0].fx.acceso)
        // ROLL: Y = lunghezza
        c.fxTipo(0, 3)
        c.fxTrackpadGiu(0, 3, 1f, 0.95f)
        assertEquals(0.95f, stato.deck[0].fx.param1[3])
        assertTrue(stato.deck[0].fx.acceso)
        c.fxTrackpadSu(0, 3)
        assertEquals(false, stato.deck[0].fx.acceso)
    }

    @Test
    fun preascoltoConLaSchedaA4Uscite() {
        c.cuffia(1)
        assertEquals(1f, ponte.mixer[ParMixer.CUFFIA to 1])
        assertTrue(stato.messaggio!!.contains("4 uscite"))  // senza scheda attiva lo dice
        c.attivaCuffie(true)
        assertEquals(true to 9, piattaforma.cuffie)
        assertTrue(stato.cuffieAttive)
        c.mixCuffie(0.3f)
        assertEquals(0.3f, ponte.mixer[ParMixer.MIX_CUFFIE to 0])
        // scheda scollegata: il motore torna stereo e l'app se ne accorge
        ponte.istantanea[36] = 2.0
        c.aggiornaDaMotore()
        assertEquals(false, stato.cuffieAttive)
        assertTrue(stato.opzioni.cuffie)  // la scelta resta: alla prossima scheda si riattiva
        c.dvsAggiornaSchede()
        assertTrue(stato.cuffieAttive)
        // senza schede a 4 uscite non si attiva
        c.attivaCuffie(false)
        piattaforma.schedeUscita = emptyList()
        c.attivaCuffie(true)
        assertEquals(false, stato.cuffieAttive)
    }

    @Test
    fun oneDeckECampionatore() {
        carica(1, 120.0, 0.0)
        stato.deck[1].inPlay = true
        c.unDeck(true)
        assertEquals(Cmd.PLAY, ponte.comandi.last().tipo)  // il deck B si ferma
        assertEquals(0.0, ponte.comandi.last().a, 0.0)
        // pad vuoto: si apre la libreria per lui; scelto un brano, il pad si riempie
        piattaforma.brani = listOf(branoFinto(5L, "Boom Bap Loop", "", 8))
        c.padCampione(2)
        assertEquals(2, stato.campionatore.scegliPer)
        c.caricaCampione(2, piattaforma.brani[0])
        assertEquals("Boom Bap Loop", stato.campionatore.pad[2].brano?.titolo)
        assertEquals(5L to 2, piattaforma.nelCampionatore.last())
        assertNull(stato.campionatore.scegliPer)
        // pad pieno: suona (premi e lascia)
        c.padCampione(2)
        c.padCampioneRilascia(2)
        assertEquals(listOf(CmdCampione.PREMI, CmdCampione.LASCIA), ponte.campioni.takeLast(2).map { it.first })
        // MODIFICA: il tocco apre il menu; modo loop, salvato e ricaricato
        c.modificaCampionatore()
        c.padCampione(2)
        assertEquals(2, stato.campionatore.menu)
        c.campioneModo(2, 1)
        assertEquals(Triple(CmdCampione.MODO, 2, 1), ponte.campioni.last())
        val altro = Controllore(StatoApp(), PonteFinto(), piattaforma, CoroutineScope(Dispatchers.Unconfined))
        assertTrue(altro.stato.opzioni.unDeck)
        assertEquals(1, altro.stato.campionatore.pad[2].modo)
        altro.aggiornaLibreria()  // letta la libreria il suono torna nel suo pad
        assertEquals("Boom Bap Loop", altro.stato.campionatore.pad[2].brano?.titolo)
        // togli
        c.campioneTogli(2)
        assertNull(stato.campionatore.pad[2].brano)
        assertEquals(CmdCampione.TOGLI, ponte.campioni.last().first)
    }
}
