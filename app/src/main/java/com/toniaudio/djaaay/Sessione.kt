package com.toniaudio.djaaay

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.lang.ref.WeakReference

/**
 * Quello che vive quanto il processo, come il motore nativo: lo stato della schermata, il controllore e la
 * piattaforma. Chiudendo e riaprendo l'app (col processo ancora vivo) la schermata ritrova i brani che il motore ha
 * davvero caricati: prima ripartiva vuota mentre il motore teneva i brani vecchi, e muovendo i dischi si sentivano
 * brani "invisibili".
 */
object Sessione {
    private var stato: StatoApp? = null
    private var controllore: Controllore? = null
    private var attivita = WeakReference<MainActivity>(null)

    /** I lavori in corso (caricamento dei brani…) non dipendono dalla schermata aperta. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Lo stato e il controllore della sessione (creati la prima volta). `nuova` = appena creati. */
    fun prendi(attivita: MainActivity): Triple<StatoApp, Controllore, Boolean> {
        this.attivita = WeakReference(attivita)
        val esistente = controllore
        if (esistente != null) return Triple(stato!!, esistente, false)
        val s = StatoApp()
        val contesto: Context = attivita.applicationContext
        lateinit var piattaforma: PiattaformaAndroid
        piattaforma = PiattaformaAndroid(
            contesto,
            chiediPermesso = { this.attivita.get()?.chiediMusica(piattaforma.permessoDaChiedere()) },
            chiediPermessoAudio = { this.attivita.get()?.chiediIngressoAudio() },
        )
        val c = Controllore(s, PonteNativo, piattaforma, scope)
        stato = s
        controllore = c
        return Triple(s, c, true)
    }

    /** La schermata che se ne va (se è quella attuale): i permessi si chiederanno alla prossima. */
    fun lascia(a: Activity) {
        if (attivita.get() === a) attivita = WeakReference(null)
    }
}
