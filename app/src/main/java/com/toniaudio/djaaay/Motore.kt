package com.toniaudio.djaaay

/** Accesso al motore audio nativo (C++). */
object Motore {
    init {
        System.loadLibrary("djaaay")
    }

    external fun avvia(): Boolean
    external fun ferma()
    external fun sampleRate(): Int
    external fun tonoProva(acceso: Boolean)
}
