package com.toniaudio.djaaay

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Piattaforma vera: brani dal MediaStore, decodifica con MediaExtractor/MediaCodec, analisi nel motore C++,
 * REC in Musica/DVS, preferenze in SharedPreferences.
 */
class PiattaformaAndroid(
    private val context: Context,
    private val chiediPermesso: () -> Unit,
    private val chiediPermessoAudio: () -> Unit,
) : Piattaforma {

    private val preferenze = context.getSharedPreferences("dvs", Context.MODE_PRIVATE)

    private val permesso: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    override fun haPermessoLibreria() =
        ContextCompat.checkSelfPermission(context, permesso) == PackageManager.PERMISSION_GRANTED

    override fun chiediPermessoLibreria() = chiediPermesso()

    /** Nome del permesso da chiedere all'utente (lo usa l'Activity). */
    fun permessoDaChiedere() = permesso

    override suspend fun leggiLibreria(): List<Brano> = withContext(Dispatchers.IO) {
        val brani = mutableListOf<Brano>()
        val colonne = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
        )
        val colonnaCartella =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Audio.Media.RELATIVE_PATH else MediaStore.Audio.Media.DATA
        colonne += colonnaCartella
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            colonne.toTypedArray(),
            "${MediaStore.Audio.Media.DURATION} >= ?",
            arrayOf("3000"),
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iTitolo = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iArtista = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iDurata = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iDim = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val iMod = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val iCartella = c.getColumnIndexOrThrow(colonnaCartella)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val artista = c.getString(iArtista)?.takeUnless { it == "<unknown>" } ?: ""
                var cartella = c.getString(iCartella) ?: ""
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) cartella = File(cartella).parent ?: ""
                brani += Brano(
                    id = id,
                    uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                    titolo = c.getString(iTitolo) ?: "Senza titolo",
                    artista = artista,
                    durataMs = c.getLong(iDurata),
                    cartella = cartella.trimEnd('/'),
                    dimensione = c.getLong(iDim),
                    modificato = c.getLong(iMod),
                )
            }
        }
        brani
    }

    override suspend fun carica(brano: Brano, progresso: (Float) -> Unit): BranoCaricato = withContext(Dispatchers.IO) {
        val estrattore = MediaExtractor()
        var codec: MediaCodec? = null
        var handle = 0L
        try {
            estrattore.setDataSource(context, brano.uri, null)
            val traccia = (0 until estrattore.trackCount).firstOrNull {
                estrattore.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("nessuna traccia audio")
            estrattore.selectTrack(traccia)
            val formato = estrattore.getTrackFormat(traccia)
            val mime = formato.getString(MediaFormat.KEY_MIME)!!
            val durataUs = if (formato.containsKey(MediaFormat.KEY_DURATION)) formato.getLong(MediaFormat.KEY_DURATION) else brano.durataMs * 1000
            var frequenza = formato.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var canali = formato.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var inFloat = false
            handle = Motore.nuovaTraccia(frequenza, durataUs * frequenza / 1_000_000 + 4096)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(formato, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var fineIngresso = false
            var fineUscita = false
            var ultimoProgresso = -1
            while (!fineUscita) {
                ensureActive()  // caricamento annullato (es. altro brano scelto)
                if (!fineIngresso) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buffer = codec.getInputBuffer(i)!!
                        val n = estrattore.readSampleData(buffer, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            fineIngresso = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, estrattore.sampleTime, 0)
                            if (durataUs > 0) {
                                val p = (estrattore.sampleTime * 100 / durataUs).toInt()
                                if (p != ultimoProgresso) {
                                    ultimoProgresso = p
                                    withContext(Dispatchers.Main) { progresso(0.9f * p / 100f) }
                                }
                            }
                            estrattore.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o >= 0 -> {
                        if (info.size > 0) {
                            val uscita = codec.getOutputBuffer(o)!!
                            Motore.aggiungiPcm(handle, uscita, info.offset, info.size, canali, inFloat)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) fineUscita = true
                    }
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // il formato vero (es. AAC con SBR: frequenza doppia) si conosce solo qui
                        val f = codec.outputFormat
                        frequenza = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        canali = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        inFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        Motore.frequenzaTraccia(handle, frequenza)
                    }
                }
            }
            withContext(Dispatchers.Main) { progresso(0.92f) }
            // waveform e BPM: dal file di analisi se il brano è già stato caricato, altrimenti calcolati e salvati
            val cartella = File(context.cacheDir, "analisi").apply { mkdirs() }
            val analisi = Motore.analizza(handle, File(cartella, "${brano.chiave}.dvsw").absolutePath)
            val waveform = Waveform(Motore.waveform(handle).toList(), frequenza)
            BranoCaricato(handle, analisi[0], analisi[1], analisi[2], waveform).also { handle = 0L }
        } finally {
            if (handle != 0L) Motore.scartaTraccia(handle)  // errore o annullamento: si libera la memoria
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            codec?.release()
            estrattore.release()
        }
    }

    override fun mettiNelDeck(caricato: BranoCaricato, deck: Int) = Motore.caricaNelDeck(caricato.handle, deck)

    override fun scarta(caricato: BranoCaricato) = Motore.scartaTraccia(caricato.handle)

    override fun avviaRec(): String? = Registrazione.avvia(context)

    override fun fermaRec() = Registrazione.ferma(context)

    override fun impostaBuffer(multiplo: Int) = Motore.impostaBuffer(multiplo)

    override fun diagnostica(): String = Motore.diagnostica()

    override fun leggiPreferenza(chiave: String): String? = preferenze.getString(chiave, null)

    override fun salvaPreferenza(chiave: String, valore: String) {
        preferenze.edit().putString(chiave, valore).apply()
    }

    // ------------------------------------------------------------------ DVS
    override fun haPermessoIngresso() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun chiediPermessoIngresso() = chiediPermessoAudio()

    override fun schedeIngresso(): List<SchedaAudio> {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val usb = setOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY)
        return audio.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.type in usb || it.type == AudioDeviceInfo.TYPE_LINE_ANALOG || it.type == AudioDeviceInfo.TYPE_LINE_DIGITAL }
            .map { d ->
                // canali: il massimo dichiarato (lista vuota = qualsiasi numero)
                val canali = d.channelCounts.maxOrNull() ?: 2
                SchedaAudio(d.id, d.productName?.toString()?.ifBlank { null } ?: "Scheda audio", canali, d.type in usb)
            }
            .distinctBy { it.id }
    }

    override fun apriIngresso(id: Int, coppia: Int): String = Motore.dvsApri(id, coppia)

    override fun chiudiIngresso() = Motore.dvsChiudi()
}
