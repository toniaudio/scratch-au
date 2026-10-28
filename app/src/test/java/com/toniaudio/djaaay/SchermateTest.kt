package com.toniaudio.djaaay

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.toniaudio.djaaay.ui.SchermataDJ
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * "Foto" della schermata vera (stesso codice dell'app) su un Pixel in orizzontale, con brani finti:
 * finiscono in app/build/schermate/ e il workflow le pubblica per confrontarle con le bozze in design/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w914dp-h411dp-land-420dpi")
class SchermateTest {
    @get:Rule
    val regola = createComposeRule()

    private val brani = listOf(
        branoFinto(1, "Funky Drummer Break", "Classic Breaks", 252, "Music/Breaks"),
        branoFinto(2, "Ahhh Fresh (Scratch Tool)", "Skratch Sounds", 118, "Music/Scratch tools"),
        branoFinto(3, "Impeach the Groove", "Classic Breaks", 211, "Music/Breaks"),
        branoFinto(4, "Late Night Cuts", "DJ Toni", 305, "Music"),
        branoFinto(5, "Boom Bap Loop 03", "Beat Lab", 140, "Download"),
        branoFinto(6, "Sunday Groove", "Mr Funk", 288, "Music"),
        branoFinto(7, "Battle Weapon Vol.2", "Skratch Sounds", 372, "Music/Scratch tools"),
    )

    private fun prepara(): Pair<StatoApp, Controllore> {
        val stato = StatoApp()
        val c = Controllore(stato, PonteFinto(), PiattaformaFinta(brani), CoroutineScope(Dispatchers.Unconfined))
        stato.libreria = brani
        stato.bpmNoti = mapOf(brani[0].chiave to 98.0, brani[1].chiave to 98.0, brani[2].chiave to 96.4, brani[5].chiave to 104.2)
        stato.recenti = listOf(2, 1)
        val a = stato.deck[0]
        a.brano = brani[0]; a.bpm = 98.0; a.primoBattito = 0.37; a.durata = 252.0; a.posizione = 85.2
        a.waveform = waveformFinta(252.0, 98.0, 0.37, 1); a.inPlay = true; a.livello = 0.55f
        a.hotCue[0] = 80.1; a.hotCue[1] = 88.3; a.hotCue[2] = 91.0
        val b = stato.deck[1]
        b.brano = brani[1]; b.bpm = 96.0; b.primoBattito = 0.1; b.durata = 118.0; b.posizione = 37.4
        b.waveform = waveformFinta(118.0, 96.0, 0.1, 2); b.pitch = 0.021f; b.keylock = true; b.livello = 0.4f
        b.tabPad = 1; b.loopAttivo = true; b.loopInizio = 37.0
        stato.mixer.canale[0].bassi = 0.15f
        stato.mixer.canale[0].filtro = 0.35f
        stato.mixer.canale[1].volume = 0.7f
        stato.mixer.crossfader = 0.12f
        stato.mixer.livelloL = 0.5f
        stato.mixer.livelloR = 0.47f
        return stato to c
    }

    private fun foto(nome: String, prima: (StatoApp, Controllore) -> Unit = { _, _ -> }) {
        val (stato, c) = prepara()
        prima(stato, c)
        regola.setContent { SchermataDJ(stato, c) }
        regola.waitForIdle()
        regola.onRoot().captureRoboImage("build/schermate/$nome.png")
    }

    @Test fun principale() = foto("1-principale")

    @Test fun libreriaDeckA() = foto("2-libreria-deck-a") { s, _ -> s.deck[0].libreriaAperta = true }

    @Test fun libreriaCartelleDeckB() = foto("3-libreria-deck-b") { s, _ -> s.deck[1].libreriaAperta = true; s.deck[1].inPlay = false }

    @Test fun opzioni() = foto("4-opzioni") { s, _ -> s.opzioniAperte = true }

    @Test fun opzioniAudio() = foto("5-opzioni-audio") { s, c -> s.opzioniAperte = true; s.schedaOpzioni = 1; c.aggiornaDiagnostica() }

    @Test fun fx() = foto("6-fx") { s, c ->
        s.mixer.tab = 1
        s.deck[0].tabPad = 2
        s.deck[1].tabPad = 2
        c.fxPadTocco(0, 1)  // DUB DELAY acceso sul deck A
        c.fxTipo(1, 3)  // STRETCH scelto sul deck B, spento
    }

    @Test fun fxEchoReverb() = foto("6b-fx-echo-reverb") { s, c ->
        s.mixer.tab = 1
        c.fxPadTocco(0, 0)
        c.fxPadTocco(1, 2)
    }

    @Test fun midiLearn() = foto("8-midi-learn") { s, c ->
        c.midiCollegato("M-Audio X-Session Pro")
        c.learnAttiva(true)
        c.learnScegli(AzioneMidi.CROSSFADER, 0)
    }

    @Test fun opzioniMidi() = foto("9-opzioni-midi") { s, c ->
        c.midiCollegato("M-Audio X-Session Pro")
        c.learnScegli(AzioneMidi.CROSSFADER, 0)
        c.midi(MessaggioMidi(SorgenteMidi(false, 0, 8), 64))
        c.learnScegli(AzioneMidi.VOLUME, 0)
        c.midi(MessaggioMidi(SorgenteMidi(false, 0, 1), 100))
        c.learnScegli(AzioneMidi.PLAY, 0)
        c.midi(MessaggioMidi(SorgenteMidi(true, 0, 46), 127))
        c.learnScegli(AzioneMidi.GAIN, 0)
        s.messaggio = null
        s.opzioniAperte = true
        s.schedaOpzioni = 2
    }

    @Test fun opzioniDvs() = foto("10-opzioni-dvs") { s, c ->
        c.dvs(0)  // con la scheda finta (Behringer UMC404HD) collegata
        s.messaggio = null
        val d = s.dvs
        d.qualita = 0.96f
        d.velocita = 1.003f
        d.posizione = 102.6
        d.livello = 0.35f
        d.guadagnoDb = 6f
        for (i in 0 until StatoDvs.PUNTI_SCOPE) {
            val a = i * 0.31
            val r = 0.8 + 0.04 * kotlin.math.sin(i * 1.7)
            d.scope[2 * i] = (r * kotlin.math.cos(a)).toFloat()
            d.scope[2 * i + 1] = (r * kotlin.math.sin(a)).toFloat()
        }
        s.opzioniAperte = true
        s.schedaOpzioni = 3
    }

    @Test fun vuoto() = foto("7-appena-aperta") { s, _ ->
        for (d in s.deck) { d.brano = null; d.waveform = null; d.bpm = 0.0; d.posizione = 0.0; d.durata = 0.0; d.inPlay = false; d.livello = 0f }
        s.deck[1].caricamento = 0.4f
        s.mixer.livelloL = 0f; s.mixer.livelloR = 0f
    }
}
