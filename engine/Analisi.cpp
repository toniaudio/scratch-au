#include "Analisi.h"

#include <algorithm>
#include <cmath>

#include "Filtri.h"

namespace djaaay {

namespace {

uint8_t scala(float picco) {
    const float v = std::sqrt(std::min(1.0f, picco)) * 255.0f;
    return static_cast<uint8_t>(std::lround(v));
}

}  // namespace

Waveform analizzaWaveform(const Traccia& t) {
    Waveform w;
    const int64_t n = t.frame();
    const int64_t colonne = (n + Waveform::kFramePerColonna - 1) / Waveform::kFramePerColonna;
    Waveform::Livello base;
    base.bassi.resize(static_cast<size_t>(colonne));
    base.medi.resize(static_cast<size_t>(colonne));
    base.alti.resize(static_cast<size_t>(colonne));

    // Stesse bande dell'EQ del mixer (250 Hz e 2,5 kHz), sul mono L+R
    Isolatore3 eq;
    eq.imposta(250.0f, 2500.0f, static_cast<float>(t.sampleRate));
    for (int64_t c = 0; c < colonne; ++c) {
        float pb = 0.0f, pm = 0.0f, pa = 0.0f;
        const int64_t fine = std::min(n, (c + 1) * Waveform::kFramePerColonna);
        for (int64_t i = c * Waveform::kFramePerColonna; i < fine; ++i) {
            const float x = 0.5f * (t.campione(i, 0) + t.campione(i, 1));
            float b, m, a;
            eq.separa(x, b, m, a);
            pb = std::max(pb, std::fabs(b));
            pm = std::max(pm, std::fabs(m));
            pa = std::max(pa, std::fabs(a));
        }
        base.bassi[c] = scala(pb);
        base.medi[c] = scala(pm);
        base.alti[c] = scala(pa);
    }
    w.livelli.push_back(std::move(base));

    // Livelli più "lontani": massimo di coppie di colonne, finché restano più di 256 colonne
    while (w.livelli.back().colonne() > 256) {
        const Waveform::Livello& prima = w.livelli.back();
        Waveform::Livello l;
        l.framePerColonna = prima.framePerColonna * 2;
        const size_t m = (prima.colonne() + 1) / 2;
        l.bassi.resize(m);
        l.medi.resize(m);
        l.alti.resize(m);
        for (size_t i = 0; i < m; ++i) {
            const size_t j = std::min(2 * i + 1, prima.colonne() - 1);
            l.bassi[i] = std::max(prima.bassi[2 * i], prima.bassi[j]);
            l.medi[i] = std::max(prima.medi[2 * i], prima.medi[j]);
            l.alti[i] = std::max(prima.alti[2 * i], prima.alti[j]);
        }
        w.livelli.push_back(std::move(l));
    }
    return w;
}

}  // namespace djaaay
