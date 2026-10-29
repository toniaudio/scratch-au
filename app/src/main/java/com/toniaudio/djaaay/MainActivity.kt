package com.toniaudio.djaaay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.toniaudio.djaaay.ui.SchermataDJ

class MainActivity : ComponentActivity() {

    private val chiediNotifiche = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val chiediMusica = registerForActivityResult(ActivityResultContracts.RequestPermission()) { concesso ->
        if (concesso) controllore.aggiornaLibreria()
        else controllore.mostra("Senza il permesso DVS non può leggere la musica del telefono")
    }

    private val chiediAudio = registerForActivityResult(ActivityResultContracts.RequestPermission()) { concesso ->
        controllore.permessoIngresso(concesso)
    }

    // schede audio USB collegate o scollegate (DVS)
    private val schedeAudio = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = controllore.dvsAggiornaSchede()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = controllore.dvsAggiornaSchede()
    }

    private lateinit var stato: StatoApp
    private lateinit var controllore: Controllore
    private var midi: MidiUsb? = null

    /** Per la piattaforma (vedi Sessione): i permessi si chiedono dalla schermata aperta. */
    fun chiediMusica(permesso: String) = chiediMusica.launch(permesso)
    fun chiediIngressoAudio() = chiediAudio.launch(Manifest.permission.RECORD_AUDIO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Durante un set lo schermo non deve spegnersi, e le barre di sistema non devono rubare spazio
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // La notifica del servizio audio (Android 13+ chiede il permesso; se negato il servizio funziona lo stesso)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            chiediNotifiche.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val audioOk = Motore.avvia()
        ContextCompat.startForegroundService(this, Intent(this, ServizioAudio::class.java))

        // stato e controllore vivono quanto il motore (il processo): riaprendo l'app si ritrova com'era
        val (s, c, nuova) = Sessione.prendi(this)
        stato = s
        controllore = c
        if (!nuova) controllore.riapertura()
        Motore.impostaBuffer(stato.opzioni.buffer)
        if (!audioOk) controllore.mostra("Audio non disponibile: riavvia l'app")
        controllore.aggiornaLibreria()
        midi = MidiUsb(this, { controllore.midi(it) }, { controllore.midiCollegato(it) }).also { it.avvia() }
        getSystemService(AudioManager::class.java).registerAudioDeviceCallback(schedeAudio, Handler(Looper.getMainLooper()))

        setContent {
            // a ogni fotogramma: posizione dei deck, livelli, REC
            LaunchedEffect(Unit) {
                while (true) withFrameNanos { controllore.aggiornaDaMotore() }
            }
            SchermataDJ(stato, controllore)
        }
    }

    override fun onDestroy() {
        midi?.ferma()
        getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(schedeAudio)
        // Chiusura vera dell'app (non una rotazione): deck e campionatore si fermano, effetti spenti (riaprendo non
        // riparte niente da solo); si ferma il servizio, che spegne audio e registrazione
        if (isFinishing) {
            controllore.chiusura()
            stopService(Intent(this, ServizioAudio::class.java))
        }
        Sessione.lascia(this)
        super.onDestroy()
    }
}
