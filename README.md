# DVS

App DJ per **Android** (pensata per un Google Pixel in orizzontale): due deck, scratch col dito, mixer con EQ e filtro,
crossfader, BPM e sync, hot cue, loop, effetti, controller **M-Audio X-Session Pro** via USB.
Suona i tuoi file (mp3, wav, m4a, flac…). Le bozze grafiche sono in [`design/`](design/).

> **Stato:** prima versione completa da provare: libreria, waveform, BPM e sync, scratch, hot cue, loop, keylock,
> mixer, effetti (Echo, Dub Delay, Reverb, Stretch), REC, controller MIDI con LEARN e DVS con vinile timecode.

## Installarla sul Pixel

1. Dal telefono apri la pagina **Releases** di questo repository (Release **"ultima"**) e tocca **DVS.apk**.
2. A download finito tocca la notifica (o apri *File › Download*) e poi **Installa**.
3. La prima volta Android chiede di consentire l'installazione di app da Chrome (o da File): **consenti** e torna indietro.
4. Se compare un avviso di Play Protect, tocca *Altri dettagli › Installa comunque* (l'app non viene dal Play Store).

Per aggiornare: scarica di nuovo `DVS.apk` e installalo sopra. Le tue impostazioni restano.

## Controller MIDI (X-Session Pro)

Collega il controller con un cavo USB (adattatore USB-C). Tocca **DVS** in alto › **Opzioni › MIDI** ›
**Configurazione guidata › INIZIA** e muovi i controlli che l'app chiede, uno alla volta (si può saltare).
Per assegnare un comando qualsiasi: **LEARN**, tocca il comando sullo schermo e muovi il controllo.

## DVS (vinile timecode)

Serve una scheda audio USB "class compliant" con ingressi phono (o un pre phono) e un vinile timecode
Serato CV02, Traktor o MixVibes. **Opzioni › DVS › Attiva**, scegli ingresso, tipo di timecode e giri; metti il
disco in play e tocca **Calibra**: lo scope deve mostrare un anello verde. Poi tocca **DVS** sul deck A, sul B
o su tutti e due.

## Per chi sviluppa

- `app/` – app Android (Kotlin, Jetpack Compose). `app/src/main/cpp/` – ponte JNI e uscita audio con Oboe.
- `engine/` – motore audio in C++17 senza dipendenze Android; `tests/` lo prova anche su Linux.
- `.github/workflows/android.yml` – test, compilazione, firma (secret `DVS_KEYSTORE_B64`), Release "ultima".
- `engine/terzi/` – librerie di terzi: Signalsmith Stretch e Signalsmith Linear (licenza MIT) per il keylock.
- `engine/Timecode.cpp` – decoder del timecode scritto qui; i parametri dei formati (LFSR) vengono da
  [xwax](https://xwax.org) di Mark Hills (GPL).
- Il vecchio plugin AUv3 per iPad (ScratchAU) è nel ramo `ios-scratchau`.
