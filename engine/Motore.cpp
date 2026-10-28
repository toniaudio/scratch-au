#include "Motore.h"

#include <algorithm>

namespace djaaay {

void Motore::prepara(double sampleRate) {
    sr_ = sampleRate;
    for (auto& d : deck) d.prepara(sampleRate);
    for (auto& e : effetto) e.prepara(sampleRate);
    mixer.prepara(sampleRate);
    timecode.prepara(sampleRate);
}

void Motore::leggiTimecode(int n) {
    // Di solito arriva tanto audio quanto se ne suona; se l'ingresso è indietro se ne legge di più (fino al
    // doppio) per recuperare, e la velocità si distribuisce sui campioni di uscita.
    const int disponibili = ingresso.disponibili();
    const int k = ingresso.leggi(inL_, inR_, std::min(disponibili, disponibili > 2 * n ? 2 * n : n));
    if (k > 0) {
        timecode.processa(inL_, inR_, k, velIn_);
        for (int i = 0; i < n; ++i) velTc_[i] = velIn_[std::min(k - 1, i * k / n)];
        ultimaVel_ = velIn_[k - 1];
        senzaIngresso_ = 0;
    } else {
        // niente audio dalla scheda: per un attimo si tiene l'ultima velocità, poi il disco si ferma
        senzaIngresso_ += n;
        if (senzaIngresso_ > static_cast<int64_t>(0.05 * sr_)) ultimaVel_ = 0.0f;
        std::fill(velTc_, velTc_ + n, ultimaVel_);
    }
    if (k > 0 && dvsAssoluta.load(std::memory_order_relaxed) && timecode.posizioneValida()) {
        for (auto& d : deck)
            d.dvsPosizione(timecode.posizioneSecondi(), timecode.appenaRiacquisito(), timecode.dopoSilenzio(),
                           dvsProtezione.load(std::memory_order_relaxed));
    }
}

void Motore::render(float* uscita, int frame) {
    float* const inizio = uscita;
    const int totale = frame;
    while (frame > 0) {
        const int n = std::min(frame, kBlocco);
        const bool dvs = deck[0].dvs.load(std::memory_order_relaxed) || deck[1].dvs.load(std::memory_order_relaxed);
        // il timecode si legge anche senza deck in DVS: serve alla taratura (scope, qualità) nelle opzioni
        if (dvs || ingresso.disponibili() > 0) leggiTimecode(n);
        deck[0].render(aL_, aR_, n, dvs ? velTc_ : nullptr);
        deck[1].render(bL_, bR_, n, dvs ? velTc_ : nullptr);
        effetto[0].render(aL_, aR_, n);
        effetto[1].render(bL_, bR_, n);
        mixer.render(aL_, aR_, bL_, bR_, uscita, n);
        uscita += 2 * n;
        frame -= n;
    }
    registratore.scrivi(inizio, totale);
}

}  // namespace djaaay
