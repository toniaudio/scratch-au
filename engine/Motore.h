// Il motore audio completo: due deck + mixer. È l'unico oggetto che vede il thread audio.
#pragma once

#include <vector>

#include "Deck.h"
#include "Effetti.h"
#include "Mixer.h"
#include "Registratore.h"

namespace djaaay {

class Motore {
public:
    static constexpr int kBlocco = 256;  // si lavora a pezzi di massimo 256 frame

    // Fuori dal thread audio (all'apertura del flusso o quando cambia il dispositivo).
    void prepara(double sampleRate);
    double sampleRate() const { return sr_; }

    Deck deck[2];
    Effetto effetto[2];  // FX di ogni deck, prima del mixer
    Mixer mixer;
    Registratore registratore;  // REC: riceve l'uscita del master

    // Thread audio: `frame` campioni stereo interleaved.
    void render(float* uscita, int frame);

private:
    double sr_ = 48000.0;
    float aL_[kBlocco], aR_[kBlocco], bL_[kBlocco], bR_[kBlocco];
};

}  // namespace djaaay
