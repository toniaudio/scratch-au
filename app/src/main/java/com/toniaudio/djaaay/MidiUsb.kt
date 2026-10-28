package com.toniaudio.djaaay

import android.content.Context
import android.content.pm.PackageManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper

/**
 * Controller MIDI (es. M-Audio X-Session Pro) via USB: Android li vede da solo se sono "class compliant".
 * Si apre il primo controller che manda messaggi; quando si scollega si passa al successivo.
 * I messaggi arrivano sul thread dell'interfaccia.
 */
class MidiUsb(
    context: Context,
    private val messaggio: (MessaggioMidi) -> Unit,
    private val collegato: (String?) -> Unit,
) {
    private val principale = Handler(Looper.getMainLooper())
    private val manager: MidiManager? =
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)) {
            context.getSystemService(Context.MIDI_SERVICE) as? MidiManager
        } else {
            null
        }
    private var dispositivo: MidiDevice? = null
    private var porta: MidiOutputPort? = null
    private var info: MidiDeviceInfo? = null

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) {
            if (info == null) apri(device)
        }

        override fun onDeviceRemoved(device: MidiDeviceInfo) {
            if (device.id == info?.id) {
                chiudi()
                collegato(null)
                cercaAltro()
            }
        }
    }

    fun avvia() {
        val m = manager ?: return
        m.registerDeviceCallback(callback, principale)
        cercaAltro()
    }

    fun ferma() {
        manager?.unregisterDeviceCallback(callback)
        chiudi()
    }

    @Suppress("DEPRECATION")  // getDevicesForTransport c'è solo da Android 13
    private fun cercaAltro() {
        manager?.devices?.firstOrNull { it.outputPortCount > 0 }?.let { apri(it) }
    }

    private fun apri(device: MidiDeviceInfo) {
        if (device.outputPortCount == 0) return  // non manda niente (es. solo sintetizzatore)
        val m = manager ?: return
        info = device
        m.openDevice(device, { aperto ->
            if (aperto == null || info?.id != device.id) {
                aperto?.close()
                if (info?.id == device.id) info = null
                return@openDevice
            }
            dispositivo = aperto
            // la "porta di uscita" del controller è quella da cui arrivano i suoi messaggi
            val p = aperto.openOutputPort(0)
            porta = p
            val lettore = LettoreMidi()
            p?.connect(object : MidiReceiver() {
                override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
                    // thread del MIDI: si legge qui e si passa all'interfaccia
                    lettore.leggi(msg, offset, count) { m -> principale.post { messaggio(m) } }
                }
            })
            collegato(nome(device))
        }, principale)
    }

    private fun chiudi() {
        try {
            porta?.close()
            dispositivo?.close()
        } catch (_: Exception) {
        }
        porta = null
        dispositivo = null
        info = null
    }

    private fun nome(d: MidiDeviceInfo): String {
        val p = d.properties
        val produttore = p.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER)?.trim().orEmpty()
        val prodotto = p.getString(MidiDeviceInfo.PROPERTY_PRODUCT)?.trim()
            ?: p.getString(MidiDeviceInfo.PROPERTY_NAME)?.trim().orEmpty()
        return listOf(produttore, prodotto).filter { it.isNotEmpty() }.distinct().joinToString(" ").ifEmpty { "Controller MIDI" }
    }
}
