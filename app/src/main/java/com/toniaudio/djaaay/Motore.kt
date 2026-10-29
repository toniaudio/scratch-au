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

    // --- caricamento dei brani (vedi PiattaformaAndroid.carica): il brano si può suonare mentre si decodifica
    /** `cache` = file di analisi del brano: se c'è, waveform e BPM sono pronti subito. */
    external fun nuovaTraccia(sampleRate: Int, frameAttesi: Long, cache: String?): Long
    external fun aggiungiPcm(handle: Long, buffer: java.nio.ByteBuffer, offset: Int, byte: Int, canali: Int, formatoFloat: Boolean): Boolean
    external fun frequenzaTraccia(handle: Long, sampleRate: Int)
    /** Analizza la parte arrivata (da un altro thread rispetto alla decodifica); restituisce i secondi decodificati. */
    external fun analizza(handle: Long): Double
    /** Dopo la decodifica: analisi finita e salvata in `cache`. [bpm, prima battuta in s, durata in s] */
    external fun concludi(handle: Long, cache: String?): DoubleArray
    /** [bpm, prima battuta, frequenza del brano]; bpm 0 = non ancora noto. */
    external fun tempo(handle: Long): DoubleArray
    /** Istanti delle battute (s) se il tempo del brano cambia; vuoto = griglia fissa (bpm e prima battuta). */
    external fun battiti(handle: Long): DoubleArray
    /** Per livello di zoom: [bassi…, medi…, alti…], colonne di 64·2^livello frame del brano (fin dove è arrivata). */
    external fun waveform(handle: Long): Array<ByteArray>
    external fun caricaNelDeck(handle: Long, deck: Int)
    /** Fine del caricamento: il deck o il campionatore che usa il brano lo tiene. */
    external fun scartaTraccia(handle: Long)

    // --- comandi e stato (vedi Ponte)
    external fun comando(deck: Int, tipo: Int, a: Double, b: Double)
    external fun impostaDeck(deck: Int, parametro: Int, valore: Float)
    external fun impostaMixer(parametro: Int, canale: Int, valore: Float)
    external fun impostaFx(deck: Int, parametro: Int, tipo: Int, valore: Float)

    // --- DVS: ingresso della scheda audio (coppia 0 = ingressi 1-2, 1 = 3-4); "" = aperto, altrimenti il problema
    external fun dvsApri(deviceId: Int, coppia: Int): String
    external fun dvsChiudi()
    external fun impostaDvs(parametro: Int, valore: Float)
    external fun statoDvs(dest: FloatArray)
    /** Istantanea per l'interfaccia, 37 valori (vedi djaaay_jni.cpp). */
    external fun stato(dest: DoubleArray)

    /** Pre-ascolto: uscita a 4 canali sulla scheda (master 1-2, cuffie 3-4) o di nuovo stereo. "" = fatto. */
    external fun uscitaCuffie(attiva: Boolean, deviceId: Int): String

    /** Campionatore: tipo 0 premi, 1 lascia, 2 togli, 3 modo (valore), 4 ferma tutti. */
    external fun campionatore(tipo: Int, pad: Int, valore: Int)
    external fun caricaNelCampionatore(handle: Long, pad: Int)

    /** Il motore diventa proprietario del file descriptor e lo chiude sempre. */
    external fun avviaRegistrazione(fd: Int): Boolean
    external fun fermaRegistrazione()
    external fun registrazioneAttiva(): Boolean
}
