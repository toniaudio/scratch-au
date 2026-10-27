// Filtri usati dal mixer e dall'analisi. Tutti senza allocazioni.
#pragma once

#include <algorithm>
#include <cmath>

namespace djaaay {

constexpr float kPi = 3.14159265358979f;

// Filtro a variabili di stato "TPT" (Simper): stabile anche se la frequenza cambia mentre suona.
class FiltroSVF {
public:
    void imposta(float frequenza, float q, float sampleRate) {
        frequenza = std::clamp(frequenza, 10.0f, sampleRate * 0.49f);
        g_ = std::tan(kPi * frequenza / sampleRate);
        k_ = 1.0f / q;
        a1_ = 1.0f / (1.0f + g_ * (g_ + k_));
        a2_ = g_ * a1_;
        a3_ = g_ * a2_;
    }
    void azzera() { ic1_ = ic2_ = 0.0f; }

    // Restituisce passa-basso e passa-alto dello stesso campione.
    void processa(float x, float& lp, float& hp) {
        const float v3 = x - ic2_;
        const float v1 = a1_ * ic1_ + a2_ * v3;
        const float v2 = ic2_ + a2_ * ic1_ + a3_ * v3;
        ic1_ = 2.0f * v1 - ic1_;
        ic2_ = 2.0f * v2 - ic2_;
        lp = v2;
        hp = x - k_ * v1 - v2;
    }

    // Passa-tutto: stesso volume a tutte le frequenze, cambia solo la fase.
    float passaTutto(float x) {
        const float v3 = x - ic2_;
        const float v1 = a1_ * ic1_ + a2_ * v3;
        const float v2 = ic2_ + a2_ * ic1_ + a3_ * v3;
        ic1_ = 2.0f * v1 - ic1_;
        ic2_ = 2.0f * v2 - ic2_;
        return x - 2.0f * k_ * v1;
    }

private:
    float g_ = 0.0f, k_ = 1.4142f, a1_ = 1.0f, a2_ = 0.0f, a3_ = 0.0f;
    float ic1_ = 0.0f, ic2_ = 0.0f;
};

// Linkwitz-Riley del 4° ordine (due Butterworth in cascata): passa-basso e passa-alto complementari.
class CrossoverLR4 {
public:
    void imposta(float frequenza, float sampleRate) {
        for (auto* f : {&lp1_, &lp2_, &hp1_, &hp2_}) f->imposta(frequenza, 0.70710678f, sampleRate);
    }
    void azzera() {
        for (auto* f : {&lp1_, &lp2_, &hp1_, &hp2_}) f->azzera();
    }
    void separa(float x, float& basso, float& alto) {
        float lp, hp, scarto;
        lp1_.processa(x, lp, scarto);
        lp2_.processa(lp, basso, scarto);
        hp1_.processa(x, scarto, hp);
        hp2_.processa(hp, scarto, alto);
    }

private:
    FiltroSVF lp1_, lp2_, hp1_, hp2_;
};

// Separatore a 3 bande da mixer DJ ("isolatore"): bassi | medi | alti con crossover LR4.
// Con i tre guadagni a 1 la somma ha lo stesso volume dell'ingresso a tutte le frequenze (cambia solo la fase),
// e mettendo una banda a 0 quella banda sparisce davvero (kill).
class Isolatore3 {
public:
    void imposta(float frequenzaBassi, float frequenzaAlti, float sampleRate) {
        x1_.imposta(frequenzaBassi, sampleRate);
        x2_.imposta(frequenzaAlti, sampleRate);
        compensa_.imposta(frequenzaAlti, 0.70710678f, sampleRate);
    }
    void azzera() {
        x1_.azzera();
        x2_.azzera();
        compensa_.azzera();
    }
    void separa(float x, float& bassi, float& medi, float& alti) {
        float b, resto;
        x1_.separa(x, b, resto);
        x2_.separa(resto, medi, alti);
        // i bassi passano dallo stesso passa-tutto che il crossover degli alti applica al resto
        bassi = compensa_.passaTutto(b);
    }

private:
    CrossoverLR4 x1_, x2_;
    FiltroSVF compensa_;
};

// Toglie la componente continua (es. disco fermo su un campione diverso da zero).
class BloccaDC {
public:
    float processa(float x) {
        const float y = x - x1_ + 0.9995f * y1_;
        x1_ = x;
        y1_ = y;
        return y;
    }

private:
    float x1_ = 0.0f, y1_ = 0.0f;
};

// Coefficiente di uno smoothing esponenziale con costante di tempo `secondi`.
inline float coeffSmooth(float secondi, float sampleRate) {
    if (secondi <= 0.0f) return 0.0f;
    return std::exp(-1.0f / (secondi * sampleRate));
}

}  // namespace djaaay
