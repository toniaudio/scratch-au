#include "Timecode.h"

#include <algorithm>
#include <cmath>

namespace djaaay {

namespace {

constexpr float kDuePi = 6.28318530717959f;
constexpr int kBitValidi = 24;  // bit consecutivi giusti prima di fidarsi della posizione (come xwax)

// Parametri dei formati (da xwax): Serato CV02 lato A, Traktor Scratch lato A, MixVibes V2
const FormatoTimecode kFormati[3] = {
    {"Serato CV02", 1000, 20, 0x59017, 0x361e4, 712000, false, false, false},
    {"Traktor", 2000, 23, 0x134503, 0x041040, 1500000, true, true, true},
    {"MixVibes", 1300, 20, 0x22c90, 0x00008, 950000, false, false, true},
};

inline uint32_t parita(uint32_t x) {
    x ^= x >> 16;
    x ^= x >> 8;
    x ^= x >> 4;
    x ^= x >> 2;
    x ^= x >> 1;
    return x & 1u;
}

// Il codice "prima" di questo (disco che va indietro): il bit nuovo entra dal fondo.
inline uint32_t codicePrecedente(uint32_t c, const FormatoTimecode& f) {
    const uint32_t maschera = (1u << f.bit) - 1u;
    const uint32_t l = parita(c & ((f.prese >> 1) | (1u << (f.bit - 1))));
    return ((c << 1) & maschera) | l;
}

inline float avvolgi(float a) {
    while (a > kDuePi * 0.5f) a -= kDuePi;
    while (a < -kDuePi * 0.5f) a += kDuePi;
    return a;
}

}  // namespace

const FormatoTimecode& formatoTimecode(int tipo) { return kFormati[std::clamp(tipo, 0, 2)]; }

uint32_t codiceSuccessivo(uint32_t c, const FormatoTimecode& f) {
    const uint32_t l = parita(c & (f.prese | 1u));
    return (c >> 1) | (l << (f.bit - 1));
}

// Tabella codice → numero di ciclo, ordinata per codice (ricerca binaria, nessuna allocazione nel thread audio)
struct Timecode::Tabella {
    std::vector<uint64_t> voci;  // (codice << 32) | ciclo
    explicit Tabella(const FormatoTimecode& f) {
        voci.reserve(static_cast<size_t>(f.lunghezza));
        uint32_t c = f.seme;
        for (int i = 0; i < f.lunghezza; ++i) {
            voci.push_back((static_cast<uint64_t>(c) << 32) | static_cast<uint32_t>(i));
            c = codiceSuccessivo(c, f);
        }
        std::sort(voci.begin(), voci.end());
    }
    int cerca(uint32_t codice) const {
        const uint64_t chiave = static_cast<uint64_t>(codice) << 32;
        auto it = std::lower_bound(voci.begin(), voci.end(), chiave);
        if (it == voci.end() || (*it >> 32) != codice) return -1;
        return static_cast<int>(*it & 0xFFFFFFFFu);
    }
};

Timecode::Timecode() {
    for (auto& p : pronte_) p.store(nullptr);
    for (auto& v : scope_) v.store(0.0f);
}

Timecode::~Timecode() = default;

void Timecode::prepara(double sampleRate) {
    sr_ = sampleRate;
    const auto sr = static_cast<float>(sampleRate);
    kDc_ = std::exp(-kDuePi * 5.0f / sr);
    kOmega_ = 1.0f - std::exp(-1.0f / (0.0015f * sr));
    kAmp_ = 1.0f - std::exp(-1.0f / (0.03f * sr));
    impostaTipo(tipo_.load());
    azzeraStato();
}

void Timecode::impostaTipo(int tipo) {
    tipo = std::clamp(tipo, 0, 2);
    if (!tabelle_[tipo]) {
        tabelle_[tipo] = std::make_unique<Tabella>(kFormati[tipo]);
        pronte_[tipo].store(tabelle_[tipo].get(), std::memory_order_release);
    }
    tipo_.store(tipo);
}

void Timecode::azzeraStato() {
    dcP_ = dcS_ = 0.0f;
    fasePrima_ = 0.0f;
    omega1_ = omega2_ = 0.0f;
    ampRif_ = 0.02f;
    ampMedia_ = ampQuad_ = 0.0f;
    qualita_ = 0.0f;
    prim_ = sec_ = Incrocio{};
    livelloBit_ = 0.0f;
    codice_ = flusso_ = 0;
    validi_ = 0;
    valida_ = false;
    posizione_ = 0.0;
    senzaBit_ = 0;
    silenzio_ = ultimoSilenzio_ = static_cast<int64_t>(sr_);  // all'inizio la puntina "arriva"
}

void Timecode::copiaScope(float* xy) const {
    const int inizio = scopeIndice_.load(std::memory_order_relaxed);
    for (int i = 0; i < kPuntiScope; ++i) {
        const int k = (inizio + i) % kPuntiScope;
        xy[2 * i] = scope_[2 * k].load(std::memory_order_relaxed);
        xy[2 * i + 1] = scope_[2 * k + 1].load(std::memory_order_relaxed);
    }
}

void Timecode::processa(const float* l, const float* r, int n, float* velocita) {
    if (chiediAzzera.exchange(false)) {
        g_ = 1.0f;
        sinE_ = 0.0f;
        cosE_ = 1.0f;
        scambia_ = false;
        misura_ = 0;
        azzeraStato();
        calibratoLetto_.store(false);
        invertiLetto_.store(false);
    }
    if (chiediCalibra.exchange(false)) {
        misura_ = static_cast<int64_t>(sr_);
        sPP_ = sSS_ = sPS_ = sVel_ = 0.0;
    }
    const int t = tipo_.load(std::memory_order_relaxed);
    const FormatoTimecode& f = kFormati[t];
    const Tabella* tabella = pronte_[t].load(std::memory_order_acquire);
    const float guadagno = std::pow(10.0f, std::clamp(guadagnoDb.load(std::memory_order_relaxed), -12.0f, 24.0f) / 20.0f);
    const double giri = giri45.load(std::memory_order_relaxed) ? 1.35 : 1.0;
    const auto radPerVelocita = static_cast<float>(kDuePi * f.risoluzione * giri / sr_);  // radianti per campione a velocità 1
    const float sens = std::clamp(sensibilita.load(std::memory_order_relaxed), 0.0f, 1.0f);
    const bool primarioDestro = f.primarioDestro != scambia_;
    const uint32_t maschera = (1u << f.bit) - 1u;
    const float kRifSu = 1.0f - std::exp(-1.0f / (0.05f * static_cast<float>(sr_)));
    const float kRifGiu = 1.0f - std::exp(-1.0f / (3.0f * static_cast<float>(sr_)));
    const float kQualita = 1.0f - std::exp(-1.0f / (0.2f * static_cast<float>(sr_)));
    riacquisito_ = false;

    float picco = 0.0f;
    float vel = 0.0f;
    for (int i = 0; i < n; ++i) {
        const float a = l[i] * guadagno, b = r[i] * guadagno;
        picco = std::max(picco, std::max(std::fabs(a), std::fabs(b)));
        float p = primarioDestro ? b : a;
        float s = primarioDestro ? a : b;
        // via la componente continua
        dcP_ = p + (dcP_ - p) * kDc_;
        dcS_ = s + (dcS_ - s) * kDc_;
        p -= dcP_;
        s -= dcS_;
        const float sGrezzo = s;
        s = (s / g_ - p * sinE_) / cosE_;  // calibrazione: canali di pari ampiezza e a 90°

        // --- velocità: di quanto è girato l'angolo del segnale da un campione all'altro
        const float amp = std::sqrt(p * p + s * s);
        const float fase = std::atan2(s, p);
        float d = avvolgi(fase - fasePrima_);
        fasePrima_ = fase;
        const bool fermo = amp < ampRif_ * (0.03f + 0.3f * sens);
        if (fermo) {
            d = 0.0f;
            ++silenzio_;
        } else if (silenzio_ > 0) {
            ultimoSilenzio_ = silenzio_;
            silenzio_ = 0;
        }
        omega1_ += kOmega_ * (d - omega1_);
        omega2_ += kOmega_ * (omega1_ - omega2_);
        vel = omega2_ / radPerVelocita;
        if (f.faseInvertita) vel = -vel;
        if (velocita) velocita[i] = vel;

        // --- qualità: ampiezza stabile e vicina a quella di riferimento
        ampMedia_ += kAmp_ * (amp - ampMedia_);
        ampQuad_ += kAmp_ * (amp * amp - ampQuad_);
        const float varianza = std::max(0.0f, ampQuad_ - ampMedia_ * ampMedia_);
        const float instabile = ampMedia_ > 1e-6f ? std::sqrt(varianza) / ampMedia_ : 1.0f;
        const bool sembraTimecode = instabile < 0.3f && ampMedia_ > 1e-4f;
        // il riferimento segue il segnale vero (sale in fretta, scende piano), non il rumore a disco fermo
        if (sembraTimecode) ampRif_ += (ampMedia_ - ampRif_) * (ampMedia_ > ampRif_ ? kRifSu : kRifGiu);
        ampRif_ = std::max(ampRif_, 0.001f);
        const float q = std::clamp(ampMedia_ / ampRif_, 0.0f, 1.0f) * std::clamp(1.0f - 2.0f * instabile, 0.0f, 1.0f);
        qualita_ += kQualita * ((fermo ? 0.0f : q) - qualita_);

        // --- calibrazione (disco in play): ampiezze e angolo tra i canali
        if (misura_ > 0 && !fermo) {
            sPP_ += static_cast<double>(p) * p;
            sSS_ += static_cast<double>(sGrezzo) * sGrezzo;
            sPS_ += static_cast<double>(p) * sGrezzo;
            sVel_ += vel;
            if (--misura_ == 0 && sPP_ > 0.0 && sSS_ > 0.0) {
                g_ = static_cast<float>(std::sqrt(sSS_ / sPP_));
                sinE_ = static_cast<float>(std::clamp(sPS_ / (g_ * sPP_), -0.7, 0.7));
                cosE_ = std::sqrt(1.0f - sinE_ * sinE_);
                // il disco gira in avanti ma si legge all'indietro: canali scambiati
                if (sVel_ < 0.0) scambia_ = !scambia_;
                calibratoLetto_.store(true);
                invertiLetto_.store(scambia_);
            }
        }

        // --- bit della posizione: un bit per ciclo, letto dal primario quando il secondario passa per lo zero
        const float soglia = ampRif_ * 0.1f;
        prim_.processa(p, soglia);
        sec_.processa(s, soglia);
        if (prim_.scambiato || sec_.scambiato) {
            bool avanti = prim_.scambiato ? (prim_.positivo != sec_.positivo) : (prim_.positivo == sec_.positivo);
            if (f.faseInvertita) avanti = !avanti;
            if (avanti != avanti_) {
                avanti_ = avanti;
                validi_ = 0;
            }
        }
        ++senzaBit_;
        if (sec_.scambiato && prim_.positivo == !f.polaritaInvertita) {
            const float m = std::fabs(p);
            const uint32_t bit = m > livelloBit_ ? 1u : 0u;
            if (avanti_) {
                codice_ = codiceSuccessivo(codice_, f);
                flusso_ = (flusso_ >> 1) | (bit << (f.bit - 1));
            } else {
                codice_ = codicePrecedente(codice_, f);
                flusso_ = ((flusso_ << 1) & maschera) | bit;
            }
            if (codice_ == flusso_) {
                ++validi_;
            } else {
                codice_ = flusso_;
                validi_ = 0;
            }
            livelloBit_ += (m - livelloBit_) / 48.0f;
            senzaBit_ = 0;
            const int ciclo = (validi_ > kBitValidi && tabella != nullptr) ? tabella->cerca(flusso_) : -1;
            if (ciclo >= 0) {
                if (!valida_) {
                    riacquisito_ = true;
                    dopoSilenzio_ = ultimoSilenzio_ > static_cast<int64_t>(0.2 * sr_);
                    ultimoSilenzio_ = 0;
                }
                valida_ = true;
                // all'indietro il bit nuovo entra dal fondo: il codice letto finisce (bit − 1) cicli più avanti
                posizione_ = (avanti_ ? ciclo : ciclo - (f.bit - 1)) / (f.risoluzione * giri);
            } else if (validi_ == 0) {
                valida_ = false;
            }
        }
        // tra un bit e l'altro la posizione avanza con la velocità; senza bit per un secondo non è più sicura
        if (valida_) posizione_ += vel / sr_;
        if (senzaBit_ > static_cast<int64_t>(sr_)) valida_ = false;

        // --- scope (un punto ogni 4 campioni)
        if (++scopeConta_ >= 4) {
            scopeConta_ = 0;
            const int k = scopeIndice_.load(std::memory_order_relaxed);
            const float scala = 1.0f / (ampRif_ * 1.6f);
            scope_[2 * k].store(std::clamp(p * scala, -1.0f, 1.0f), std::memory_order_relaxed);
            scope_[2 * k + 1].store(std::clamp(s * scala, -1.0f, 1.0f), std::memory_order_relaxed);
            scopeIndice_.store((k + 1) % kPuntiScope, std::memory_order_relaxed);
        }
    }
    qualitaLetta_.store(qualita_, std::memory_order_relaxed);
    velocitaLetta_.store(vel, std::memory_order_relaxed);
    posizioneLetta_.store(valida_ ? posizione_ : -1.0, std::memory_order_relaxed);
    livello_.store(std::max(picco, livello_.load(std::memory_order_relaxed) * 0.9f), std::memory_order_relaxed);
}

}  // namespace djaaay
