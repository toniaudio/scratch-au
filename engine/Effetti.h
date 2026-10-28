// Effetti per deck (uno per deck, prima del mixer): ECHO, DUB DELAY, REVERB, STRETCH.
// I tempi di eco seguono il BPM del brano (secondiBattito). Le code di eco e riverbero continuano anche dopo aver
// spento l'effetto o cambiato tipo, come sugli effetti dei mixer veri. Ogni tipo ricorda le sue regolazioni.
// Le regolazioni sono atomiche; render() gira nel thread audio e non alloca.
#pragma once

#include <atomic>
#include <cstdint>
#include <vector>

#include "Filtri.h"

namespace djaaay {

enum TipoEffetto : int { kEcho = 0, kDub = 1, kReverb = 2, kStretch = 3 };

// Riga in basso di ECHO e DUB DELAY: tempo in battiti
constexpr int kNumBattute = 7;
constexpr float kBattute[kNumBattute] = {0.125f, 0.25f, 0.5f, 0.75f, 1.0f, 2.0f, 4.0f};
// Riga in basso del REVERB: coda corta, media, lunga
constexpr int kNumCode = 3;
// Riga in basso dello STRETCH: ½× ¼× ⅛× e FREEZE (fermo)
constexpr int kNumRapporti = 4;
constexpr float kRapporti[kNumRapporti] = {0.5f, 0.25f, 0.125f, 0.0f};

class Effetto {
public:
    Effetto();

    // Fuori dal thread audio: alloca linee di ritardo e memoria dello stretch.
    void prepara(double sampleRate);

    std::atomic<bool> acceso{false};
    std::atomic<int> tipo{kEcho};
    // Per tipo (indice TipoEffetto):
    std::atomic<float> dryWet[4];
    std::atomic<float> param1[4];  // ECHO, DUB: feedback · REVERB: stanza · STRETCH: grana (10…120 ms)
    std::atomic<float> param2[4];  // DUB: tono · REVERB: damp · STRETCH: tono (centro = fisso, ±12 semitoni)
    std::atomic<int> scelta[4];    // riga in basso: ECHO/DUB indice in kBattute, REVERB coda, STRETCH rapporto
    std::atomic<float> secondiBattito{0.5f};  // durata di un battito (dal BPM e dal pitch del deck)

    // Thread audio: elabora sul posto un blocco stereo non interleaved.
    void render(float* l, float* r, int frame);

private:
    static constexpr int kPezzo = 256;
    void pezzo(float* l, float* r, int n);
    void svuota(int e);

    // Linea di ritardo stereo con lettura frazionaria
    struct Linea {
        std::vector<float> l, r;
        int scrivi = 0;
        void prepara(int campioni) {
            l.assign(static_cast<size_t>(campioni), 0.0f);
            r.assign(static_cast<size_t>(campioni), 0.0f);
            scrivi = 0;
        }
        void azzera() {
            std::fill(l.begin(), l.end(), 0.0f);
            std::fill(r.begin(), r.end(), 0.0f);
        }
        float leggi(const std::vector<float>& b, double ritardo) const;
        void avanza(float vl, float vr) {
            l[static_cast<size_t>(scrivi)] = vl;
            r[static_cast<size_t>(scrivi)] = vr;
            if (++scrivi >= static_cast<int>(l.size())) scrivi = 0;
        }
    };

    // Riverbero tipo "Freeverb" (Jezar, pubblico dominio): 8 filtri a pettine + 4 passa-tutto per canale.
    // La memoria è allocata per la stanza più grande; la STANZA sceglie quanta usarne.
    struct Pettine {
        std::vector<float> b;
        int base = 1;  // lunghezza a stanza "normale"
        int i = 0;
        float memoria = 0.0f;
        float processa(float x, int lunghezza, float feedback, float smorza) {
            if (i >= lunghezza) i = 0;
            const float y = b[static_cast<size_t>(i)];
            memoria = y * (1.0f - smorza) + memoria * smorza;
            b[static_cast<size_t>(i)] = x + memoria * feedback;
            ++i;
            return y;
        }
    };
    struct PassaTutto {
        std::vector<float> b;
        int i = 0;
        float processa(float x) {
            const float y = b[static_cast<size_t>(i)];
            b[static_cast<size_t>(i)] = x + y * 0.5f;
            if (++i >= static_cast<int>(b.size())) i = 0;
            return y - x;
        }
    };

    // Un grano dello STRETCH: legge un pezzetto del deck a velocità `passo` con una finestra a campana
    struct Grano {
        double pos = 0.0;
        double passo = 1.0;
        int eta = 0;
        int durata = 0;  // 0 = spento
    };

    double sr_ = 48000.0;
    float kRampa_ = 0.0f;   // smoothing per campione (~15 ms) di mandate e livelli
    float kTempo_ = 0.0f;   // smoothing del tempo di ritardo (~80 ms: cambiando tempo l'eco "scivola")

    // Mandate (quanto segnale entra nell'effetto), livelli e "vita" delle code
    float mandata_[3] = {0.0f, 0.0f, 0.0f};  // echo, dub, reverb
    float wet_[4] = {0.0f, 0.0f, 0.0f, 0.0f};
    int vita_[3] = {0, 0, 0};                 // campioni da elaborare ancora dopo che la mandata è a zero

    // ECHO
    Linea eco_;
    double ritardoEco_ = 0.0;
    float lpEcoL_ = 0.0f, lpEcoR_ = 0.0f, coeffLpEco_ = 0.0f;
    // DUB DELAY
    Linea dub_;
    double ritardoDub_ = 0.0;
    FiltroSVF dubLp_[2], dubHp_[2];
    double faseWow_ = 0.0;
    // REVERB
    Pettine pettini_[2][8];
    PassaTutto passaTutto_[2][4];
    float stanza_ = 1.0f;
    // STRETCH
    std::vector<float> catturaL_, catturaR_;  // ultimi secondi del deck
    int64_t catturati_ = 0;
    double lettura_ = 0.0;      // da dove partono i grani (avanza al rapporto scelto)
    Grano grani_[2];
    int prossimoGrano_ = 0;     // campioni al prossimo grano
    uint32_t caso_ = 12345;     // generatore pseudo-casuale per il FREEZE
    bool stretchVivo_ = false;
    float pesoStretch_ = 0.0f;
    float wL_[kPezzo], wR_[kPezzo];
};

}  // namespace djaaay
