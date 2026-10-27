# DVS

App DJ per **Android** (pensata per un Google Pixel in orizzontale): due deck, scratch col dito, mixer con EQ e filtro,
crossfader, BPM e sync, hot cue, loop, effetti, controller **M-Audio X-Session Pro** via USB.
Suona i tuoi file (mp3, wav, m4a, flac…). Le bozze grafiche sono in [`design/`](design/).

> **Stato:** in costruzione. La prima versione suona solo un tono di prova per verificare installazione e audio.

## Installarla sul Pixel

1. Dal telefono apri la pagina **Releases** di questo repository (Release **"ultima"**) e tocca **DVS.apk**.
2. A download finito tocca la notifica (o apri *File › Download*) e poi **Installa**.
3. La prima volta Android chiede di consentire l'installazione di app da Chrome (o da File): **consenti** e torna indietro.
4. Se compare un avviso di Play Protect, tocca *Altri dettagli › Installa comunque* (l'app non viene dal Play Store).

Per aggiornare: scarica di nuovo `DVS.apk` e installalo sopra. Le tue impostazioni restano.

## Per chi sviluppa

- `app/` – app Android (Kotlin, Jetpack Compose). `app/src/main/cpp/` – ponte JNI e uscita audio con Oboe.
- `engine/` – motore audio in C++17 senza dipendenze Android; `tests/` lo prova anche su Linux.
- `.github/workflows/android.yml` – test, compilazione, firma (secret `DVS_KEYSTORE_B64`), Release "ultima".
- Il vecchio plugin AUv3 per iPad (ScratchAU) è nel ramo `ios-scratchau`.
