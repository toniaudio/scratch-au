#include "Mixer.h"

#include <algorithm>
#include <cmath>

namespace djaaay {

Guadagni guadagniCrossfader(float x, int curva, bool inverti) {
    x = std::clamp(x, 0.0f, 1.0f);
    if (inverti) x = 1.0f - x;
    switch (curva) {
        case kCurvaLineare:
            return {1.0f - x, x};
        case kCurvaMorbida:
            return {std::cos(x * kPi * 0.5f), std::sin(x * kPi * 0.5f)};
        default:
            return {std::clamp((1.0f - x) / kZonaTaglio, 0.0f, 1.0f), std::clamp(x / kZonaTaglio, 0.0f, 1.0f)};
    }
}

float guadagnoEQ(float m) {
    m = std::clamp(m, 0.0f, 1.0f);
    if (m < 0.5f) {
        const float v = m / 0.5f;
        return v * v;
    }
    return 1.0f + (m - 0.5f) * 2.0f;
}

namespace {

float dbLineare(float db) { return std::pow(10.0f, db / 20.0f); }

// Passa da `da` ad `a` con uno smoothing per blocco; restituisce il nuovo valore.
float avvicina(float da, float a, float coeff) { return a + (da - a) * coeff; }

}  // namespace

void Mixer::prepara(double sampleRate) {
    sr_ = static_cast<float>(sampleRate);
    for (auto& s : st_) {
        for (int c = 0; c < 2; ++c) {
            s.xBassi[c].imposta(250.0f, sr_);
            s.xAlti[c].imposta(2500.0f, sr_);
            s.xBassi[c].azzera();
            s.xAlti[c].azzera();
            s.filtro[c].azzera();
        }
    }
    smooth_ = coeffSmooth(0.001f, sr_);       // crossfader: 1 ms, i tagli restano netti
    rilascio_ = 1.0f - coeffSmooth(0.15f, sr_);
}

void Mixer::canaleRender(int n, float* l, float* r, int frame) {
    Canale& c = canale[n];
    Stato& s = st_[n];
    // Le manopole si avvicinano al valore nuovo in ~10 ms (a blocchi), con rampa dentro il blocco
    const float k = std::exp(-static_cast<float>(frame) / (0.01f * sr_));

    const float gain0 = s.gain, alti0 = s.alti, medi0 = s.medi, bassi0 = s.bassi, vol0 = s.volume;
    s.gain = avvicina(s.gain, dbLineare(-12.0f + 24.0f * std::clamp(c.gain.load(), 0.0f, 1.0f)), k);
    s.alti = avvicina(s.alti, guadagnoEQ(c.alti.load()), k);
    s.medi = avvicina(s.medi, guadagnoEQ(c.medi.load()), k);
    s.bassi = avvicina(s.bassi, guadagnoEQ(c.bassi.load()), k);
    const float v = std::clamp(c.volume.load(), 0.0f, 1.0f);
    s.volume = avvicina(s.volume, v * v, k);

    // Filtro: a sinistra passa-basso (20 kHz → 60 Hz), a destra passa-alto (20 Hz → 8 kHz)
    const float f = std::clamp(c.filtro.load(), -1.0f, 1.0f);
    const float morta = 0.02f;
    const float quanto = std::max(0.0f, std::fabs(f) - morta) / (1.0f - morta);
    const bool passaAlto = f > 0.0f;
    const float fc = passaAlto ? 20.0f * std::pow(400.0f, quanto) : 20000.0f * std::pow(0.003f, quanto);
    const float q = 0.8f + 0.6f * quanto;
    for (auto& fl : s.filtro) fl.imposta(fc, q, sr_);
    const float mix0 = s.filtroMix;
    s.filtroMix = avvicina(s.filtroMix, quanto > 0.0f ? 1.0f : 0.0f, k);

    const float inv = 1.0f / static_cast<float>(frame);
    float picco = 0.0f;
    for (int i = 0; i < frame; ++i) {
        const float t = static_cast<float>(i + 1) * inv;
        const float g = gain0 + (s.gain - gain0) * t;
        const float ga = alti0 + (s.alti - alti0) * t;
        const float gm = medi0 + (s.medi - medi0) * t;
        const float gb = bassi0 + (s.bassi - bassi0) * t;
        const float gv = vol0 + (s.volume - vol0) * t;
        const float mx = mix0 + (s.filtroMix - mix0) * t;
        float* canali[2] = {l, r};
        for (int ch = 0; ch < 2; ++ch) {
            const float x = canali[ch][i] * g;
            const float b = s.xBassi[ch].bassi(x);
            const float a = s.xAlti[ch].alti(x);
            const float m = x - b - a;
            const float eq = b * gb + m * gm + a * ga;
            float lp, hp;
            s.filtro[ch].processa(eq, lp, hp);
            const float filtrato = passaAlto ? hp : lp;
            const float y = (eq + (filtrato - eq) * mx) * gv;
            picco = std::max(picco, std::fabs(y));
            canali[ch][i] = y;
        }
    }
    c.livello.store(picco, std::memory_order_relaxed);
}

void Mixer::render(float* aL, float* aR, float* bL, float* bR, float* uscita, int frame) {
    canaleRender(0, aL, aR, frame);
    canaleRender(1, bL, bR, frame);

    const Guadagni obiettivo = guadagniCrossfader(crossfader.load(), curva.load(), inverti.load());
    const float m = std::clamp(master.load(), 0.0f, 1.0f);
    const float kMaster = std::exp(-static_cast<float>(frame) / (0.01f * sr_));
    const float master0 = master_;
    master_ = avvicina(master_, m * m, kMaster);
    const float inv = 1.0f / static_cast<float>(frame);

    float pL = 0.0f, pR = 0.0f;
    const float soglia = 0.95f;
    for (int i = 0; i < frame; ++i) {
        st_[0].xf = obiettivo.a + (st_[0].xf - obiettivo.a) * smooth_;
        st_[1].xf = obiettivo.b + (st_[1].xf - obiettivo.b) * smooth_;
        const float gm = master0 + (master_ - master0) * static_cast<float>(i + 1) * inv;
        float l = (aL[i] * st_[0].xf + bL[i] * st_[1].xf) * gm;
        float r = (aR[i] * st_[0].xf + bR[i] * st_[1].xf) * gm;

        // Limitatore: attacco immediato, rilascio 150 ms, poi sicurezza a ±1
        const float picco = std::max(std::fabs(l), std::fabs(r));
        const float vorrei = picco > soglia ? soglia / picco : 1.0f;
        riduzione_ = std::min(vorrei, riduzione_ + (1.0f - riduzione_) * rilascio_);
        l = std::clamp(l * riduzione_, -1.0f, 1.0f);
        r = std::clamp(r * riduzione_, -1.0f, 1.0f);

        uscita[2 * i] = l;
        uscita[2 * i + 1] = r;
        pL = std::max(pL, std::fabs(l));
        pR = std::max(pR, std::fabs(r));
    }
    livelloL.store(pL, std::memory_order_relaxed);
    livelloR.store(pR, std::memory_order_relaxed);
}

}  // namespace djaaay
