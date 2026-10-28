package com.toniaudio.djaaay.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

/** Misure dei comandi del deck: normali (tablet, schermo alto) o grandi (telefono: meno cose, più grandi). */
data class Misure(
    val telefono: Boolean,
    val tasto: Dp, val testoTasto: TextUnit,        // CUE, Play, SYNC
    val pad: Dp, val testoPad: TextUnit,            // hot cue, loop, FX del deck
    val schede: TextUnit, val key: TextUnit,
    val pitch: Dp, val faderPitch: Dp, val capPitch: Dp, val testoPitch: TextUnit,
    val dvsL: Dp, val dvsH: Dp, val testoDvs: TextUnit,
)

val MISURE_NORMALI = Misure(
    telefono = false, tasto = 26.dp, testoTasto = 10.5.sp, pad = 24.dp, testoPad = 9.5.sp, schede = 9.sp, key = 9.5.sp,
    pitch = 52.dp, faderPitch = 30.dp, capPitch = 22.dp, testoPitch = 10.sp, dvsL = 44.dp, dvsH = 22.dp, testoDvs = 8.sp,
)

val MISURE_GRANDI = Misure(
    telefono = true, tasto = 44.dp, testoTasto = 14.sp, pad = 38.dp, testoPad = 13.sp, schede = 11.sp, key = 12.sp,
    pitch = 56.dp, faderPitch = 44.dp, capPitch = 34.dp, testoPitch = 12.sp, dvsL = 54.dp, dvsH = 30.dp, testoDvs = 10.5.sp,
)

val LocalMisure = staticCompositionLocalOf { MISURE_NORMALI }
