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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay

private val Sfondo = Color(0xFF0B0D12)
private val Testo = Color(0xFFE8ECF4)
private val Spento = Color(0xFF8A93A8)
private val Verde = Color(0xFF3DDC84)
private val Rosso = Color(0xFFFF3B47)

class MainActivity : ComponentActivity() {

    private val chiediNotifiche = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

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

        setContent { SchermataProva(audioOk) }
    }

    override fun onDestroy() {
        // Chiusura vera dell'app (non una rotazione): si ferma il servizio, che spegne audio e registrazione
        if (isFinishing) stopService(Intent(this, ServizioAudio::class.java))
        super.onDestroy()
    }

    @Composable
    private fun SchermataProva(audioOk: Boolean) {
        var tono by remember { mutableStateOf(false) }
        var rec by remember { mutableStateOf(Registrazione.attiva) }
        var fileRec by remember { mutableStateOf(Registrazione.nomeFile) }
        var multiplo by remember { mutableIntStateOf(2) }
        var key by remember { mutableStateOf(false) }
        var pitch by remember { mutableIntStateOf(0) }
        var diagnostica by remember { mutableStateOf("") }

        LaunchedEffect(Unit) {
            while (true) {
                diagnostica = Motore.diagnostica()
                rec = Registrazione.attiva
                delay(500)
            }
        }

        Row(Modifier.fillMaxSize().background(Sfondo).padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("DVS", color = Testo, fontSize = 32.sp)
                Text(
                    if (audioOk) "Versione di prova del motore audio" else "Audio non disponibile",
                    color = Spento,
                )
                Button(onClick = { tono = !tono; Motore.tonoProva(tono) }) {
                    Text(if (tono) "Ferma il tono di prova" else "Suona un tono di prova")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Pitch", color = Testo)
                    Spacer(Modifier.width(12.dp))
                    for (p in listOf(-8, 0, 8)) {
                        OutlinedButton(
                            onClick = { pitch = p; Motore.pitch(0, p / 100f) },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = if (p == pitch) Verde else Spento),
                        ) { Text(if (p > 0) "+$p%" else "$p%") }
                        Spacer(Modifier.width(6.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(
                        onClick = { key = !key; Motore.keylock(0, key) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (key) Verde else Spento),
                    ) { Text("🔒 KEY") }
                }
                Button(
                    onClick = {
                        if (rec) {
                            Registrazione.ferma(this@MainActivity)
                            rec = false
                        } else {
                            fileRec = Registrazione.avvia(this@MainActivity)
                            rec = fileRec != null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = if (rec) Rosso else Color(0xFF1B2030)),
                ) {
                    Text(if (rec) "● REC — tocca per fermare" else "REC: registra il mix")
                }
                fileRec?.let {
                    Text(
                        if (rec) "Sto registrando in Musica/DVS/$it" else "Salvato in Musica/DVS/$it",
                        color = Spento,
                        fontSize = 12.sp,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Buffer", color = Testo)
                    Spacer(Modifier.width(12.dp))
                    for (m in listOf(1, 2, 4)) {
                        val scelto = m == multiplo
                        OutlinedButton(
                            onClick = { multiplo = m; Motore.impostaBuffer(m) },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = if (scelto) Verde else Spento),
                        ) { Text("$m×") }
                        Spacer(Modifier.width(6.dp))
                    }
                }
            }
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                Text("Diagnostica", color = Testo, fontSize = 18.sp)
                Spacer(Modifier.height(8.dp))
                Text(diagnostica, color = Spento, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Se \"Interruzioni del suono\" cresce mentre suona, prova un buffer più grande.",
                    color = Spento,
                    fontSize = 12.sp,
                )
            }
        }
    }
}
