package com.toniaudio.djaaay.ui

import androidx.compose.ui.graphics.Color

/** Colori decisi nelle bozze (design/): interfaccia neutra, colore solo nelle waveform e al centro dei dischi. */
object Colori {
    val Sfondo = Color(0xFF0B0D12)
    val Barra = Color(0xFF0E1118)
    val Pannello = Color(0xFF141821)
    val Pannello2 = Color(0xFF1B2030)
    val Linea = Color(0xFF262C3D)
    val Testo = Color(0xFFE8ECF4)
    val Spento = Color(0xFF8A93A8)
    val Scuro = Color(0xFF5A6378)
    val Neutro = Color(0xFFC9CFDB)
    val Verde = Color(0xFF3DDC84)
    val Arancio = Color(0xFFFFB020)
    val RossoMeter = Color(0xFFFF4040)
    val Rec = Color(0xFFFF3B47)

    val DeckA = Color(0xFFFF3B47)
    val DeckABassi = Color(0xFF5C1219)
    val DeckB = Color(0xFF3D7BFF)
    val DeckBBassi = Color(0xFF0F2766)
    val Alti = Color(0xFFE9F6FF)

    val HotCue = listOf(Color(0xFFFFB020), Color(0xFF3DDC84), Color(0xFFB07CFF), Color(0xFFFF4FD8))

    fun deck(i: Int) = if (i == 0) DeckA else DeckB
    fun deckBassi(i: Int) = if (i == 0) DeckABassi else DeckBBassi
}
