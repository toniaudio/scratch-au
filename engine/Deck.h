// Un deck: legge una Traccia a velocità variabile come un giradischi.
//
// Il movimento del disco può venire da:
//  - motore (Play/Stop, con tempi di avvio e frenata, pitch);
//  - dito sul disco o sulla waveform (scratch): il disco segue la posizione del dito;
//  - quando il dito si stacca, il disco torna alla velocità del motore con la "frizione" dello slipmat;
//  - il timecode (DVS): con `dvs` acceso il disco va alla velocità del vinile timecode.
// Tutto quello che sta in render() gira nel thread audio.
#pragma once

#include <atomic>
#include <cmath>
#include <cstdint>
#include <vector>

#include "Coda.h"
#include "Filtri.h"
#include "Griglia.h"
#include "Keylock.h"
#include "Traccia.h"

namespace djaaay {

// Messaggi dall'interfaccia al deck (passano dalla coda, li applica il thread audio).
struct ComandoDeck {
    enum Tipo : uint8_t {
        Carica,        // traccia = nuova traccia (il deck ne prende un riferimento)
        Play,          // a = 1 acceso, 0 spento
        PlayPausa,
        CueGiu,        // tasto CUE premuto
        CueSu,         // tasto CUE rilasciato
        Vai,           // a = posizione in secondi
        ToccoInizio,   // dito appoggiato sul disco
        ToccoSposta,   // a = spostamento in secondi dal punto in cui il dito si è appoggiato
        ToccoFine,
        ImpostaCue,    // a = secondi
        Loop,          // a = inizio, b = fine in secondi; b <= a spegne il loop; aggancia = sui colpi veri vicini
        Griglia,       // griglia = griglia delle battute del brano (il deck ne diventa proprietario), per il SYNC
    };
    Tipo tipo = Play;
    double a = 0.0;
    double b = 0.0;
    bool aggancia = false;
    Traccia* traccia = nullptr;
    djaaay::Griglia* griglia = nullptr;
};

class Deck {
public:
    Deck();
    ~Deck();

    // Fuori dal thread audio, prima di render().
    void prepara(double sampleRateUscita);

    // Qualsiasi thread: comandi e regolazioni.
    bool invia(const ComandoDeck& c) { return comandi_.invia(c); }
    void carica(Traccia* t);           // passa la proprietà della traccia al deck
    void impostaGriglia(Griglia* g);   // passa la proprietà della griglia (nullptr = nessuna)
    void raccogliTracceVecchie();      // libera tracce e griglie sostituite (chiamare dal thread dell'interfaccia)

    std::atomic<float> pitch{0.0f};          // −0,5…+0,5 (es. 0,08 = +8%)
    std::atomic<float> frizione{0.35f};      // 0 = il disco scivola a lungo, 1 = riparte subito
    std::atomic<float> avvioSecondi{0.15f};
    std::atomic<float> frenataSecondi{0.40f};
    std::atomic<bool> keylock{false};        // KEY: cambiando il pitch il tono resta quello originale
    std::atomic<bool> dvs{false};            // segue il vinile timecode (velocità da render, posizione da dvsPosizione)
    std::atomic<bool> sincro{false};         // SYNC: tempo e battute seguono l'altro deck (lo fa il Motore)

    // Letture per l'interfaccia (aggiornate a ogni blocco)
    double posizioneSecondi() const { return posizione_.load(std::memory_order_relaxed); }
    double durataSecondi() const { return durata_.load(std::memory_order_relaxed); }
    float velocita() const { return velocitaLetta_.load(std::memory_order_relaxed); }  // 1 = normale
    bool inPlay() const { return motoreLetto_.load(std::memory_order_relaxed); }
    double cueSecondi() const { return cueLetto_.load(std::memory_order_relaxed); }
    bool keylockInUso() const { return keylockInUso_.load(std::memory_order_relaxed); }  // false durante scratch
    float pitchEffettivo() const { return pitchEffettivo_.load(std::memory_order_relaxed); }  // col SYNC ≠ dal fader
    // Dove sta davvero il loop (agganciato ai colpi: può differire di qualche ms da quello chiesto); fine 0 = spento
    double loopInizioSecondi() const { return loopInizioLetto_.load(std::memory_order_relaxed); }
    double loopFineSecondi() const { return loopFineLetto_.load(std::memory_order_relaxed); }

    // --- SYNC: le usa il Motore nel thread audio, prima di render()
    bool haGriglia() const { return griglia_ != nullptr && traccia_ != nullptr; }
    double battutaQui() const;                 // battuta frazionaria della puntina
    double durataBattutaQui() const;           // secondi di brano della battuta in corso
    double durataMediaQui() const;             // secondi di brano per battuta, in media qui (tempo stabile)
    double rapportoMotore() const;             // secondi di brano al secondo, a motore (1 + pitch, o del SYNC)
    bool aMotore() const { return motore_ && !tocco_ && !scivola_ && !dvs.load(std::memory_order_relaxed); }
    bool suona() const { return motore_; }
    // a velocità piena (finito l'avvio "da giradischi"): solo allora si allinea la fase
    bool aRegime() const { return aMotore() && std::fabs(vel_ - base_ * rapportoMotore()) < 0.002 * base_; }
    bool loopCorto() const;                    // loop acceso più corto di una battuta (la fase salta a ogni giro)
    // velocità imposta dal SYNC (0 = vale il pitch del fader) e quella "di base", senza le correzioni di fase: è
    // quella che si mostra sul fader e che segue chi è a sua volta guida
    void impostaSincro(double rapporto, double base) {
        rapportoSincro_ = rapporto;
        rapportoBase_ = base;
    }
    double rapportoBase() const;
    double rapportoBaseAttuale() const { return rapportoBase_; }  // 0 = SYNC non ancora agganciato
    void spostaDiBattute(double battute);      // salto di fase (con dissolvenza)
    bool chiedeAllineamento() const { return allinea_; }  // appena partito / saltato: allineare subito
    void allineato() { allinea_ = false; }
    double fuoriFase = 0.0;  // SYNC: da quanti secondi si è fuori battuta di molto (lo usa il Motore)

    // Thread audio: scrive `frame` campioni stereo non interleaved in l, r. `velocitaTimecode` (se il DVS è
    // acceso) = velocità del vinile per ogni campione (1 = normale).
    void render(float* l, float* r, int frame, const float* velocitaTimecode = nullptr);
    // Thread audio, DVS in modalità assoluta: posizione letta dal vinile. Si salta lì quando la puntina viene
    // appoggiata (`dopoSilenzio`) o, senza protezione, quando la puntina salta.
    void dvsPosizione(double secondi, bool riacquisito, bool dopoSilenzio, bool protezione);

private:
    static constexpr int kMaxBlocco = 256;
    void renderBlocco(float* l, float* r, int frame, const float* timecode);
    void applica(const ComandoDeck& c);
    // Sposta la puntina: breve dissolvenza dalla posizione vecchia (niente click) e il dito resta "agganciato".
    // `perKeylock`: il keylock, che legge in anticipo, deve ripartire (non serve per il giro del loop, che prevede).
    void salta(double nuovaPos, bool perKeylock = true);
    // Il colpo vero (attacco) più vicino a `pos` (frame), entro ~20 ms; -1 se lì non ce n'è uno netto
    double attaccoVicino(double pos) const;

    static constexpr int kSfuma = 96;  // ~2 ms di dissolvenza sui salti

    Coda<ComandoDeck, 256> comandi_;
    Coda<Traccia*, 16> daLiberare_;
    Coda<Griglia*, 16> grigliaDaLiberare_;

    // Stato del thread audio
    Traccia* traccia_ = nullptr;
    Griglia* griglia_ = nullptr;
    double rapportoSincro_ = 0.0;   // velocità imposta dal SYNC (0 = niente)
    double rapportoBase_ = 0.0;     // la stessa senza le correzioni di fase
    bool allinea_ = false;          // SYNC: allineare subito la fase (dopo Play, un salto o l'aggancio)
    bool sincroPrima_ = false;
    double srUscita_ = 48000.0;
    double base_ = 1.0;        // velocità normale in frame della traccia per frame di uscita
    double pos_ = 0.0;         // frame della traccia
    double vel_ = 0.0;         // frame della traccia per frame di uscita
    bool motore_ = false;
    bool anteprimaCue_ = false;
    double cue_ = 0.0;
    bool scivola_ = false;     // dito appena staccato: si torna al motore con la frizione
    bool tocco_ = false;
    double toccoAncora_ = 0.0;
    double toccoBersaglio_ = 0.0;      // dove deve andare il disco (posizione del dito, prevista tra un tocco e l'altro)
    double toccoUltimo_ = 0.0;         // ultima posizione del dito ricevuta davvero
    double toccoVelBersaglio_ = 0.0;
    double campioniDaUltimoTocco_ = 0.0;
    double intervalloTocco_ = 0.0;
    bool loop_ = false;
    double loopInizio_ = 0.0, loopFine_ = 0.0;
    double posVecchia_ = 0.0;   // durante la dissolvenza di un salto
    int sfumaRestanti_ = 0;
    BloccaDC dcL_, dcR_;

    // Keylock: legge il brano in anticipo del suo ritardo, così l'uscita resta allineata alla puntina
    Keylock keylock_;
    std::vector<float> kInL_, kInR_, kOutL_, kOutR_, kPeso_;
    bool keylockAcceso_ = false;
    double ritardoKey_ = 0.0;
    int64_t caldoKey_ = 0;       // campioni dati al keylock da quando è acceso
    int64_t attesaKey_ = 0;      // dopo un salto si usa il suono diretto finché il keylock non ha l'audio nuovo
    float pesoKey_ = 0.0f;       // 0 = suono diretto, 1 = keylock (dissolvenza tra i due)
    float coeffPesoKey_ = 0.0f;

    // Coefficienti (calcolati in prepara)
    double segui_ = 0.0;       // smoothing della velocità mentre si segue il dito
    double ritardoTocco_ = 0.0;

    std::atomic<double> posizione_{0.0};
    std::atomic<double> durata_{0.0};
    std::atomic<float> velocitaLetta_{0.0f};
    std::atomic<bool> motoreLetto_{false};
    std::atomic<double> cueLetto_{0.0};
    std::atomic<bool> keylockInUso_{false};
    std::atomic<float> pitchEffettivo_{0.0f};
    std::atomic<double> loopInizioLetto_{0.0}, loopFineLetto_{0.0};
};

}  // namespace djaaay
