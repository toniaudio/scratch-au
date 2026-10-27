#include "Deck.h"

#include <algorithm>
#include <cmath>

namespace djaaay {

namespace {

// Interpolazione di Hermite a 4 punti: suono pulito anche a velocità molto diverse da 1.
inline float hermite(float ym1, float y0, float y1, float y2, float t) {
    const float c1 = 0.5f * (y1 - ym1);
    const float c2 = ym1 - 2.5f * y0 + 2.0f * y1 - 0.5f * y2;
    const float c3 = 0.5f * (y2 - ym1) + 1.5f * (y0 - y1);
    return ((c3 * t + c2) * t + c1) * t + y0;
}

}  // namespace

Deck::Deck() = default;

Deck::~Deck() {
    delete traccia_;
    ComandoDeck c;
    while (comandi_.ricevi(c)) delete c.traccia;
    raccogliTracceVecchie();
}

void Deck::prepara(double sampleRateUscita) {
    srUscita_ = sampleRateUscita;
    base_ = traccia_ ? traccia_->sampleRate / srUscita_ : 1.0;
    segui_ = std::exp(-1.0 / (0.002 * srUscita_));
    ritardoTocco_ = 0.006 * srUscita_;
}

void Deck::carica(Traccia* t) {
    ComandoDeck c;
    c.tipo = ComandoDeck::Carica;
    c.traccia = t;
    if (!invia(c)) delete t;
}

void Deck::raccogliTracceVecchie() {
    Traccia* t = nullptr;
    while (daLiberare_.ricevi(t)) delete t;
}

void Deck::applica(const ComandoDeck& c) {
    const double sr = traccia_ ? traccia_->sampleRate : srUscita_;
    switch (c.tipo) {
        case ComandoDeck::Carica:
            if (traccia_ && !daLiberare_.inviaSenzaLock(traccia_)) {
                // coda piena: meglio perdere memoria che liberarla nel thread audio
            }
            traccia_ = c.traccia;
            base_ = traccia_ ? traccia_->sampleRate / srUscita_ : 1.0;
            pos_ = 0.0;
            vel_ = 0.0;
            motore_ = anteprimaCue_ = scivola_ = tocco_ = loop_ = false;
            cue_ = 0.0;
            break;
        case ComandoDeck::Play:
            anteprimaCue_ = false;
            motore_ = c.a > 0.5;
            break;
        case ComandoDeck::PlayPausa:
            anteprimaCue_ = false;
            motore_ = !motore_;
            break;
        case ComandoDeck::CueGiu:
            if (motore_ && !anteprimaCue_) {
                // in play: torna al cue e si ferma
                pos_ = cue_;
                motore_ = false;
                vel_ = 0.0;
                scivola_ = false;
            } else if (!motore_) {
                // da fermo: qui c'è il nuovo cue; tenendo premuto si ascolta da qui
                cue_ = pos_;
                anteprimaCue_ = true;
                motore_ = true;
                scivola_ = false;
                vel_ = base_ * (1.0 + pitch.load());
            }
            break;
        case ComandoDeck::CueSu:
            if (anteprimaCue_) {
                anteprimaCue_ = false;
                motore_ = false;
                vel_ = 0.0;
                pos_ = cue_;
            }
            break;
        case ComandoDeck::Vai:
            pos_ = c.a * sr;
            break;
        case ComandoDeck::ToccoInizio:
            tocco_ = true;
            scivola_ = false;
            toccoAncora_ = toccoBersaglio_ = pos_;
            toccoVelBersaglio_ = 0.0;
            campioniDaUltimoTocco_ = 0.0;
            intervalloTocco_ = 0.0;
            break;
        case ComandoDeck::ToccoSposta:
            if (tocco_) {
                const double nuovo = toccoAncora_ + c.a * sr;
                if (campioniDaUltimoTocco_ > 0.0) {
                    const double v = (nuovo - toccoBersaglio_) / campioniDaUltimoTocco_;
                    toccoVelBersaglio_ = std::clamp(v, -40.0 * base_, 40.0 * base_);
                    intervalloTocco_ = intervalloTocco_ > 0.0
                                           ? 0.7 * intervalloTocco_ + 0.3 * campioniDaUltimoTocco_
                                           : campioniDaUltimoTocco_;
                }
                toccoBersaglio_ = nuovo;
                campioniDaUltimoTocco_ = 0.0;
            }
            break;
        case ComandoDeck::ToccoFine:
            if (tocco_) {
                tocco_ = false;
                scivola_ = true;
            }
            break;
        case ComandoDeck::ImpostaCue:
            cue_ = c.a * sr;
            break;
        case ComandoDeck::Loop:
            loop_ = c.b > c.a;
            loopInizio_ = c.a * sr;
            loopFine_ = c.b * sr;
            break;
    }
}

void Deck::render(float* l, float* r, int frame) {
    ComandoDeck c;
    while (comandi_.ricevi(c)) applica(c);

    if (traccia_ == nullptr) {
        std::fill(l, l + frame, 0.0f);
        std::fill(r, r + frame, 0.0f);
        velocitaLetta_.store(0.0f, std::memory_order_relaxed);
        return;
    }

    const Traccia& t = *traccia_;
    const double fine = static_cast<double>(t.frame());
    const double velMotore = motore_ ? base_ * (1.0 + pitch.load(std::memory_order_relaxed)) : 0.0;

    // Frizione 0 → costante di tempo 0,6 s (scivola), 1 → 20 ms (riparte subito)
    const double f = std::clamp(static_cast<double>(frizione.load(std::memory_order_relaxed)), 0.0, 1.0);
    const double tauFrizione = 0.6 * std::pow(0.02 / 0.6, f);
    const double coeffFrizione = std::exp(-1.0 / (tauFrizione * srUscita_));
    const double coeffPitch = std::exp(-1.0 / (0.03 * srUscita_));
    const double avvio = avvioSecondi.load(std::memory_order_relaxed);
    const double frenata = frenataSecondi.load(std::memory_order_relaxed);
    const double passoAvvio = avvio > 0.0 ? base_ / (avvio * srUscita_) : 1e9;
    const double passoFrenata = frenata > 0.0 ? base_ / (frenata * srUscita_) : 1e9;
    const double soglia = 0.03 * base_;  // sotto questa velocità il suono sfuma (disco quasi fermo)

    for (int i = 0; i < frame; ++i) {
        // --- velocità del disco
        if (tocco_) {
            campioniDaUltimoTocco_ += 1.0;
            if (intervalloTocco_ > 0.0 && campioniDaUltimoTocco_ > 2.0 * intervalloTocco_) toccoVelBersaglio_ = 0.0;
            toccoBersaglio_ += toccoVelBersaglio_;
            const double desiderata =
                std::clamp((toccoBersaglio_ - pos_) / ritardoTocco_, -40.0 * base_, 40.0 * base_);
            vel_ = desiderata + (vel_ - desiderata) * segui_;
        } else if (scivola_) {
            vel_ = velMotore + (vel_ - velMotore) * coeffFrizione;
            if (std::fabs(vel_ - velMotore) < 1e-3 * base_) {
                vel_ = velMotore;
                scivola_ = false;
            }
        } else if (vel_ != velMotore) {
            const double diff = velMotore - vel_;
            if (motore_ && vel_ > 0.0 && std::fabs(diff) < 0.1 * base_) {
                vel_ = velMotore + (vel_ - velMotore) * coeffPitch;  // ritocco del pitch
                if (std::fabs(vel_ - velMotore) < 1e-6 * base_) vel_ = velMotore;
            } else {
                const double passo = motore_ ? passoAvvio : passoFrenata;
                vel_ += std::clamp(diff, -passo, passo);
            }
        }

        // --- lettura con interpolazione
        const double p = std::floor(pos_);
        const int64_t k = static_cast<int64_t>(p);
        const float fr = static_cast<float>(pos_ - p);
        float sl = hermite(t.campione(k - 1, 0), t.campione(k, 0), t.campione(k + 1, 0), t.campione(k + 2, 0), fr);
        float sr = hermite(t.campione(k - 1, 1), t.campione(k, 1), t.campione(k + 1, 1), t.campione(k + 2, 1), fr);
        const double av = std::fabs(vel_);
        if (av < soglia) {
            const float g = static_cast<float>(av / soglia);
            sl *= g * g;
            sr *= g * g;
        }
        l[i] = dcL_.processa(sl);
        r[i] = dcR_.processa(sr);

        // --- avanzamento
        pos_ += vel_;
        if (loop_ && vel_ > 0.0 && pos_ >= loopFine_) pos_ -= (loopFine_ - loopInizio_);
        if (pos_ >= fine) {
            pos_ = fine;
            if (!tocco_) {
                motore_ = anteprimaCue_ = scivola_ = false;
                vel_ = 0.0;
            }
        } else if (pos_ < -60.0 * t.sampleRate) {
            pos_ = -60.0 * t.sampleRate;
        }
    }

    posizione_.store(pos_ / t.sampleRate, std::memory_order_relaxed);
    durata_.store(t.secondi(), std::memory_order_relaxed);
    velocitaLetta_.store(static_cast<float>(vel_ / base_), std::memory_order_relaxed);
    motoreLetto_.store(motore_, std::memory_order_relaxed);
    cueLetto_.store(cue_ / t.sampleRate, std::memory_order_relaxed);
}

}  // namespace djaaay
