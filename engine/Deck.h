// Un deck: legge una Traccia a velocità variabile come un giradischi.
//
// Il movimento del disco può venire da:
//  - motore (Play/Stop, con tempi di avvio e frenata, pitch);
//  - dito sul disco o sulla waveform (scratch): il disco segue la posizione del dito;
//  - quando il dito si stacca, il disco torna alla velocità del motore con la "frizione" dello slipmat;
//  - in futuro il timecode (DVS).
// Tutto quello che sta in render() gira nel thread audio.
#pragma once

#include <atomic>
#include <cstdint>

#include "Coda.h"
#include "Filtri.h"
#include "Traccia.h"

namespace djaaay {

// Messaggi dall'interfaccia al deck (passano dalla coda, li applica il thread audio).
struct ComandoDeck {
    enum Tipo : uint8_t {
        Carica,        // traccia = nuova traccia (il deck ne diventa proprietario)
        Play,          // a = 1 acceso, 0 spento
        PlayPausa,
        CueGiu,        // tasto CUE premuto
        CueSu,         // tasto CUE rilasciato
        Vai,           // a = posizione in secondi
        ToccoInizio,   // dito appoggiato sul disco
        ToccoSposta,   // a = spostamento in secondi dal punto in cui il dito si è appoggiato
        ToccoFine,
        ImpostaCue,    // a = secondi
        Loop,          // a = inizio, b = fine in secondi; b <= a spegne il loop
    };
    Tipo tipo = Play;
    double a = 0.0;
    double b = 0.0;
    Traccia* traccia = nullptr;
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
    void raccogliTracceVecchie();      // libera le tracce sostituite (chiamare dal thread dell'interfaccia)

    std::atomic<float> pitch{0.0f};          // −0,5…+0,5 (es. 0,08 = +8%)
    std::atomic<float> frizione{0.35f};      // 0 = il disco scivola a lungo, 1 = riparte subito
    std::atomic<float> avvioSecondi{0.15f};
    std::atomic<float> frenataSecondi{0.40f};

    // Letture per l'interfaccia (aggiornate a ogni blocco)
    double posizioneSecondi() const { return posizione_.load(std::memory_order_relaxed); }
    double durataSecondi() const { return durata_.load(std::memory_order_relaxed); }
    float velocita() const { return velocitaLetta_.load(std::memory_order_relaxed); }  // 1 = normale
    bool inPlay() const { return motoreLetto_.load(std::memory_order_relaxed); }
    double cueSecondi() const { return cueLetto_.load(std::memory_order_relaxed); }

    // Thread audio: scrive `frame` campioni stereo non interleaved in l, r.
    void render(float* l, float* r, int frame);

private:
    void applica(const ComandoDeck& c);
    // Sposta la puntina: breve dissolvenza dalla posizione vecchia (niente click) e il dito resta "agganciato".
    void salta(double nuovaPos);

    static constexpr int kSfuma = 96;  // ~2 ms di dissolvenza sui salti

    Coda<ComandoDeck, 256> comandi_;
    Coda<Traccia*, 16> daLiberare_;

    // Stato del thread audio
    Traccia* traccia_ = nullptr;
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

    // Coefficienti (calcolati in prepara)
    double segui_ = 0.0;       // smoothing della velocità mentre si segue il dito
    double ritardoTocco_ = 0.0;

    std::atomic<double> posizione_{0.0};
    std::atomic<double> durata_{0.0};
    std::atomic<float> velocitaLetta_{0.0f};
    std::atomic<bool> motoreLetto_{false};
    std::atomic<double> cueLetto_{0.0};
};

}  // namespace djaaay
