// Campionatore (modalità One Deck): 8 pad con suoni o loop presi dalla libreria, sul canale B del mixer.
// Ogni pad ha un modo: UNA VOLTA (suona tutto, ritoccato riparte), LOOP (acceso/spento a ogni tocco),
// TIENI (suona finché il pad è premuto). Le partenze e gli stop hanno una dissolvenza di pochi ms (niente click).
// I comandi passano da una coda (qualsiasi thread); render() gira nel thread audio e non alloca.
#pragma once

#include <atomic>
#include <cstdint>

#include "Coda.h"
#include "Traccia.h"

namespace djaaay {

enum ModoPad : int { kUnaVolta = 0, kLoopPad = 1, kTieni = 2 };

struct ComandoCampionatore {
    enum Tipo : uint8_t {
        Carica,  // traccia = nuovo suono del pad (il campionatore ne diventa proprietario)
        Togli,
        Premi,
        Lascia,
        Ferma,   // ferma tutti i pad
    };
    Tipo tipo = Premi;
    int pad = 0;
    Traccia* traccia = nullptr;
};

class Campionatore {
public:
    static constexpr int kPad = 8;

    Campionatore();
    ~Campionatore();

    void prepara(double sampleRateUscita);  // fuori dal thread audio

    // Qualsiasi thread
    bool invia(const ComandoCampionatore& c) { return comandi_.invia(c); }
    void carica(int pad, Traccia* t);    // passa la proprietà della traccia
    void raccogliTracceVecchie();        // libera i suoni sostituiti (thread dell'interfaccia)
    std::atomic<int> modo[kPad];

    // Letture per l'interfaccia
    bool suona(int pad) const { return suona_[pad].load(std::memory_order_relaxed); }
    float avanzamento(int pad) const { return avanzamento_[pad].load(std::memory_order_relaxed); }  // 0…1

    // Thread audio: SOMMA i pad che suonano a l, r (stereo non interleaved).
    void render(float* l, float* r, int frame);

private:
    struct Voce {
        Traccia* traccia = nullptr;
        double pos = 0.0;
        bool attiva = false;
        bool premuto = false;
        float guadagno = 0.0f;  // dissolvenza in entrata/uscita
        bool spegni = false;
        // coda del suono interrotto da una ripartenza (sfuma mentre il nuovo parte)
        double posCoda = 0.0;
        float guadagnoCoda = 0.0f;
    };
    void applica(const ComandoCampionatore& c);
    void parti(Voce& v);

    Coda<ComandoCampionatore, 128> comandi_;
    Coda<Traccia*, 32> daLiberare_;
    Voce voci_[kPad];
    double srUscita_ = 48000.0;
    float passoEntrata_ = 0.0f, passoUscita_ = 0.0f;
    std::atomic<bool> suona_[kPad];
    std::atomic<float> avanzamento_[kPad];
};

}  // namespace djaaay
