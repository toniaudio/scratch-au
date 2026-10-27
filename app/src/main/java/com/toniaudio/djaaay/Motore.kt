package com.toniaudio.djaaay

/** Accesso al motore audio nativo (C++). Il flusso audio è unico per tutta l'app. */
object Motore {
    init {
        System.loadLibrary("djaaay")
    }

    /** Apre l'audio (se è già aperto non fa niente, la musica non si interrompe). */
    external fun avvia(): Boolean
    external fun ferma()
    external fun sampleRate(): Int
    external fun tonoProva(acceso: Boolean)

    /** KEY: con il keylock acceso cambiando il pitch cambia solo la velocità, non il tono. deck 0 = A, 1 = B. */
    external fun keylock(deck: Int, acceso: Boolean)
    /** Pitch del deck: −0,5…+0,5 (0,08 = +8%). */
    external fun pitch(deck: Int, valore: Float)

    /** Buffer = multiplo × burst del dispositivo: più piccolo = meno ritardo, più grande = più stabile. */
    external fun impostaBuffer(multiplo: Int)
    external fun diagnostica(): String

    /** Il motore diventa proprietario del file descriptor e lo chiude sempre. */
    external fun avviaRegistrazione(fd: Int): Boolean
    external fun fermaRegistrazione()
    external fun registrazioneAttiva(): Boolean
}
