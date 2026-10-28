#include "Effetti.h"

#include <algorithm>
#include <cmath>

namespace djaaay {

namespace {

constexpr double kMaxRitardo = 4.4;   // secondi (4 battiti fino a ~55 BPM)
constexpr double kLinea = 4.7;        // la linea è un po' più lunga: c'è la modulazione del dub
constexpr double kCattura = 12.0;     // secondi di deck tenuti per lo STRETCH
constexpr float kStanzaMax = 1.5f;    // la STANZA va da 0,5 a 1,5 volte le lunghezze di Freeverb
constexpr float kCoda[kNumCode] = {0.76f, 0.86f, 0.94f};
constexpr int kVitaSecondi = 30;      // dopo lo spegnimento le code si elaborano al massimo per tanto

// Freeverb, accordato a 44,1 kHz: lunghezze in campioni (il canale destro è allargato di 23)
constexpr int kPettini[8] = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
constexpr int kPassaTutto[4] = {556, 441, 341, 225};
constexpr int kAllarga = 23;

// Saturazione morbida (approssimazione di tanh): il dub può andare in auto-oscillazione senza esplodere
inline float satura(float x) {
    x = std::clamp(x, -3.0f, 3.0f);
    return x * (27.0f + x * x) / (27.0f + 9.0f * x * x);
}

inline float verso(float attuale, float bersaglio, float k) { return bersaglio + (attuale - bersaglio) * k; }

}  // namespace

float Effetto::Linea::leggi(const std::vector<float>& b, double ritardo) const {
    const int n = static_cast<int>(b.size());
    double p = static_cast<double>(scrivi) - ritardo;
    while (p < 0.0) p += n;
    const int i0 = static_cast<int>(p) % n;
    const int i1 = i0 + 1 >= n ? 0 : i0 + 1;
    const float f = static_cast<float>(p - std::floor(p));
    return b[static_cast<size_t>(i0)] + (b[static_cast<size_t>(i1)] - b[static_cast<size_t>(i0)]) * f;
}

Effetto::Effetto() {
    const float dw[4] = {0.35f, 0.45f, 0.30f, 1.0f};
    const float p1[4] = {0.4f, 0.7f, 0.7f, 0.3f};
    const float p2[4] = {0.0f, 0.2f, 0.5f, 0.5f};
    const int sc[4] = {1, 3, 2, 0};  // 1/4 · 3/4 · lunga · ½×
    for (int t = 0; t < 4; ++t) {
        dryWet[t].store(dw[t]);
        param1[t].store(p1[t]);
        param2[t].store(p2[t]);
        scelta[t].store(sc[t]);
    }
}

void Effetto::prepara(double sampleRate) {
    sr_ = sampleRate;
    const float sr = static_cast<float>(sampleRate);
    kRampa_ = coeffSmooth(0.015f, sr);
    kTempo_ = coeffSmooth(0.08f, sr);
    kCodaStretch_ = coeffSmooth(0.15f, sr);
    coeffLpEco_ = std::exp(-2.0f * kPi * 9000.0f / sr);

    const int linea = static_cast<int>(kLinea * sampleRate) + 8;
    eco_.prepara(linea);
    dub_.prepara(linea);
    const double scala = sampleRate / 44100.0;
    for (int ch = 0; ch < 2; ++ch) {
        for (int k = 0; k < 8; ++k) {
            auto& p = pettini_[ch][k];
            p.base = static_cast<int>(std::max(1.0, (kPettini[k] + ch * kAllarga) * scala));
            p.b.assign(static_cast<size_t>(p.base * kStanzaMax) + 2, 0.0f);
        }
        for (int k = 0; k < 4; ++k)
            passaTutto_[ch][k].b.assign(static_cast<size_t>(std::max(1.0, (kPassaTutto[k] + ch * kAllarga) * scala)), 0.0f);
    }
    const auto cattura = static_cast<size_t>(kCattura * sampleRate);
    catturaL_.assign(cattura, 0.0f);
    catturaR_.assign(cattura, 0.0f);
    catturati_ = 0;
    for (int e = 0; e < 3; ++e) {
        svuota(e);
        mandata_[e] = 0.0f;
        vita_[e] = 0;
    }
    stretchVivo_ = false;
    pesoStretch_ = 0.0f;
}

void Effetto::svuota(int e) {
    if (e == kEcho) {
        eco_.azzera();
        ritardoEco_ = 0.0;
        lpEcoL_ = lpEcoR_ = 0.0f;
    } else if (e == kDub) {
        dub_.azzera();
        ritardoDub_ = 0.0;
        faseWow_ = 0.0;
        for (auto& f : dubLp_) f.azzera();
        for (auto& f : dubHp_) f.azzera();
    } else {
        for (auto& c : pettini_)
            for (auto& p : c) {
                std::fill(p.b.begin(), p.b.end(), 0.0f);
                p.memoria = 0.0f;
                p.i = 0;
            }
        for (auto& c : passaTutto_)
            for (auto& a : c) {
                std::fill(a.b.begin(), a.b.end(), 0.0f);
                a.i = 0;
            }
    }
}

void Effetto::render(float* l, float* r, int frame) {
    if (catturaL_.empty()) return;  // non preparato
    for (int fatti = 0; fatti < frame; fatti += kPezzo) pezzo(l + fatti, r + fatti, std::min(kPezzo, frame - fatti));
}

void Effetto::pezzo(float* l, float* r, int n) {
    const bool on = acceso.load(std::memory_order_relaxed);
    const int t = std::clamp(tipo.load(std::memory_order_relaxed), 0, 3);
    float p1[4], p2[4], w[4];
    for (int k = 0; k < 4; ++k) {
        p1[k] = std::clamp(param1[k].load(std::memory_order_relaxed), 0.0f, 1.0f);
        p2[k] = std::clamp(param2[k].load(std::memory_order_relaxed), 0.0f, 1.0f);
        w[k] = std::clamp(dryWet[k].load(std::memory_order_relaxed), 0.0f, 1.0f);
    }
    const float battito = std::clamp(secondiBattito.load(std::memory_order_relaxed), 0.05f, 4.0f);
    auto ritardoDi = [&](int e) {
        const int bi = std::clamp(scelta[e].load(std::memory_order_relaxed), 0, kNumBattute - 1);
        return std::clamp(static_cast<double>(kBattute[bi] * battito), 0.005, kMaxRitardo) * sr_;
    };

    // Il deck viene sempre registrato negli ultimi secondi: lo STRETCH parte da "adesso"
    const auto cap = static_cast<int64_t>(catturaL_.size());
    for (int i = 0; i < n; ++i) {
        const auto k = static_cast<size_t>((catturati_ + i) % cap);
        catturaL_[k] = l[i];
        catturaR_[k] = r[i];
    }
    catturati_ += n;

    std::fill(wL_, wL_ + n, 0.0f);
    std::fill(wR_, wR_ + n, 0.0f);

    // --- ECHO, DUB, REVERB: si elaborano finché sono accesi o hanno ancora una coda
    for (int e = 0; e < 3; ++e) {
        const float bersaglio = (on && t == e) ? 1.0f : 0.0f;
        if (bersaglio > 0.0f) {
            vita_[e] = kVitaSecondi * static_cast<int>(sr_);
        } else if (vita_[e] <= 0) {
            continue;
        }
        float picco = 0.0f;
        float m = mandata_[e];
        float& wet = wet_[e];
        switch (e) {
            case kEcho: {
                const double ritardo = ritardoDi(kEcho);
                if (ritardoEco_ <= 0.0) ritardoEco_ = ritardo;
                const float fb = p1[kEcho] * 0.92f;
                for (int i = 0; i < n; ++i) {
                    m = verso(m, bersaglio, kRampa_);
                    wet = verso(wet, w[kEcho], kRampa_);
                    ritardoEco_ = ritardo + (ritardoEco_ - ritardo) * kTempo_;
                    const float dl = eco_.leggi(eco_.l, ritardoEco_);
                    const float dr = eco_.leggi(eco_.r, ritardoEco_);
                    // un leggero passa-basso nel giro: ogni ripetizione è un po' più scura
                    lpEcoL_ = verso(lpEcoL_, dl, coeffLpEco_);
                    lpEcoR_ = verso(lpEcoR_, dr, coeffLpEco_);
                    eco_.avanza(l[i] * m + fb * lpEcoL_, r[i] * m + fb * lpEcoR_);
                    wL_[i] += dl * wet;
                    wR_[i] += dr * wet;
                    picco = std::max(picco, std::fabs(dl) + std::fabs(dr));
                }
                break;
            }
            case kDub: {
                const double ritardo = ritardoDi(kDub);
                if (ritardoDub_ <= 0.0) ritardoDub_ = ritardo;
                // tono: da scuro (passa-basso 600 Hz) a brillante (19 kHz); i bassi profondi escono dal giro
                const float sr = static_cast<float>(sr_);
                for (int ch = 0; ch < 2; ++ch) {
                    dubLp_[ch].imposta(600.0f * std::pow(2.0f, 5.0f * p2[kDub]), 0.7f, sr);
                    dubHp_[ch].imposta(40.0f * std::pow(2.0f, 3.0f * p2[kDub]), 0.7f, sr);
                }
                const float fb = p1[kDub] * 1.1f;  // oltre 1 si auto-alimenta (saturato), come un dub vero
                for (int i = 0; i < n; ++i) {
                    m = verso(m, bersaglio, kRampa_);
                    wet = verso(wet, w[kDub], kRampa_);
                    ritardoDub_ = ritardo + (ritardoDub_ - ritardo) * kTempo_;
                    faseWow_ += 0.45 / sr_;
                    if (faseWow_ >= 1.0) faseWow_ -= 1.0;
                    const double wow = 1.0 + 0.0025 * std::sin(2.0 * 3.14159265358979 * faseWow_);  // nastro
                    const float dl = dub_.leggi(dub_.l, ritardoDub_ * wow);
                    const float dr = dub_.leggi(dub_.r, ritardoDub_ * wow * 1.013);
                    float lp, scarto, fl, fr;
                    dubLp_[0].processa(dl, lp, scarto);
                    dubHp_[0].processa(lp, scarto, fl);
                    dubLp_[1].processa(dr, lp, scarto);
                    dubHp_[1].processa(lp, scarto, fr);
                    const float in = 0.5f * (l[i] + r[i]) * m;
                    dub_.avanza(satura(in + fb * fl), satura(in + fb * fr));
                    wL_[i] += dl * wet;
                    wR_[i] += dr * wet;
                    picco = std::max(picco, std::fabs(dl) + std::fabs(dr));
                }
                break;
            }
            default: {  // REVERB
                stanza_ = 0.5f + p1[kReverb];
                const float feedback = kCoda[std::clamp(scelta[kReverb].load(std::memory_order_relaxed), 0, kNumCode - 1)];
                const float smorza = 0.6f * p2[kReverb];
                int lunghezze[2][8];
                for (int ch = 0; ch < 2; ++ch)
                    for (int k = 0; k < 8; ++k)
                        lunghezze[ch][k] = std::max(1, static_cast<int>(static_cast<float>(pettini_[ch][k].base) * stanza_));
                for (int i = 0; i < n; ++i) {
                    m = verso(m, bersaglio, kRampa_);
                    wet = verso(wet, w[kReverb], kRampa_);
                    const float in = (l[i] + r[i]) * 0.015f * m;
                    float out[2] = {0.0f, 0.0f};
                    for (int ch = 0; ch < 2; ++ch) {
                        for (int k = 0; k < 8; ++k) out[ch] += pettini_[ch][k].processa(in, lunghezze[ch][k], feedback, smorza);
                        for (auto& a : passaTutto_[ch]) out[ch] = a.processa(out[ch]);
                    }
                    wL_[i] += out[0] * 3.0f * wet;
                    wR_[i] += out[1] * 3.0f * wet;
                    picco = std::max(picco, std::fabs(out[0]) + std::fabs(out[1]));
                }
                break;
            }
        }
        mandata_[e] = m;
        if (bersaglio == 0.0f) {
            vita_[e] -= n;
            // coda finita (o troppo lunga): si svuota la memoria, così riaccendendo non torna l'eco vecchia
            if ((m < 1e-5f && picco < 1e-6f) || vita_[e] <= 0) {
                vita_[e] = 0;
                mandata_[e] = 0.0f;
                svuota(e);
            }
        }
    }

    // --- STRETCH granulare: grani a campana (si sovrappongono a metà) presi dagli ultimi secondi del deck.
    // Il punto da cui si prendono avanza più piano del tempo (½× ¼× ⅛×) o resta fermo (FREEZE): il tono resta.
    const bool vuoleStretch = on && t == kStretch;
    if (vuoleStretch && !stretchVivo_) {
        stretchVivo_ = true;
        lettura_ = static_cast<double>(catturati_ - n);  // dall'inizio di questo blocco
        for (auto& g : grani_) g.durata = 0;
        prossimoGrano_ = 0;
    }
    if (stretchVivo_) {
        const float rapporto = kRapporti[std::clamp(scelta[kStretch].load(std::memory_order_relaxed), 0, kNumRapporti - 1)];
        const int durata = std::max(16, static_cast<int>((0.010 + 0.110 * p1[kStretch]) * sr_)) & ~1;  // pari: i grani si danno il cambio esatto
        // TONO: centro = fisso, ai lati fino a ±12 semitoni
        const float semitoni = std::fabs(p2[kStretch] - 0.5f) < 0.03f ? 0.0f : (p2[kStretch] - 0.5f) * 24.0f;
        const double passo = std::pow(2.0, semitoni / 12.0);
        const float bersaglio = vuoleStretch ? 1.0f : 0.0f;
        // troppo indietro (stretch tenuto a lungo): si salta avanti, la memoria è a giro
        if (static_cast<double>(catturati_) - lettura_ > static_cast<double>(cap) * 0.8)
            lettura_ = static_cast<double>(catturati_) - static_cast<double>(cap) * 0.5;
        for (int i = 0; i < n; ++i) {
            if (--prossimoGrano_ <= 0) {
                prossimoGrano_ = durata / 2;
                auto libero = [](const Grano& x) { return x.durata == 0 || x.eta >= x.durata; };
                // il posto libero, altrimenti quello più vecchio (es. grana cambiata mentre suona)
                Grano& g = libero(grani_[0]) ? grani_[0] : libero(grani_[1]) ? grani_[1] : grani_[0].eta > grani_[1].eta ? grani_[0] : grani_[1];
                caso_ = caso_ * 1664525u + 1013904223u;
                const double sparpaglia = static_cast<double>(caso_ >> 8) / 16777216.0 * durata * (rapporto == 0.0f ? 1.0 : 0.25);
                // il grano finisce dove si trova la lettura: non legge mai suono non ancora arrivato
                g.pos = lettura_ - durata * passo - sparpaglia;
                g.passo = passo;
                g.eta = 0;
                g.durata = durata;
            }
            float sl = 0.0f, sr = 0.0f;
            for (auto& g : grani_) {
                if (g.durata == 0 || g.eta >= g.durata) continue;
                const float finestra = 0.5f - 0.5f * std::cos(2.0f * kPi * static_cast<float>(g.eta) / static_cast<float>(g.durata));
                // solo suono ancora in memoria (e mai prima dell'inizio, se l'effetto parte appena aperta l'app)
                const double p = std::max(g.pos, static_cast<double>(std::max<int64_t>(0, catturati_ - cap + 2)));
                const auto k0 = static_cast<int64_t>(std::floor(p));
                const float f = static_cast<float>(p - std::floor(p));
                const auto a = static_cast<size_t>(k0 % cap), b = static_cast<size_t>((k0 + 1) % cap);
                sl += finestra * (catturaL_[a] + (catturaL_[b] - catturaL_[a]) * f);
                sr += finestra * (catturaR_[a] + (catturaR_[b] - catturaR_[a]) * f);
                g.pos += g.passo;
                ++g.eta;
            }
            lettura_ += rapporto;
            // entra in fretta, esce in dissolvenza (niente stacco secco quando si alza il dito)
            pesoStretch_ = verso(pesoStretch_, bersaglio, bersaglio > pesoStretch_ ? kRampa_ : kCodaStretch_);
            wet_[kStretch] = verso(wet_[kStretch], w[kStretch], kRampa_);
            const float ps = pesoStretch_ * wet_[kStretch];
            // il suono del deck sfuma verso lo stretch (sommato alle code degli altri effetti)
            l[i] += (sl - l[i]) * ps;
            r[i] += (sr - r[i]) * ps;
        }
        if (!vuoleStretch && pesoStretch_ < 1e-4f) {
            stretchVivo_ = false;
            pesoStretch_ = 0.0f;
        }
    }

    for (int i = 0; i < n; ++i) {
        l[i] += wL_[i];
        r[i] += wR_[i];
    }
}

}  // namespace djaaay
