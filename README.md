# ScratchAU

Plugin **AUv3** per **AUM** (iPad) per fare scratch su una traccia, con un crossfader mappabile via MIDI
(pensato per il crossfader dell'M-Audio X-Session Pro).

> **Stato: fase 1 – plugin "vuoto".** Fa passare l'audio del canale attraverso il crossfader.
> Serve a verificare che l'installazione funzioni e che il plugin compaia in AUM.
> Caricamento della traccia, piatto touch e scratch arrivano nei prossimi aggiornamenti.

## Scaricare l'app

Ogni modifica al codice viene costruita in automatico da GitHub (scheda **Actions**).
Il file pronto è **`ScratchAU.ipa`** nella Release **"ultima"** (scheda **Releases**, a destra nella pagina del repository).

Dall'iPad: apri la pagina della Release in Safari, tocca `ScratchAU.ipa`, poi *Scarica*. Lo trovi nell'app File › Download.

## Installarla sull'iPad (gratis, con il tuo Apple ID)

L'app non è firmata da Apple: la firma **SideStore** con il tuo Apple ID gratuito.
Con un Apple ID gratuito la firma dura 7 giorni; SideStore la rinnova da solo.

**Una volta sola, con il Mac:**
1. Sull'iPad installa **LocalDevVPN** dall'App Store (serve a SideStore per firmare le app senza computer).
2. Sul Mac scarica **iloader** dalla sua pagina ufficiale su GitHub (`nab138/iloader`, sezione *Releases*).
3. Collega l'iPad al Mac con il cavo, sblocca l'iPad e tocca *Autorizza* se lo chiede.
4. Apri iloader, accedi con il tuo Apple ID e scegli di installare **SideStore**: iloader installa l'app e il "file di abbinamento".
5. Sull'iPad: Impostazioni › Generali › VPN e gestione dispositivi › tocca il tuo Apple ID › *Autorizza*.
   Se iPadOS lo chiede, attiva anche la **Modalità sviluppatore** (Impostazioni › Privacy e sicurezza) e riavvia.

**Installare ScratchAU:**
1. Attiva la VPN in LocalDevVPN.
2. Apri SideStore › *My Apps* › **+** › scegli `ScratchAU.ipa` dalla cartella Download.
3. Se chiede cosa fare delle estensioni dell'app, scegli di **tenerle** (*Keep App Extensions*): il plugin è proprio l'estensione.

**Rinnovo:** ogni tanto apri SideStore con LocalDevVPN attiva e tocca *Refresh All* (o lascia fare all'aggiornamento automatico).
Limiti dell'Apple ID gratuito: massimo 3 app installate così (SideStore conta come una) e 10 "App ID" a settimana
(ScratchAU ne usa 2: app + plugin).

**Aggiornare ScratchAU:** scarica di nuovo `ScratchAU.ipa` dalla Release e reinstallalo da SideStore con **+**.

## Usarlo in AUM

1. Apri AUM, aggiungi un canale audio (es. con un'app, un file o il microfono come sorgente).
2. In uno slot del canale scegli *Audio Unit Extensions* › **Toniaudio: ScratchAU**.
3. Tocca il plugin per aprirne la finestra.

### Crossfader dell'X-Session

1. Collega l'X-Session all'iPad (adattatore USB; se non si accende, un hub USB alimentato).
2. In AUM tocca lo slot del plugin e apri **MIDI Control**: trova il parametro *Crossfader*,
   tocca *Learn* e muovi il crossfader dell'X-Session.

Tutto a **sinistra = traccia**, tutto a **destra = ingresso** del canale.
Curve: **Taglio** (da scratch: tutto pieno tranne un taglio netto agli estremi), **Lineare**, **Potenza costante**.
**Inverti** scambia i due lati ("hamster switch"). Anche *Curva* e *Inverti* sono mappabili via MIDI.

## Per chi sviluppa

- `project.yml` – progetto Xcode generato con XcodeGen (nessun `.xcodeproj` nel repository).
- `App/` – app contenitore (solo istruzioni).
- `Extension/` – estensione AUv3 (`aumf`, Music Effect): Audio Unit in Objective-C++, finestra in Swift/UIKit.
- `DSP/` – elaborazione audio in C++ senza dipendenze Apple; regole real-time: niente allocazioni, lock o I/O in `process()`.
- `tests/` – test del DSP, girano anche su Linux:
  `g++ -std=c++17 -IDSP tests/test_dsp.cpp -o test_dsp && ./test_dsp`
- `.github/workflows/build.yml` – test, build per iPad senza firma, `.ipa` nella Release "ultima".
