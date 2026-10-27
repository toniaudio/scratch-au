package com.toniaudio.djaaay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.lifecycle.lifecycleScope
import com.toniaudio.djaaay.ui.SchermataDJ

class MainActivity : ComponentActivity() {

    private val chiediNotifiche = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val chiediMusica = registerForActivityResult(ActivityResultContracts.RequestPermission()) { concesso ->
        if (concesso) controllore.aggiornaLibreria()
        else controllore.mostra("Senza il permesso DVS non può leggere la musica del telefono")
    }

    private val stato = StatoApp()
    private lateinit var piattaforma: PiattaformaAndroid
    private lateinit var controllore: Controllore

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

        piattaforma = PiattaformaAndroid(this) { chiediMusica.launch(piattaforma.permessoDaChiedere()) }
        controllore = Controllore(stato, PonteNativo, piattaforma, lifecycleScope)
        Motore.impostaBuffer(stato.opzioni.buffer)
        if (!audioOk) controllore.mostra("Audio non disponibile: riavvia l'app")
        controllore.aggiornaLibreria()

        setContent {
            // a ogni fotogramma: posizione dei deck, livelli, REC
            LaunchedEffect(Unit) {
                while (true) withFrameNanos { controllore.aggiornaDaMotore() }
            }
            SchermataDJ(stato, controllore)
        }
    }

    override fun onDestroy() {
        // Chiusura vera dell'app (non una rotazione): si ferma il servizio, che spegne audio e registrazione
        if (isFinishing) stopService(Intent(this, ServizioAudio::class.java))
        super.onDestroy()
    }
}
