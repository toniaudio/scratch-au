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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
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

    /**
     * Decodifica a pezzi: appena c'è il primo secondo il brano va nel deck e si può suonare; la decodifica continua
     * (il codec riceve sempre tutti i pezzi che può, senza attese inutili) e intanto un altro thread analizza
     * waveform e BPM. Se il brano è già stato analizzato una volta, waveform e BPM arrivano subito dal file.
     */
    override suspend fun carica(brano: Brano, aggiorna: (BranoCaricato) -> Unit): Unit = withContext(Dispatchers.IO) {
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
            val cache = File(File(context.cacheDir, "analisi").apply { mkdirs() }, "${brano.chiave}.dvsw").absolutePath
            handle = Motore.nuovaTraccia(frequenza, durataUs * frequenza / 1_000_000, cache)
            val h = handle
            val durataPrevista = durataUs / 1e6

            val dec = MediaCodec.createDecoderByType(mime)
            codec = dec
            dec.configure(formato, null, null, 0)
            dec.start()

            coroutineScope {
                // analisi in parallelo alla decodifica: legge solo la parte già decodificata
                val decodificaFinita = CompletableDeferred<Unit>()
                val analisi = launch(Dispatchers.Default) {
                    var ultimoInvio = 0L
                    var inviato = false
                    while (!decodificaFinita.isCompleted) {
                        val secondi = Motore.analizza(h)
                        val ora = System.nanoTime()
                        // il primo aggiornamento appena c'è un secondo di musica, poi ~4 volte al secondo
                        if ((!inviato && secondi >= 1.0) || (inviato && ora - ultimoInvio > 250_000_000L)) {
                            inviato = true
                            ultimoInvio = ora
                            val tempo = Motore.tempo(h)
                            val wf = Waveform(Motore.waveform(h).toList(), tempo[2].toInt())
                            val progresso = if (durataPrevista > 0) (secondi / durataPrevista).toFloat().coerceIn(0f, 0.99f) else 0f
                            withContext(Dispatchers.Main) {
                                aggiorna(BranoCaricato(h, tempo[0], tempo[1], maxOf(durataPrevista, secondi), wf, progresso, false))
                            }
                        }
                        delay(15)
                    }
                    // fine: analisi completata (e salvata), durata vera
                    val r = Motore.concludi(h, cache)
                    val wf = Waveform(Motore.waveform(h).toList(), Motore.tempo(h)[2].toInt())
                    withContext(Dispatchers.Main) { aggiorna(BranoCaricato(h, r[0], r[1], r[2], wf, 1f, true)) }
                }

                val info = MediaCodec.BufferInfo()
                var fineIngresso = false
                var fineUscita = false
                while (!fineUscita) {
                    ensureActive()  // caricamento annullato (es. altro brano scelto)
                    var lavorato = false
                    // tutti i pezzi che il codec può prendere, senza aspettare
                    while (!fineIngresso) {
                        val i = dec.dequeueInputBuffer(0)
                        if (i < 0) break
                        lavorato = true
                        val n = estrattore.readSampleData(dec.getInputBuffer(i)!!, 0)
                        if (n < 0) {
                            dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            fineIngresso = true
                        } else {
                            dec.queueInputBuffer(i, 0, n, estrattore.sampleTime, 0)
                            estrattore.advance()
                        }
                    }
                    // tutto quello che è pronto in uscita; si aspetta un attimo solo se non c'era niente da fare
                    while (true) {
                        val o = dec.dequeueOutputBuffer(info, if (lavorato) 0L else 2_000L)
                        if (o == MediaCodec.INFO_TRY_AGAIN_LATER) break
                        lavorato = true
                        if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            // il formato vero (es. AAC con SBR: frequenza doppia) si conosce solo qui
                            val f = dec.outputFormat
                            frequenza = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            canali = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            inFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                            Motore.frequenzaTraccia(h, frequenza)
                        } else if (o >= 0) {
                            if (info.size > 0) Motore.aggiungiPcm(h, dec.getOutputBuffer(o)!!, info.offset, info.size, canali, inFloat)
                            dec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                fineUscita = true
                                break
                            }
                        }
                    }
                }
                decodificaFinita.complete(Unit)
                analisi.join()
            }
        } finally {
            // finito, annullato o errore: il deck che usa il brano lo tiene, il resto si libera
            if (handle != 0L) Motore.scartaTraccia(handle)
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            codec?.release()
            estrattore.release()
        }
    }

    override fun mettiNelDeck(caricato: BranoCaricato, deck: Int) = Motore.caricaNelDeck(caricato.handle, deck)

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

    // ------------------------------------------------------------------ pre-ascolto e campionatore
    override fun schedeUscita(): List<SchedaAudio> {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val usb = setOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY)
        return audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type in usb }
            .map { d -> SchedaAudio(d.id, d.productName?.toString()?.ifBlank { null } ?: "Scheda audio", d.channelCounts.maxOrNull() ?: 2, true) }
            // lista dei canali vuota = qualsiasi numero: si prova
            .filter { it.canali >= 4 || audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).first { d -> d.id == it.id }.channelCounts.isEmpty() }
    }

    override fun apriCuffie(attiva: Boolean, id: Int): String = Motore.uscitaCuffie(attiva, id)

    override fun mettiNelCampionatore(caricato: BranoCaricato, pad: Int) = Motore.caricaNelCampionatore(caricato.handle, pad)

    override fun chiudiIngresso() = Motore.dvsChiudi()
}
