package com.toniaudio.djaaay

/**
 * Tutto quello che l'interfaccia chiede al motore audio passa da qui. In app è [PonteNativo];
 * nei test (logica e "foto" della schermata) un ponte finto, così non serve il motore C++.
 */
interface Ponte {
    fun comando(deck: Int, tipo: Int, a: Double = 0.0, b: Double = 0.0)
    fun impostaDeck(deck: Int, parametro: Int, valore: Float)
    fun impostaMixer(parametro: Int, canale: Int, valore: Float)
    fun impostaFx(deck: Int, parametro: Int, tipo: Int, valore: Float)
    fun impostaDvs(parametro: Int, valore: Float)
    /** 8 valori (vedi djaaay_jni.cpp) e poi lo scope: 256 punti (x, y). */
    fun statoDvs(dest: FloatArray)
    fun stato(dest: DoubleArray)
}

/** Codici dei comandi del deck: stesso ordine di ComandoDeck::Tipo in engine/Deck.h. */
object Cmd {
    const val PLAY = 1
    const val PLAY_PAUSA = 2
    const val CUE_GIU = 3
    const val CUE_SU = 4
    const val VAI = 5
    const val TOCCO_INIZIO = 6
    const val TOCCO_SPOSTA = 7
    const val TOCCO_FINE = 8
    const val IMPOSTA_CUE = 9
    const val LOOP = 10
}

/** Parametri di impostaDeck. */
object ParDeck {
    const val PITCH = 0
    const val FRIZIONE = 1
    const val AVVIO = 2
    const val FRENATA = 3
    const val KEYLOCK = 4
    const val DVS = 5
}

/** Parametri di impostaDvs. */
object ParDvs {
    const val TIPO = 0
    const val GIRI45 = 1
    const val ASSOLUTA = 2
    const val SENSIBILITA = 3
    const val PROTEZIONE = 4
    const val GUADAGNO = 5
    const val CALIBRA = 6
    const val AZZERA = 7
}

/** Parametri di impostaMixer. */
object ParMixer {
    const val GAIN = 0
    const val ALTI = 1
    const val MEDI = 2
    const val BASSI = 3
    const val FILTRO = 4
    const val VOLUME = 5
    const val CROSSFADER = 10
    const val CURVA = 11
    const val INVERTI = 12
    const val MASTER = 13
}

/** Parametri di impostaFx (vedi djaaay_jni.cpp): i parametri "per tipo" valgono per l'effetto `tipo`. */
object ParFx {
    const val ACCESO = 0
    const val TIPO = 1
    const val DRY_WET = 2
    const val PARAM1 = 3
    const val PARAM2 = 4
    const val SCELTA = 5
    const val BATTITO = 6
}

object PonteNativo : Ponte {
    override fun comando(deck: Int, tipo: Int, a: Double, b: Double) = Motore.comando(deck, tipo, a, b)
    override fun impostaDeck(deck: Int, parametro: Int, valore: Float) = Motore.impostaDeck(deck, parametro, valore)
    override fun impostaMixer(parametro: Int, canale: Int, valore: Float) = Motore.impostaMixer(parametro, canale, valore)
    override fun impostaFx(deck: Int, parametro: Int, tipo: Int, valore: Float) = Motore.impostaFx(deck, parametro, tipo, valore)
    override fun impostaDvs(parametro: Int, valore: Float) = Motore.impostaDvs(parametro, valore)
    override fun statoDvs(dest: FloatArray) = Motore.statoDvs(dest)
    override fun stato(dest: DoubleArray) = Motore.stato(dest)
}
