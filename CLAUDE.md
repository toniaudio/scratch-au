# ScratchAU — Plugin AUv3 per AUM (iOS) per scratch con crossfader MIDI

Questo file riassume una conversazione di pianificazione. Leggilo tutto prima di iniziare.
**Parla con l'utente in italiano.** L'utente non è uno sviluppatore: spiega ogni passaggio pratico (GitHub, SideStore, AUM) in modo semplice, un passo alla volta.

---

## Obiettivo

Un plugin **AUv3** (non VST: su iOS il formato è AUv3) da usare dentro **AUM** su iPad/iPhone che:

1. Carica una traccia o un sample **mp3/wav**.
2. Permette di fare **scratch** sulla traccia.
3. Ha un **crossfader** esposto come parametro AUv3, mappabile via MIDI in AUM (MIDI learn).

## Vincoli del progetto

- **Costo zero.** Niente Apple Developer Program a pagamento.
- **Niente Xcode in locale:** si compila solo su GitHub Actions. Un Mac c'è, ma serve solo per la prima installazione di SideStore.
- **Nessuna scheda audio per ora.** Quindi il DVS con timecode Serato NON è possibile ora: il timecode è un segnale audio e serve un ingresso stereo pulito.
- **Controller: M-Audio X-Session Pro.** È solo MIDI via USB: niente audio interno, niente jog wheel. Ha fader, crossfader, manopole e pulsanti. Il suo crossfader fisico verrà mappato sul parametro crossfader del plugin. Per collegarlo all'iPad serve un adattatore USB, ed eventualmente un hub alimentato.
- **Vuole un'app che funzioni davvero**, senza dover reinstallare a mano ogni settimana.

## Percorso scelto: build e installazione gratis, senza Xcode

1. **Codice** scritto qui, in un repository GitHub.
2. **Progetto Xcode generato con XcodeGen** (`project.yml`): niente `.xcodeproj` scritto a mano, niente GUI di Xcode.
3. **GitHub Actions** con runner `macos-latest` (gratuito per i repo pubblici):
   - `xcodegen generate`
   - `xcodebuild ... -sdk iphoneos CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO`
   - impacchetta `Payload/ScratchAU.app` in uno zip → **`.ipa` non firmato** come artifact della build (e/o Release).
4. **Installazione con SideStore (preferito) o AltStore**, firmando con l'**Apple ID gratuito** dell'utente. Questi strumenti rinnovano da soli la firma, che con un account gratuito dura 7 giorni.
   - Limiti dell'account gratuito: max **3 app attive** sideloaded e **10 App ID a settimana**. App contenitore + estensione AU = 2 App ID.
   - Verifica che l'estensione AUv3 venga firmata e registrata correttamente dopo il sideload, e che compaia in AUM.

## Architettura

- **App contenitore** (minima): schermata con istruzioni d'uso e, se serve, import dei file.
- **Estensione AUv3**:
  - Tipo consigliato: **`aumf` (Music Effect)**, perché riceve MIDI e ha un ingresso audio. L'ingresso servirà in futuro per il DVS e per l'eventuale crossfade con l'audio in ingresso. In alternativa `augn` (generator) se `aumf` crea problemi in AUM.
  - DSP in C/C++ (o Swift con molta attenzione) nel render block. **Regole real-time:** nessuna allocazione, nessun lock, nessun Objective-C/Swift ARC, nessun I/O nel render thread.
  - UI in SwiftUI o UIKit dentro l'estensione.

### Funzionalità MVP (Fase 1)

- **Caricamento file:** document picker (mp3/wav, anche m4a/aiff se gratis). Decodifica completa in memoria con `AVAudioFile` in float stereo, convertita al sample rate della sessione. Il caricamento avviene fuori dal render thread e il buffer viene passato al DSP con uno swap atomico.
  - Rischio: presentare il document picker dall'interno di un'estensione AU. Se dà problemi, alternativa: import dall'app contenitore in un **App Group** condiviso. Le App Group con account gratuito + SideStore vanno verificate.
  - Salvare la traccia nello stato del plugin (`fullState`) tramite bookmark, così AUM la ricarica con il preset/sessione.
- **Piatto virtuale touch:**
  - Disco che ruota a schermo. Toccandolo il dito "afferra" il disco: la velocità del dito diventa la velocità di riproduzione (anche negativa, all'indietro).
  - Rilasciato, torna alla velocità normale con un'inerzia breve (motore).
  - Pulsante **Play/Stop** con brake di avvio e arresto regolabile.
  - Waveform o marker della posizione + **cue point** (tap per tornare al cue).
- **Riproduzione a velocità variabile:** puntatore di lettura frazionario con interpolazione **Hermite/cubica**, e smoothing della velocità per evitare click. Gestire velocità ~0 senza rumore.
- **Crossfader:** `AUParameter` (0…1) automatizzabile e mappabile MIDI in AUM. Curve selezionabili: **sharp/cut** (tipica da scratch), lineare, constant-power. Applicare uno smoothing minimo che non ammorbidisca troppo i tagli.
  - **Domanda aperta per l'utente:** il crossfader sfuma tra **traccia ↔ ingresso audio (thru)** oppure tra **due deck** nel plugin? Default proposto per l'MVP: traccia ↔ ingresso thru, che con AUM permette di mixare con altre sorgenti.
- **Altri parametri esposti (mappabili):** volume deck, pitch/tempo (±8/±16/±50%), play/stop, curva crossfader, eventualmente un "hamster switch" (inverti crossfader).
- Esporre i parametri anche via **MIDI diretto (CC)** all'estensione, oltre al learn di AUM.

### Fase 2 (futuro)

- Supporto **jog wheel MIDI** (scratch da controller con jog, messaggi relativi).

### Fase 3 (futuro, solo con hardware adatto)

- **DVS con timecode Serato** (portante ~1 kHz stereo in quadratura + bit di posizione assoluta) letto dall'ingresso audio dell'estensione.
- Iniziare in **modalità relativa** (velocità e direzione dalla fase della portante), poi aggiungere quella **assoluta**.
- Riferimento: il decoder open source di **xwax** supporta i timecode Serato. È **GPL**: se si riusa il codice, il progetto diventa GPL (va bene per uso personale).
- Richiede un mixer o una scheda audio class-compliant con ingressi phono/line stereo, più un vinile o CD timecode Serato.

## Struttura repo suggerita

```
scratch-au/
├── CLAUDE.md
├── README.md                  # istruzioni per l'utente, in italiano
├── project.yml                # XcodeGen
├── App/                       # app contenitore
├── Extension/                 # AUv3: AudioUnit, UI, Info.plist (AudioComponents)
├── DSP/                       # C/C++: player, interpolazione, crossfader
└── .github/workflows/build.yml
```

## Ordine di lavoro consigliato

1. Chiedere all'utente le risposte alle domande aperte (sotto).
2. Scaffold: `project.yml`, app vuota + estensione AUv3 che passa l'audio in thru, workflow GitHub Actions che produce l'`.ipa`.
3. Guidare l'utente: creare l'account GitHub, caricare il repo, scaricare l'`.ipa`, installare SideStore, sideload, aprire AUM e verificare che il plugin compaia.
4. Solo dopo che il "plugin vuoto" funziona sull'iPad: player + caricamento file → piatto touch → crossfader + parametri → rifiniture.
5. Ad ogni passo: build in CI verde prima di chiedere all'utente di installare.

## Decisioni prese (27/10/2026)

- **Dispositivo:** iPad. **Computer:** Mac (per la prima installazione di SideStore con iloader).
- **Repository:** repo GitHub **pubblico** separato (`toniaudio/scratch-au`), così le build su macOS sono gratis e senza limiti.
- **Crossfader:** traccia ↔ ingresso audio (thru), un solo deck. 0 = solo traccia (sinistra), 1 = solo ingresso (destra).
- Tipo AU `aumf`, manufacturer `Toni`, subtype `Scrt`, nome in AUM "Toniaudio: ScratchAU". Bundle ID `com.toniaudio.scratchau` (+ `.AUv3`).
- Parametri (indirizzi fissi, non cambiarli: AUM salva le mappature MIDI per indirizzo): 0 Crossfader, 1 Curva (0 Taglio, 1 Lineare, 2 Potenza costante), 2 Inverti.
- AU in Objective-C++ (`Extension/ScratchAudioUnit.mm`), finestra in Swift/UIKit, DSP in C++ puro (`DSP/`) testato anche su Linux (`tests/`).
- Release fissa "ultima" con `ScratchAU.ipa`, ricreata a ogni push su main.

## Stato

- Fase "plugin vuoto" scritta: thru + crossfader (curve, inverti, smoothing ~1 ms) + finestra con i controlli. Test DSP verdi.
- Prossimo: build CI verde → installazione sull'iPad → verifica in AUM (plugin visibile, MIDI learn del crossfader). Poi player + caricamento file.

## Richieste dell'utente per la fase 2 (27/10/2026)

- **Waveform della traccia caricata**, visibile nella finestra del plugin:
  - waveform che **scorre** con la **puntina** fissa al centro (linea verticale), come nei software DJ;
  - **zoom** con il pizzico (pinch) delle dita;
  - in più una panoramica di tutta la traccia con la posizione attuale (tocco = salto in quel punto, da valutare).
  - Tecnica: picchi min/max calcolati al caricamento (fuori dal thread audio) a più livelli di zoom; posizione letta dal DSP
    con un atomic e disegnata a ogni frame (CADisplayLink).
- **Frizione del disco regolabile** (e in generale un pannello **Opzioni** con le cose personalizzabili).
  - Le opzioni stanno **nella finestra del plugin** (icona ingranaggio), non nell'app contenitore: così vengono salvate con la
    sessione di AUM (fullState) e non serve condividere dati tra app ed estensione (App Group incerti con SideStore).
  - Opzioni previste: **frizione/slipmat** (quanto in fretta il disco rilasciato torna alla velocità del motore),
    **avvio** e **frenata** del motore (Play/Stop), **sensibilità** del piatto touch, curva crossfader, inverti, pitch ±8/16/50%.

## Domande aperte da fare all'utente (risposte sopra, in "Decisioni prese")

- Usi un **iPad** o un **iPhone**? Che versione di iOS?
- Hai accesso a un **computer** (Windows/Mac/Linux)? Serve per la prima configurazione di SideStore o AltStore.
- Crossfader: **traccia ↔ ingresso** oppure **due deck**?
- Ti basta un solo deck per lo scratch, con l'altra mano sul crossfader dell'X-Session?
