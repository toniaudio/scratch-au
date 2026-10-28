// Il motore audio completo: due deck (o deck A + campionatore) + mixer. È l'unico oggetto che vede il thread audio.
#pragma once

#include <vector>

#include "Anello.h"
#include "Campionatore.h"
#include "Deck.h"
#include "Effetti.h"
#include "Mixer.h"
#include "Registratore.h"
#include "Timecode.h"

namespace djaaay {

class Motore {
public:
    static constexpr int kBlocco = 256;  // si lavora a pezzi di massimo 256 frame

    // Fuori dal thread audio (all'apertura del flusso o quando cambia il dispositivo).
    void prepara(double sampleRate);
    double sampleRate() const { return sr_; }

    Deck deck[2];
    Campionatore campionatore;  // One Deck: suona sul canale B (insieme al deck B, che di solito è fermo)
    Effetto effetto[2];  // FX di ogni deck, prima del mixer
    Mixer mixer;
    Registratore registratore;  // REC: riceve l'uscita del master

    // DVS: l'ingresso della scheda audio arriva nell'anello (dal thread d'ingresso), il timecode lo decodifica
    // e i deck con DVS acceso lo seguono.
    AnelloStereo ingresso{16384};
    Timecode timecode;
    std::atomic<bool> dvsAssoluta{false};
    std::atomic<bool> dvsProtezione{true};

    // Thread audio: `frame` campioni interleaved con `canali` canali. Con 4 o più canali (scheda audio) il master
    // va sui canali 1-2 e il pre-ascolto in cuffia sui 3-4; gli altri restano muti.
    void render(float* uscita, int frame, int canali = 2);

private:
    double sr_ = 48000.0;
    void leggiTimecode(int n);
    float aL_[kBlocco], aR_[kBlocco], bL_[kBlocco], bR_[kBlocco];
    float inL_[2 * kBlocco], inR_[2 * kBlocco], velIn_[2 * kBlocco], velTc_[kBlocco];
    float master_[2 * kBlocco], cuffie_[2 * kBlocco];
    float ultimaVel_ = 0.0f;
    int64_t senzaIngresso_ = 0;  // campioni di uscita senza audio dalla scheda
};

}  // namespace djaaay
