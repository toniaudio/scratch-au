#include "Campionatore.h"

#include <algorithm>
#include <cmath>

namespace djaaay {

Campionatore::Campionatore() {
    for (int i = 0; i < kPad; ++i) {
        modo[i].store(kUnaVolta);
        suona_[i].store(false);
        avanzamento_[i].store(0.0f);
    }
}

Campionatore::~Campionatore() {
    for (auto& v : voci_) delete v.traccia;
    ComandoCampionatore c;
    while (comandi_.ricevi(c)) delete c.traccia;
    raccogliTracceVecchie();
}

void Campionatore::prepara(double sampleRateUscita) {
    srUscita_ = sampleRateUscita;
    passoEntrata_ = static_cast<float>(1.0 / (0.002 * sampleRateUscita));  // 2 ms
    passoUscita_ = static_cast<float>(1.0 / (0.008 * sampleRateUscita));   // 8 ms
}

void Campionatore::carica(int pad, Traccia* t) {
    ComandoCampionatore c;
    c.tipo = ComandoCampionatore::Carica;
    c.pad = pad;
    c.traccia = t;
    if (pad < 0 || pad >= kPad || !invia(c)) delete t;
}

void Campionatore::raccogliTracceVecchie() {
    Traccia* t = nullptr;
    while (daLiberare_.ricevi(t)) delete t;
}

void Campionatore::parti(Voce& v) {
    if (v.traccia == nullptr) return;
    // il suono che stava andando sfuma mentre riparte dall'inizio
    if (v.attiva && v.guadagno > 0.0f) {
        v.posCoda = v.pos;
        v.guadagnoCoda = v.guadagno;
    }
    v.pos = 0.0;
    v.guadagno = 0.0f;
    v.attiva = true;
    v.spegni = false;
}

void Campionatore::applica(const ComandoCampionatore& c) {
    if (c.pad < 0 || c.pad >= kPad) {
        delete c.traccia;
        return;
    }
    Voce& v = voci_[c.pad];
    const int m = modo[c.pad].load(std::memory_order_relaxed);
    switch (c.tipo) {
        case ComandoCampionatore::Carica:
        case ComandoCampionatore::Togli:
            if (v.traccia && !daLiberare_.inviaSenzaLock(v.traccia)) {
                // coda piena: meglio perdere memoria che liberarla nel thread audio
            }
            v = Voce{};
            v.traccia = c.tipo == ComandoCampionatore::Carica ? c.traccia : nullptr;
            break;
        case ComandoCampionatore::Premi:
            v.premuto = true;
            if (m == kLoopPad && v.attiva && !v.spegni) {
                v.spegni = true;  // loop acceso: si spegne
            } else {
                parti(v);
            }
            break;
        case ComandoCampionatore::Lascia:
            v.premuto = false;
            if (m == kTieni && v.attiva) v.spegni = true;
            break;
        case ComandoCampionatore::Ferma:
            for (auto& w : voci_)
                if (w.attiva) w.spegni = true;
            break;
    }
}

void Campionatore::render(float* l, float* r, int frame) {
    ComandoCampionatore c;
    while (comandi_.ricevi(c)) applica(c);

    for (int p = 0; p < kPad; ++p) {
        Voce& v = voci_[p];
        const Traccia* t = v.traccia;
        if (t == nullptr || t->frame() == 0 || (!v.attiva && v.guadagnoCoda <= 0.0f)) {
            suona_[p].store(false, std::memory_order_relaxed);
            continue;
        }
        const bool loop = modo[p].load(std::memory_order_relaxed) == kLoopPad;
        const double passo = t->sampleRate / srUscita_;
        const auto n = static_cast<double>(t->frame());
        for (int i = 0; i < frame; ++i) {
            if (v.attiva) {
                if (v.spegni) {
                    v.guadagno -= passoUscita_;
                    if (v.guadagno <= 0.0f) {
                        v.guadagno = 0.0f;
                        v.attiva = false;
                    }
                } else if (v.guadagno < 1.0f) {
                    v.guadagno = std::min(1.0f, v.guadagno + passoEntrata_);
                }
            }
            if (v.attiva) {
                const auto k = static_cast<int64_t>(v.pos);
                const float f = static_cast<float>(v.pos - static_cast<double>(k));
                // nel loop il campione dopo l'ultimo è il primo: il giro non fa click
                const int64_t k1 = (loop && k + 1 >= t->frame()) ? 0 : k + 1;
                l[i] += (t->campione(k, 0) * (1.0f - f) + t->campione(k1, 0) * f) * v.guadagno;
                r[i] += (t->campione(k, 1) * (1.0f - f) + t->campione(k1, 1) * f) * v.guadagno;
                v.pos += passo;
                if (v.pos >= n) {
                    if (loop && !v.spegni) {
                        v.pos -= n;
                    } else {
                        v.attiva = false;  // finito il suono
                        v.guadagno = 0.0f;
                    }
                }
            }
            if (v.guadagnoCoda > 0.0f) {
                const auto k = static_cast<int64_t>(v.posCoda);
                l[i] += t->campione(k, 0) * v.guadagnoCoda;
                r[i] += t->campione(k, 1) * v.guadagnoCoda;
                v.posCoda += passo;
                v.guadagnoCoda -= passoUscita_;
            }
        }
        suona_[p].store(v.attiva && !v.spegni, std::memory_order_relaxed);
        avanzamento_[p].store(static_cast<float>(std::clamp(v.pos / n, 0.0, 1.0)), std::memory_order_relaxed);
    }
}

}  // namespace djaaay
