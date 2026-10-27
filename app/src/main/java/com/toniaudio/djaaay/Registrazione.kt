package com.toniaudio.djaaay

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * REC: salva il mix in un file WAV nella cartella Musica/DVS del telefono (visibile dall'app File e dai lettori).
 * Il file resta "in preparazione" (nascosto) finché la registrazione non è chiusa e completa.
 */
object Registrazione {
    private var uri: Uri? = null
    var nomeFile: String? = null
        private set

    val attiva: Boolean get() = Motore.registrazioneAttiva()

    /** Avvia la registrazione; restituisce il nome del file o null se non è stato possibile. */
    fun avvia(context: Context): String? {
        if (attiva) return nomeFile
        val nome = "DVS-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ITALY).format(Date()) + ".wav"
        val resolver = context.contentResolver
        val pfd: ParcelFileDescriptor
        var nuovoUri: Uri? = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val valori = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, nome)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/x-wav")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/DVS")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                nuovoUri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), valori)
                    ?: return null
                pfd = resolver.openFileDescriptor(nuovoUri, "rw") ?: run {
                    resolver.delete(nuovoUri, null, null)
                    return null
                }
            } else {
                val cartella = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: return null
                pfd = ParcelFileDescriptor.open(
                    File(cartella, nome),
                    ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE,
                )
            }
        } catch (e: Exception) {
            nuovoUri?.let { resolver.delete(it, null, null) }
            return null
        }
        if (!Motore.avviaRegistrazione(pfd.detachFd())) {
            nuovoUri?.let { resolver.delete(it, null, null) }
            return null
        }
        uri = nuovoUri
        nomeFile = nome
        return nome
    }

    /** Ferma la registrazione: il file viene completato e diventa visibile. */
    fun ferma(context: Context) {
        Motore.fermaRegistrazione()
        uri?.let {
            val valori = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
            context.contentResolver.update(it, valori, null, null)
        }
        uri = null
    }
}
