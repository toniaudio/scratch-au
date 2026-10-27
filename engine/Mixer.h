// Mixer a due canali: GAIN → EQ (alti/medi/bassi con kill) → FILTRO → volume → crossfader → master.
// Le regolazioni sono atomiche (si possono muovere da qualsiasi thread); render() gira nel thread audio.
#pragma once

#include <atomic>

#include "Filtri.h"

namespace djaaay {

enum CurvaCrossfader : int {
    kCurvaTaglio = 0,   // da scratch: pieno su quasi tutta la corsa, taglio netto agli estremi
    kCurvaLineare = 1,
    kCurvaMorbida = 2,  // potenza costante
};

constexpr float kZonaTaglio = 0.04f;  // frazione della corsa dove il crossfader "taglio" apre il canale

struct Guadagni {
    float a;
    float b;
};

// Guadagni dei due canali per una posizione del crossfader (0 = tutto A, 1 = tutto B).
Guadagni guadagniCrossfader(float x, int curva, bool inverti);

// Manopola EQ (0…1, centro 0,5 = neutro) → guadagno lineare: 0 = kill, 1 = +6 dB.
float guadagnoEQ(float manopola);

struct Canale {
    std::atomic<float> gain{0.5f};     // 0…1 → −12…+12 dB
    std::atomic<float> alti{0.5f};
    std::atomic<float> medi{0.5f};
    std::atomic<float> bassi{0.5f};
    std::atomic<float> filtro{0.0f};   // −1 passa-basso … 0 spento … +1 passa-alto
    std::atomic<float> volume{0.8f};   // fader 0…1
    std::atomic<float> livello{0.0f};  // picco dopo il fader (per il meter)
};

class Mixer {
public:
    void prepara(double sampleRate);

    Canale canale[2];
    std::atomic<float> crossfader{0.5f};
    std::atomic<int> curva{kCurvaTaglio};
    std::atomic<bool> inverti{false};
    std::atomic<float> master{0.8f};
    std::atomic<float> livelloL{0.0f}, livelloR{0.0f};

    // Ingressi: due deck stereo non interleaved (modificati sul posto). Uscita interleaved stereo.
    void render(float* aL, float* aR, float* bL, float* bR, float* uscita, int frame);

private:
    struct Stato {
        CrossoverLR4 xBassi[2], xAlti[2];
        FiltroSVF filtro[2];
        float gain = 1.0f, alti = 1.0f, medi = 1.0f, bassi = 1.0f, volume = 0.64f, xf = 1.0f;
        float filtroMix = 0.0f;  // 0 = filtro escluso, 1 = attivo
    };
    void canaleRender(int n, float* l, float* r, int frame);

    Stato st_[2];
    float sr_ = 48000.0f;
    float smooth_ = 0.0f;   // smoothing del crossfader (per campione)
    float master_ = 0.64f;
    float riduzione_ = 1.0f;  // limitatore
    float rilascio_ = 0.0f;
};

}  // namespace djaaay
