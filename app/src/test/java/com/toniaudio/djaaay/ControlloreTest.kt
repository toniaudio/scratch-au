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
}
