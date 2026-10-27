package com.toniaudio.djaaay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Servizio "in primo piano": tiene viva l'app (e la musica) anche a schermo spento o passando ad altre app.
 * Android mostra la notifica fissa "DVS"; toccandola si torna all'app. Chiudendo l'app dalle recenti si ferma tutto.
 */
class ServizioAudio : Service() {

    override fun onCreate() {
        super.onCreate()
        val gestore = getSystemService(NotificationManager::class.java)
        gestore.createNotificationChannel(
            NotificationChannel(CANALE, "Audio attivo", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Mostrata mentre DVS suona"
                setShowBadge(false)
            },
        )
        val apri = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notifica = NotificationCompat.Builder(this, CANALE)
            .setSmallIcon(R.drawable.ic_notifica)
            .setContentTitle("DVS")
            .setContentText("La musica continua anche a schermo spento")
            .setContentIntent(apri)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        ServiceCompat.startForeground(
            this, ID_NOTIFICA, notifica,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    // App chiusa dalla schermata delle app recenti: si ferma tutto
    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
    }

    override fun onDestroy() {
        Registrazione.ferma(this)
        Motore.ferma()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val CANALE = "audio"
        const val ID_NOTIFICA = 1
    }
}
