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
            return {x >= 1.0f - kFondoTaglio ? 0.0f : 1.0f, x <= kFondoTaglio ? 0.0f : 1.0f};
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
            s.eq[c].imposta(250.0f, 2500.0f, sr_);
            s.eq[c].azzera();
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
    const float kVeloce = std::exp(-static_cast<float>(frame) / (0.002f * sr_));

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
    const bool vuoleAlto = f > 0.0f;
    float obiettivoMix;
    if (vuoleAlto != s.passaAlto && s.filtroMix > 0.001f) {
        // cambio di tipo con il filtro attivo (es. salto da MIDI): prima sfuma quello vecchio, senza click
        obiettivoMix = 0.0f;
    } else {
        s.passaAlto = vuoleAlto;  // stesso filtro: cambia solo l'uscita usata, a volume zero
        s.fc = vuoleAlto ? 20.0f * std::pow(400.0f, quanto) : 20000.0f * std::pow(0.003f, quanto);
        s.q = 0.8f + 0.6f * quanto;
        obiettivoMix = quanto > 0.0f ? 1.0f : 0.0f;
    }
    const bool passaAlto = s.passaAlto;
    for (auto& fl : s.filtro) fl.imposta(s.fc, s.q, sr_);
    const float mix0 = s.filtroMix;
    s.filtroMix = avvicina(s.filtroMix, obiettivoMix, obiettivoMix == 0.0f && quanto > 0.0f ? kVeloce : k);

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
            float b, m, a;
            s.eq[ch].separa(x, b, m, a);
            const float eq = b * gb + m * gm + a * ga;
            float lp, hp;
            s.filtro[ch].processa(eq, lp, hp);
            const float filtrato = passaAlto ? hp : lp;
            const float pre = eq + (filtrato - eq) * mx;
            if (i < kMaxCuffie) (ch == 0 ? preL_[n] : preR_[n])[i] = pre;  // prima del fader: per le cuffie
            const float y = pre * gv;
            picco = std::max(picco, std::fabs(y));
            canali[ch][i] = y;
        }
    }
    c.livello.store(picco, std::memory_order_relaxed);
}

void Mixer::render(float* aL, float* aR, float* bL, float* bR, float* uscita, int frame, float* cuffie) {
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
    if (cuffie == nullptr) return;
    if (frame > kMaxCuffie) {
        std::fill(cuffie, cuffie + 2 * frame, 0.0f);
        return;
    }

    // Pre-ascolto: canali scelti (prima del fader) + master, al volume delle cuffie
    const float kCuffie = std::exp(-1.0f / (0.005f * sr_));
    const float obiettivoA = canale[0].cuffia.load() ? 1.0f : 0.0f;
    const float obiettivoB = canale[1].cuffia.load() ? 1.0f : 0.0f;
    const float mix0 = mixCuffie_, vol0 = volumeCuffie_;
    mixCuffie_ = avvicina(mixCuffie_, std::clamp(mixCuffie.load(), 0.0f, 1.0f), kMaster);
    const float vc = std::clamp(volumeCuffie.load(), 0.0f, 1.0f);
    volumeCuffie_ = avvicina(volumeCuffie_, vc * vc, kMaster);
    for (int i = 0; i < frame; ++i) {
        st_[0].cuffia = obiettivoA + (st_[0].cuffia - obiettivoA) * kCuffie;
        st_[1].cuffia = obiettivoB + (st_[1].cuffia - obiettivoB) * kCuffie;
        const float t = static_cast<float>(i + 1) * inv;
        const float m = mix0 + (mixCuffie_ - mix0) * t;
        const float v = vol0 + (volumeCuffie_ - vol0) * t;
        const float cueL = preL_[0][i] * st_[0].cuffia + preL_[1][i] * st_[1].cuffia;
        const float cueR = preR_[0][i] * st_[0].cuffia + preR_[1][i] * st_[1].cuffia;
        cuffie[2 * i] = std::clamp((cueL * (1.0f - m) + uscita[2 * i] * m) * v, -1.0f, 1.0f);
        cuffie[2 * i + 1] = std::clamp((cueR * (1.0f - m) + uscita[2 * i + 1] * m) * v, -1.0f, 1.0f);
    }
}

}  // namespace djaaay
