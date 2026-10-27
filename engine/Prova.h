// Segnaposto della tappa 1: tono di prova. Verrà sostituito dal motore a due deck.
#pragma once
#include <atomic>
#include <cmath>

namespace { constexpr double kDuePi = 6.283185307179586; }

namespace djaaay {

class Prova {
public:
    void prepare(double sampleRate) { sr_ = sampleRate; }
    void setAcceso(bool acceso) { acceso_.store(acceso); }
    bool acceso() const { return acceso_.load(); }

    void render(float* out, int frame, int canali) {
        const double passo = kDuePi * 440.0 / sr_;
        const float obiettivo = acceso_.load() ? 0.2f : 0.0f;
        for (int i = 0; i < frame; ++i) {
            vol_ += (obiettivo - vol_) * 0.002f;
            const float s = static_cast<float>(std::sin(fase_)) * vol_;
            fase_ += passo;
            if (fase_ > kDuePi) fase_ -= kDuePi;
            for (int c = 0; c < canali; ++c) out[i * canali + c] = s;
        }
    }

private:
    double sr_ = 48000.0;
    double fase_ = 0.0;
    float vol_ = 0.0f;
    std::atomic<bool> acceso_{false};
};

}  // namespace djaaay
