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
  panoramica traccia sotto, **toccabile** (29/10: tocco = vai lì, trascinare = il brano segue il dito; striscia 14 dp,
  zona di tocco +6 dp; **in play il dito va tenuto 2 s** prima del salto, con segno e barretta d'attesa); pinch = zoom. La waveform NON comanda il brano: lo scratch solo sul disco.
- **LOOP** (tab dei pad): selettore **◀ [ N ] ▶** (solo il numero, niente scritta "battute"); le frecce dimezzano/raddoppiano 1/8…32 battute, il tasto centrale
  accende/spegne il loop dalla **battuta più vicina** (i loop < 1 battuta dalla loro suddivisione più vicina), lunghezza
  contata sulla griglia (anche variabile); cambiare N con loop acceso lo accorcia/allunga subito.
  Loop acceso = tasto verde; sulla waveform la zona del loop è velata di verde con i bordi.
- Deck: disco touch (scratch), CUE / Play / SYNC, pad con tab HOT CUE · LOOP · FX; pitch verticale con valore
  e tasto **DVS** (l'escursione ±8/16/50 si sceglie solo nelle Opzioni). Tasto **KEY** (keylock) in alto a sinistra del disco A e in alto a destra del disco B:
  senza riquadro, scritta+lucchetto grigi da spento, verdi illuminati da acceso.
- **DVS per deck** (richiesta 27/10): un solo segnale timecode (un giradischi) può comandare il deck A, il B o tutti e due
  insieme: ogni deck con DVS acceso segue il timecode, con DVS spento si usa normalmente (touch/MIDI). Scelta manuale.
- Mixer (al centro, tab MIXER / FX): per canale GAIN (manopola piccola e sottile), HI/MID/LOW, FILTRO (manopola grande con
  arco: sinistra LP, destra HP, centro spento). **Volumi al centro** ai lati del MASTER (manopola + meter), REC.
- **Crossfader corto** (curva taglio = netta: canale muto solo col crossfader in fondo dall'altra parte, appena si
  sposta di un passo è pieno; ~1 ms anti-click), lettere A/B ai lati, sotto SOLO il simbolo della curva (taglio ⊓, lineare ×, morbida) in grigio,
  stile scritte EQ; tocco = cambia curva. Inverti (hamster) nelle Opzioni / MIDI.
- **FX**: un'unità per deck (FX · DECK A, FX · DECK B) nella sezione FX: scelta tra **ECHO, DUB DELAY, REVERB, ROLL**,
  solo **trackpad** (niente manopole né ON): dito giù = suona, su = svanisce con la coda; secondo dito = HOLD.
  X = dry/wet, Y = parametro principale (roll: TONO, centro normale, ±12 st); riga tempo / coda / lunghezza del roll.
  **Effetti indipendenti** (richiesta 28/10): ognuno ha acceso e HOLD suoi, possono suonare insieme. **4 trackpad per
  deck** sempre presenti (telefono: in fila; tablet: 2×2), un dito per effetto; sopra ognuno il nome (pieno = suona,
  punto verde = HOLD, tocco = sue regolazioni nella riga in basso, che segue l'ultimo effetto toccato).
  Niente FX nei deck (29/10: pad del deck solo HOT CUE · LOOP). Valori comandabili da MIDI. Keylock è separato (tasto KEY).
- **Libreria per deck**: il pulsante libreria di un deck apre la libreria DENTRO lo spazio di quel deck (sotto le waveform,
  senza coprire il mixer). Ricerca, tab Tutti / Cartelle / Playlist / Recenti, BPM e durata; tocco su un brano = caricato
  in quel deck. Il brano già caricato è in grigio.
- **Opzioni** (si aprono toccando DVS) a schede:
  - *Generale*: frizione slipmat, avvio, frenata, sensibilità piatto, escursione pitch ±8/16/50,
    curva e inverti crossfader, uscita audio, pre-ascolto (scheda USB).
  - *MIDI*: controller collegato, preset di mappatura per controller (X-Session Pro predefinito + personali), LEARN,
    elenco assegnazioni con ✕, azzera. **Tutti i comandi principali sono assegnabili**: crossfader e curva, volumi, gain,
    EQ, filtri, master, Play, Cue, Sync, KEY, DVS, pitch, hot cue 1–4, loop, FX (on/off, scelta, dry/wet, parametri),
    libreria (scorri e carica), in futuro scratch col jog.
  - *Modalità LEARN* sulla schermata principale: barra verde in alto con FINE, comandi assegnabili bordati in verde
    tratteggiato, quello scelto bordato pieno con "in attesa… muovi …".
  - *DVS* (taratura): scope circolare del segnale (anello verde = buono), qualità %, velocità, posizione, Calibra/Azzera;
    scheda audio, ingresso 1–2 / 3–4, livello ingresso con meter, tipo timecode (Serato CV02, Traktor, MixVibes),
    giri 33⅓/45, modalità assoluta/relativa, sensibilità a disco fermo, protezione salto puntina.

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
7. X-Session via USB MIDI con LEARN. 8. REC del mix. (Fatte tutte, 28/10.)
9. **DVS con timecode** fatto (28/10, decoder nostro coi parametri di xwax, GPL: va bene, il repo è pubblico).
   Futuro: scheda audio USB multicanale → pre-ascolto in cuffia (serve scheda class-compliant con 4 uscite).

## Stato

- 27/10/2026: bozze grafiche approvate (`design/`). Tappa 1 fatta: `DVS.apk` (tono di prova) pubblicato e firmato.
- Motore (tappa 2) scritto e testato: `tests/test_motore.cpp` (~50 verifiche, girano anche in CI con ASan/UBSan).
- Revisione (27/10/2026, sera): corretti Traccia::aggiungi quadratico (51 s → 0,07 s per 5 min), Play durante CUE,
  loop all'indietro e accorciato, salti con dissolvenza 2 ms, fine brano a metà blocco, salto durante lo scratch,
  click del filtro al cambio LP↔HP, doppia apertura del flusso audio, schermo sempre acceso, versione = 100 + commit,
  R8 (APK 3 MB, verificato che `Motore` e i metodi JNI non vengono rinominati), concorrenza CI.
- Verifiche fatte sull'APK: firma = certificato DVS (SHA-256 88f1bd61…0e22).
- Suggerimenti della revisione fatti (27/10/2026, notte): scratch regolare (bug stima velocità del dito), inseguimento
  del dito per interpolazione (niente sbandate), anti-aliasing oltre velocità 1, REC in WAV (Registratore + Musica/DVS
  via MediaStore), waveform salvabile (`salvaWaveform`/`caricaWaveform`), diagnostica (ritardo, xrun, carico, buffer
  1×/2×/4×), servizio in primo piano `ServizioAudio` (mediaPlayback), motore vero collegato al JNI (tono di prova =
  brano di 8 s in loop nel deck A).
- **Keylock fatto** (27/10/2026) con Signalsmith Stretch 1.3.2 (MIT, `engine/terzi/`, inserita col consenso esplicito
  dell'utente). `Keylock` corregge il tono dell'uscita varispeed (trasposizione 1/(1+pitch)); il deck legge in anticipo
  del ritardo della libreria (blocco 80 ms, passo 20 ms, splitComputation) così l'uscita resta allineata alla puntina
  (test: colpo allo stesso campione con e senza keylock). Loop previsti nella lettura anticipata; durante scratch,
  frenate, avvii e per ~120 ms dopo un salto si usa il suono diretto con dissolvenza di 10 ms.
  `splitComputation` è indispensabile: senza, ogni 20 ms un blocco audio impiegava fino a 1–4 ms (su 2 ms disponibili).
  Carico misurato (server): 2 deck con keylock ≈ 4% di un core, 99,9% dei blocchi da 2 ms sotto 0,3 ms.
- **Tappa 3 fatta** (27-28/10/2026): schermata vera (Compose) come da bozze, caricamento brani (MediaStore +
  MediaCodec), waveform e BPM salvati in cache, libreria per deck, opzioni. Foto automatiche della schermata con
  Robolectric + Roborazzi (job `schermate`, prerelease "schermate", `app/src/test/.../SchermateTest.kt`).
  Fader e crossfader ridisegnati "da mixer vero" (fessura, tacche, cappuccio zigrinato), cursori sottili nelle opzioni.
- **FX fatti** (28/10): `engine/Effetti.*`, uno per deck prima del mixer. ECHO (feedback), DUB DELAY (feedback fino
  a 1,1 saturato, tono nel giro, "wow" del nastro), REVERB (Freeverb: stanza, damp, coda corta/media/lunga),
  STRETCH granulare (grana 10–120 ms, tono ±12 st, ½ ¼ ⅛ FREEZE). Ogni tipo ricorda le sue regolazioni; le code
  continuano dopo lo spegnimento. Tempo dagli BPM × pitch (`secondiBattito`). Pad FX: tocco = on/off, tenuto = momentaneo.
- **MIDI fatto** (28/10): `Midi.kt` (azioni, LettoreMidi con running status, procedura guidata), `MidiUsb.kt`
  (android.media.midi). Il preset X-Session Pro NON è precaricato (manuale coi CC non raggiungibile da qui):
  c'è la **configurazione guidata** (un controllo alla volta). LEARN sulla schermata (bordi verdi) e nell'elenco
  delle opzioni (anche comandi senza tasto: libreria su/giù/scorri/carica). Assegnazioni in preferenza "midi".
- **DVS fatto** (28/10): `engine/Timecode.*` (velocità dall'angolo dei canali in quadratura con doppio filtro da
  1,5 ms; bit LFSR come xwax; calibrazione; scope), `Anello.h` (ingresso → thread audio), ingresso Oboe in
  `IngressoDvs` (InputPreset::Unprocessed, stessa frequenza dell'uscita). Deck con `dvs` acceso: velocità dal
  vinile; in assoluta salta alla posizione quando la puntina viene appoggiata (o se salta, senza protezione).
  Provato SOLO con segnale sintetico (round-trip dei formati): da verificare con scheda e vinili veri.
- **Layout telefono + trackpad FX** (28/10): con altezza < 500 dp (telefono in orizzontale) sotto le waveform
  (100 dp) c'è una vista alla volta, `StatoApp.vista`: DECK (dischi, trasporto 44 dp, pad 38 dp; al centro filtri,
  volumi, crossfader), MIXER, PAD (hot cue e loop grandi), FX. Misure in `LocalMisure` (Tema.kt). Su tablet il layout
  di prima. Bozze in `design/telefono.html`. Unità FX: il tasto ON è diventato **PAD** (apre il trackpad al posto
  delle manopole); l'effetto si accende dai pad FX del deck. Trackpad: X = dry/wet, Y = parametro principale
  (`DescrizioneFx.asseY`: echo/dub feedback, reverb stanza, stretch tono); dito giù = suona, su = svanisce (code;
  stretch in dissolvenza ~0,5 s); secondo dito = HOLD, il tocco dopo lo fa svanire; se era già acceso dal pad del
  deck il trackpad lo modula soltanto.
- **Pre-ascolto + One Deck** (29/10): `Mixer` ha la cuffia per canale (prima del fader), CUE/MASTER e volume
  cuffie; `Motore::render(…, canali)`: con 4+ canali master su 1-2 e cuffie su 3-4 (`Uscita::impostaCuffie`,
  Oboe a 4 canali sulla scheda USB, senza conversione; se la scheda sparisce si torna stereo e l'app lo vede da
  `stato[36]`). Si attiva in Opzioni › Audio; tasti cuffia nel mixer (telefono: anche accanto a FILTRO nella vista DECK).
  **One Deck** (Opzioni › Generale): deck A + `Campionatore` (engine) a 8 pad sul canale B (UNA VOLTA / LOOP / TIENI,
  dissolvenze 2/8 ms), pannello al posto del deck B, libreria per i pad, MODIFICA (menu: cambia suono, modo, togli),
  STOP; suoni e modi salvati ("campionatore") e ricaricati dopo la lettura della libreria. MIDI: CUFFIA, MIX/VOLUME
  CUFFIE, CAMPIONE_1…8.
- **Revisione 29/10** (richieste dell'utente dopo la prova):
  - Caricamento progressivo: `Traccia` a blocchi da 64k frame con conteggio atomico e riferimenti (`trattieni`/
    `rilascia`): il deck suona già dal primo secondo decodificato, la decodifica continua (codec sempre pieno, niente
    attese da 10 ms) e un altro thread fa l'analisi (`AnalisiProgressiva`: waveform + attacchi in un passaggio a metà
    frequenza; BPM alla fine). Waveform che cresce, sottile barra di avanzamento; con il file di analisi in cache
    waveform e BPM sono pronti subito. Il deck non si ferma dove la decodifica è arrivata (`lunghezza()` = prevista
    finché non è `completa`). TSan pulito.
  - Barra alta 44 dp sul telefono; testi senza "carattere grande" di Android (fontScale ≤ 1); schermata fuori dal foro
    della fotocamera; tasti libreria lontani dagli angoli arrotondati (`Display.getRoundedCorner`).
  - STRETCH tolto → **ROLL** (ripete 1/16…1 battito dal momento in cui si accende; Y = lunghezza a 5 fasce, riga
    AVANTI/INDIETRO, dissolvenza 4 ms al giro). MIDI: FX_ON/FX_ECHO…FX_ROLL suonano finché premuti, FX_HOLD.
  - Doppio tocco: volumi a 0 dB (in alto), pitch al centro, crossfader al centro (due tocchi brevi e fermi).
  - Poi: FX indipendenti (`Effetto::acceso[4]`, catena ROLL → ECHO/DUB/REVERB in parallelo); ROLL rifatto (era
    "sgranato"): ripete il pezzo appena prima a tempo, Y = tono (lettura più veloce/lenta col ritmo che resta: in su
    il pezzo si ripete più volte nel giro), riga = lunghezza 1/16…1, al contrario solo da MIDI (parametro 2);
    ogni salto è una dissolvenza a potenza costante tra voci (fino a 3 che sfumano, mai uno stacco).
  - Roll "sgranato dopo un po'": la registrazione (10 s) sovrascriveva il pezzo; ora si ferma mentre il roll suona
    (test: roll tenuto 25 s). 4 trackpad per deck. Crossfader taglio netto (`kFondoTaglio`).
  - **Griglia variabile** (loop imprecisi sui brani lunghi a BPM variabile): `trovaBattute` (Analisi.cpp) =
    programmazione dinamica alla Ellis sugli attacchi (rigidità 100, intervalli 0,7–1,3 periodi), battute
    "ritmiche" come ancore (colpo forte anche nelle vicine), nei break ridistribuite uguali, parabola ai minimi
    quadrati su ±8 battute. Se ≥ 8 battute ritmiche di fila stanno a > 20 ms dalla griglia fissa → `Tempo::battiti`
    (file di analisi v3, i vecchi si rifanno da soli). Errore medio ~0,5 ms, peggiore < 2 ms (test 100→108 BPM e
    variazioni ±15%). App: `StatoDeck.battiti`, `battutaA`/`tempoDellaBattuta`/`battitoA`; loop, SYNC, BPM mostrato
    (`bpmQui`), tempo degli FX e righe della griglia usano la griglia vera. `SenzaDenormali` (FTZ) nell'analisi e
    in `Motore::render`: nei silenzi l'analisi era 8× più lenta su x86.
- iOS valutato (28/10): fattibile (motore C++ riusabile, UI con Compose Multiplatform), ma con Apple ID gratuito la
  firma scade ogni 7 giorni (o 99 €/anno). L'utente ha deciso: **per ora resta solo Android**.
- Prossimo: prova vera dell'utente (MIDI con l'X-Session, DVS con scheda USB), poi pre-ascolto in cuffia
  (scheda multicanale), playlist, keylock durante il DVS (ora spento in DVS).

## Da ricordare (scelte tecniche)

- **`Sessione`** (object, vive quanto il processo come il motore nativo) tiene StatoApp, Controllore e piattaforma:
  riaprendo l'app la schermata ritrova i brani caricati (prima ripartiva vuota e i dischi muovevano brani
  "invisibili" rimasti nel motore). Chiudendo (isFinishing) `Controllore.chiusura()` ferma deck/campionatore/FX;
  riaprendo `riapertura()` riapre pre-ascolto e ingresso DVS. Permessi chiesti alla MainActivity aperta (WeakReference).

- Il thread dell'interfaccia deve chiamare `Deck::raccogliTracceVecchie()` (es. a ogni frame): libera i brani sostituiti.
- Quando si collega il motore vero al JNI, `Motore::prepara()` va chiamato solo con il flusso fermo (apri/riapri).
- Chiudere Oboe tenendo il mutex mentre arriva un `onErrorAfterClose` potrebbe in teoria bloccarsi: caso rarissimo
  (scollegamento del dispositivo nello stesso istante della chiusura), da rivedere se capita. (L'ingresso DVS usa try_lock.)
- DVS a schermo spento: Android silenzia l'ingresso delle app in background; servirebbe il servizio in primo piano
  di tipo "microphone" (ora solo mediaPlayback). Lo schermo resta acceso durante l'uso, quindi per ora va bene.
