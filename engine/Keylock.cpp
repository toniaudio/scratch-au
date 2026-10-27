#include "Keylock.h"

#include <vector>

#include "terzi/signalsmith-stretch.h"

namespace djaaay {

struct Keylock::Dentro {
    signalsmith::stretch::SignalsmithStretch<float> stretch{12345};  // seme fisso: risultato ripetibile
};

Keylock::Keylock() : d_(new Dentro()) {}
Keylock::~Keylock() = default;

void Keylock::prepara(double sampleRate) {
    // Blocchi da 80 ms ogni 20 ms: buon compromesso tra qualità sui bassi e ritardo
    const int blocco = static_cast<int>(sampleRate * 0.08);
    const int passo = static_cast<int>(sampleRate * 0.02);
    // splitComputation: il calcolo di ogni blocco viene spalmato sui blocchi audio successivi, invece di arrivare
    // tutto insieme ogni 20 ms (picco che farebbe saltare il suono con buffer piccoli). Costa un passo di ritardo in più.
    d_->stretch.configure(2, blocco, passo, true);
    ritardo_ = d_->stretch.inputLatency() + d_->stretch.outputLatency();
    riscaldamento_ = ritardo_ + 2 * passo;
    // Un primo giro a vuoto, così tutta la memoria interna è già pronta prima che parta l'audio
    std::vector<float> zero(1024, 0.0f), uscita(1024);
    for (int i = 0; i < 4; ++i) processa(zero.data(), zero.data(), uscita.data(), uscita.data(), 1024, 1.0f);
    azzera();
}

void Keylock::azzera() { d_->stretch.reset(); }

void Keylock::processa(const float* inL, const float* inR, float* outL, float* outR, int frame, float trasposizione) {
    d_->stretch.setTransposeFactor(trasposizione);
    const float* ingressi[2] = {inL, inR};
    float* uscite[2] = {outL, outR};
    d_->stretch.process(ingressi, frame, uscite, frame);
}

}  // namespace djaaay
