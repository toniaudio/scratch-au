// ScratchDSP: elaborazione audio del plugin, senza dipendenze da Apple.
// Tutto ciò che sta in process() gira nel thread audio: niente allocazioni, lock o I/O.
//
// Fase "plugin vuoto": l'ingresso passa in uscita (thru) attraverso il crossfader.
// Il lato "traccia" del crossfader per ora è silenzio: il player arriverà nella fase successiva.
#pragma once

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>

namespace scratch {

enum ParamAddress : uint64_t {
    kParamCrossfader = 0,  // 0 = solo traccia, 1 = solo ingresso
    kParamCurva = 1,       // CurvaCrossfader
    kParamInverti = 2,     // "hamster": inverte i due lati
};

enum CurvaCrossfader : int {
    kCurvaTaglio = 0,      // da scratch: pieno su quasi tutta la corsa, taglio netto agli estremi
    kCurvaLineare = 1,
    kCurvaPotenza = 2,     // potenza costante
};

// Larghezza della zona di taglio della curva da scratch (frazione della corsa).
constexpr float kZonaTaglio = 0.04f;

struct Guadagni {
    float traccia;
    float ingresso;
};

inline Guadagni guadagniCrossfader(float x, int curva, bool inverti) {
    x = std::clamp(x, 0.0f, 1.0f);
    if (inverti) x = 1.0f - x;
    switch (curva) {
        case kCurvaLineare:
            return {1.0f - x, x};
        case kCurvaPotenza: {
            const float a = x * 1.57079632679f;
            return {std::cos(a), std::sin(a)};
        }
        default:
            return {std::clamp((1.0f - x) / kZonaTaglio, 0.0f, 1.0f),
                    std::clamp(x / kZonaTaglio, 0.0f, 1.0f)};
    }
}

class ScratchDSP {
public:
    void prepare(double sampleRate, int canali) {
        canali_ = canali;
        // smoothing di ~1 ms: toglie i click ma lascia i tagli netti
        coeff_ = std::exp(-1.0f / (0.001f * static_cast<float>(sampleRate)));
        const Guadagni g = guadagniAttuali();
        gTraccia_ = g.traccia;
        gIngresso_ = g.ingresso;
    }

    void setParameter(uint64_t address, float valore) {
        switch (address) {
            case kParamCrossfader: crossfader_.store(std::clamp(valore, 0.0f, 1.0f)); break;
            case kParamCurva: curva_.store(std::clamp(static_cast<int>(std::lround(valore)), 0, 2)); break;
            case kParamInverti: inverti_.store(valore >= 0.5f); break;
            default: break;
        }
    }

    float getParameter(uint64_t address) const {
        switch (address) {
            case kParamCrossfader: return crossfader_.load();
            case kParamCurva: return static_cast<float>(curva_.load());
            case kParamInverti: return inverti_.load() ? 1.0f : 0.0f;
            default: return 0.0f;
        }
    }

    // ingresso e uscita: array di `canali` puntatori a float (non interleaved).
    // ingresso può essere nullptr (nessun audio in arrivo) e può coincidere con l'uscita.
    void process(const float* const* ingresso, float* const* uscita, int canali, int frame) {
        const Guadagni obiettivo = guadagniAttuali();
        const float c = coeff_;
        float gT = gTraccia_;
        float gI = gIngresso_;
        canali = std::min(canali, canali_ > 0 ? canali_ : canali);
        for (int i = 0; i < frame; ++i) {
            gT = obiettivo.traccia + (gT - obiettivo.traccia) * c;
            gI = obiettivo.ingresso + (gI - obiettivo.ingresso) * c;
            for (int ch = 0; ch < canali; ++ch) {
                const float in = ingresso ? ingresso[ch][i] : 0.0f;
                const float traccia = 0.0f;  // il player arriverà qui
                uscita[ch][i] = traccia * gT + in * gI;
            }
        }
        gTraccia_ = gT;
        gIngresso_ = gI;
    }

private:
    Guadagni guadagniAttuali() const {
        return guadagniCrossfader(crossfader_.load(), curva_.load(), inverti_.load());
    }

    std::atomic<float> crossfader_{0.5f};
    std::atomic<int> curva_{kCurvaTaglio};
    std::atomic<bool> inverti_{false};

    int canali_ = 2;
    float coeff_ = 0.0f;
    float gTraccia_ = 1.0f;
    float gIngresso_ = 1.0f;
};

}  // namespace scratch
