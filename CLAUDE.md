# DVS (ex Djaaay) — app DJ per Android (Google Pixel)

**Parla con l'utente in italiano.** L'utente non è uno sviluppatore: spiega ogni passaggio pratico (GitHub, installazione
dell'APK, collegamento dell'X-Session) in modo semplice, un passo alla volta.

Il repository si chiamava `scratch-au` (plugin AUv3 per iPad): quel progetto è archiviato nel ramo `ios-scratchau`.
Il 27/10/2026 l'utente ha scelto di passare ad Android: su iPad la firma gratuita scade ogni 7 giorni, su Android l'APK resta.

## Obiettivo

App DJ "stile djay" (ispirazione, NON copia: nome, grafica e logo nostri) per **Pixel telefono, in orizzontale**:
due deck che suonano i file dell'utente (mp3, wav, m4a, flac…), scratch col dito, mixer, crossfader, BPM e sync,
hot cue, loop, effetti, controller MIDI **M-Audio X-Session Pro** via USB.
Niente streaming (Spotify ecc.: impossibile senza accordi commerciali).

## Vincoli

- Costo zero. Niente Play Store per ora: APK installato a mano.
- Si compila solo su **GitHub Actions** (repo pubblico): in questa sessione cloud `dl.google.com`/maven Google sono
  bloccati, quindi niente Android SDK in locale. Il motore C++ invece si compila e si testa qui (clang, `tests/`).
- Google "developer verification" per app fuori dal Play Store (2026 alcuni paesi, 2027 ovunque): da ricontrollare.

## Nome

L'app si chiama **DVS** (27/10/2026; prima "Djaaay"). Nel codice il package Kotlin resta `com.toniaudio.djaaay`
(nomi delle funzioni JNI), l'applicationId è `com.toniaudio.dvs`, l'APK pubblicato è `DVS.apk`.

## Grafica decisa (bozze in `design/`, sorgente `design/bozze.html`)

- Tema scuro **neutro** (grigi/bianco). Colore SOLO nelle **waveform** e nel **centro dei dischi**: deck A **rosso**
  (#FF3B47, bassi #5C1219), deck B **blu** (#3D7BFF, bassi #0F2766); nelle waveform bassi = tono scuro del deck,
  medi = colore del deck, alti = bianco. Hot cue: 1 giallo #FFB020, 2 verde #3DDC84, 3 viola #B07CFF, 4 fucsia #FF4FD8 (non bianco: si confonderebbe con la puntina).
  Niente bottoni/scritte colorati per deck (eccezione: KEY acceso in verde).
- In alto: per deck pulsante **libreria** all'estremità (A a sinistra, B a destra), titolo, artista, BPM, tempo mancante.
  Al centro il nome **DVS**: toccandolo si aprono le **Opzioni** (nessun pulsante ingranaggio).
- **Waveform alte** (2 strisce, A sopra B), puntina fissa al centro, griglia battute, bandierine hot cue,
  panoramica traccia sotto; pinch = zoom, trascinare = scratch (disattivabile).
- Deck: disco touch (scratch), CUE / Play / SYNC, pad con tab HOT CUE · LOOP · FX; pitch verticale con valore
  e tasto **DVS** (l'escursione ±8/16/50 si sceglie solo nelle Opzioni). Tasto **KEY** (keylock) in alto a sinistra del disco A e in alto a destra del disco B:
  senza riquadro, scritta+lucchetto grigi da spento, verdi illuminati da acceso.
- **DVS per deck** (richiesta 27/10): un solo segnale timecode (un giradischi) può comandare il deck A, il B o tutti e due
  insieme: ogni deck con DVS acceso segue il timecode, con DVS spento si usa normalmente (touch/MIDI). Scelta manuale.
- Mixer (al centro, tab MIXER / FX): per canale GAIN (manopola piccola e sottile), HI/MID/LOW, FILTRO (manopola grande con
  arco: sinistra LP, destra HP, centro spento). **Volumi al centro** ai lati del MASTER (manopola + meter), REC.
- **Crossfader corto**, lettere A/B ai lati, sotto SOLO il simbolo della curva (taglio ⊓, lineare ×, morbida) in grigio,
  stile scritte EQ; tocco = cambia curva. Inverti (hamster) nelle Opzioni / MIDI.
- **FX**: un'unità per deck (FX · DECK A, FX · DECK B) con ON, scelta tra **ECHO, DUB DELAY, REVERB, STRETCH**,
  manopole DRY/WET + parametri, riga tempo (battute) o rapporto (stretch ½× ¼× ⅛× FREEZE).
  Pad FX del deck: tocco = on/off, tenere premuto = momentaneo. Keylock è separato (tasto KEY).
- **Libreria per deck**: il pulsante libreria di un deck apre la libreria DENTRO lo spazio di quel deck (sotto le waveform,
  senza coprire il mixer). Ricerca, tab Tutti / Cartelle / Playlist / Recenti, BPM e durata; tocco su un brano = caricato
  in quel deck. Il brano già caricato è in grigio.
- Opzioni: frizione slipmat, avvio, frenata, sensibilità piatto, scratch su waveform, escursione pitch, curva e inverti
  crossfader, MIDI (X-Session, LEARN), audio (uscita, pre-ascolto con scheda USB).

## Architettura

- `app/` Kotlin + Jetpack Compose (UI), `engine/` C++17 puro (nessuna dipendenza Android), `app/src/main/cpp/` ponte JNI
  + uscita audio con **Oboe**. Regole real-time nel callback audio: niente allocazioni, lock, log, I/O, JNI.
- Comandi UI → motore: parametri atomici / coda lock-free. Motore → UI: posizioni/livelli atomici letti a ogni frame.
- Decodifica (MediaExtractor/MediaCodec) fuori dal thread audio → PCM 16 bit in memoria nativa; il deck legge alla
  velocità `rate * fileRate / deviceRate` (niente ricampionamento al caricamento). Interpolazione Hermite.
- Ogni deck ha una "sorgente di controllo" del movimento: motore, dito (touch), MIDI e in futuro **timecode (DVS)**.
- Firma APK: chiave fissa nel secret GitHub `DVS_KEYSTORE_B64` (così gli aggiornamenti si installano sopra).

## Tappe

1. Scheletro Android + CI che pubblica `DVS.apk` nella Release `ultima`. 
2. Motore: 2 deck, scratch con frizione/motore, gain, EQ, filtro, volumi, crossfader, master. Test su Linux.
3. Caricamento brani, waveform colorata con zoom, libreria, UI come da bozze, opzioni.
4. BPM + beatgrid + SYNC, keylock. 5. Hot cue, loop. 6. FX (echo, dub delay, reverb, stretch).
7. X-Session via USB MIDI con LEARN. 8. REC del mix.
9. Futuro: scheda audio USB multicanale → pre-ascolto in cuffia; **DVS con timecode Serato** (decoder di xwax, GPL:
   va bene, il repo è pubblico). Serve scheda class-compliant con 4 ingressi (meglio phono) + 4 uscite.

## Stato

- 27/10/2026: bozze grafiche approvate (`design/`). Inizio tappa 1.
