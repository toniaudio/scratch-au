#include "Analisi.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>

#include "Filtri.h"

namespace djaaay {

namespace {

uint8_t scala(float picco) {
    const float v = std::sqrt(std::min(1.0f, picco)) * 255.0f;
    return static_cast<uint8_t>(std::lround(v));
}

// Livelli più "lontani": massimo di coppie di colonne, finché restano più di 256 colonne.
void costruisciLivelli(Waveform& w) {
    w.livelli.resize(1);
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
}

const char kFirma[4] = {'D', 'V', 'S', 'W'};
constexpr uint32_t kVersioneFile = 1;

}  // namespace

bool salvaWaveform(const Waveform& w, const char* percorso) {
    if (w.livelli.empty()) return false;
    const Waveform::Livello& l = w.livelli[0];
    FILE* f = std::fopen(percorso, "wb");
    if (f == nullptr) return false;
    const uint32_t testa[2] = {kVersioneFile, static_cast<uint32_t>(l.framePerColonna)};
    const uint64_t colonne = l.colonne();
    bool ok = std::fwrite(kFirma, 1, 4, f) == 4 && std::fwrite(testa, sizeof testa, 1, f) == 1 &&
              std::fwrite(&colonne, sizeof colonne, 1, f) == 1;
    for (const auto* banda : {&l.bassi, &l.medi, &l.alti})
        ok = ok && std::fwrite(banda->data(), 1, banda->size(), f) == banda->size();
    return std::fclose(f) == 0 && ok;
}

bool caricaWaveform(const char* percorso, Waveform& w) {
    FILE* f = std::fopen(percorso, "rb");
    if (f == nullptr) return false;
    char firma[4];
    uint32_t testa[2];
    uint64_t colonne = 0;
    bool ok = std::fread(firma, 1, 4, f) == 4 && std::memcmp(firma, kFirma, 4) == 0 &&
              std::fread(testa, sizeof testa, 1, f) == 1 && testa[0] == kVersioneFile &&
              testa[1] == Waveform::kFramePerColonna && std::fread(&colonne, sizeof colonne, 1, f) == 1 &&
              colonne < (1ull << 28);  // file rovinato: non si alloca all'infinito
    Waveform::Livello l;
    if (ok) {
        for (auto* banda : {&l.bassi, &l.medi, &l.alti}) {
            banda->resize(static_cast<size_t>(colonne));
            ok = ok && std::fread(banda->data(), 1, banda->size(), f) == banda->size();
        }
    }
    std::fclose(f);
    if (!ok) return false;
    w.livelli.clear();
    w.livelli.push_back(std::move(l));
    costruisciLivelli(w);
    return true;
}

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
    costruisciLivelli(w);
    return w;
}

}  // namespace djaaay
