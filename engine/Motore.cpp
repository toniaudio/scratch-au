#include "Motore.h"

#include <algorithm>

namespace djaaay {

void Motore::prepara(double sampleRate) {
    sr_ = sampleRate;
    for (auto& d : deck) d.prepara(sampleRate);
    mixer.prepara(sampleRate);
}

void Motore::render(float* uscita, int frame) {
    float* const inizio = uscita;
    const int totale = frame;
    while (frame > 0) {
        const int n = std::min(frame, kBlocco);
        deck[0].render(aL_, aR_, n);
        deck[1].render(bL_, bR_, n);
        mixer.render(aL_, aR_, bL_, bR_, uscita, n);
        uscita += 2 * n;
        frame -= n;
    }
    registratore.scrivi(inizio, totale);
}

}  // namespace djaaay
