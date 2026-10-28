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
    fun padFxToccoAccendeESpegneTenutoEMomentaneo() {
        c.fxPadTocco(0, 1)  // DUB
        assertTrue(stato.deck[0].fx.acceso)
        assertEquals(1, stato.deck[0].fx.tipo)
        assertEquals(1f, ponte.fx[Triple(0, ParFx.ACCESO, 1)])
        c.fxPadTocco(0, 1)  // stesso pad: si spegne
        assertEquals(false, stato.deck[0].fx.acceso)
        c.fxPadTocco(0, 1)
        c.fxPadTocco(0, 2)  // altro pad: cambia effetto e resta acceso
        assertEquals(2, stato.deck[0].fx.tipo)
        assertTrue(stato.deck[0].fx.acceso)
        c.fxPadRilascia(0)  // era un tocco, non si spegne
        assertTrue(stato.deck[0].fx.acceso)
        c.fxAcceso(0, false)
        c.fxPadTieni(0, 3)  // tenuto: acceso finché non si lascia
        assertTrue(stato.deck[0].fx.acceso)
        c.fxPadRilascia(0)
        assertEquals(false, stato.deck[0].fx.acceso)
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
        assertEquals("fisso", testoFx(3, 2, 0.5f))
        assertEquals("+12 st", testoFx(3, 2, 1f))
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
}
