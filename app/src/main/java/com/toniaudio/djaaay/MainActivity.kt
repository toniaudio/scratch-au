package com.toniaudio.djaaay

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Durante un set lo schermo non deve spegnersi, e le barre di sistema non devono rubare spazio
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val audioOk = Motore.avvia()
        setContent {
            var acceso by remember { mutableStateOf(false) }
            Column(
                Modifier.fillMaxSize().background(Color(0xFF0B0D12)),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("DVS", color = Color.White, fontSize = 32.sp)
                Text(
                    if (audioOk) "Audio pronto: ${Motore.sampleRate()} Hz" else "Audio non disponibile",
                    color = Color(0xFF8A93A8),
                )
                Button(onClick = { acceso = !acceso; Motore.tonoProva(acceso) }) {
                    Text(if (acceso) "Ferma il tono" else "Suona un tono di prova")
                }
            }
        }
    }

    override fun onDestroy() {
        Motore.ferma()
        super.onDestroy()
    }
}
