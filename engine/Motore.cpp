#include "Motore.h"

#include "SenzaDenormali.h"

#include <algorithm>
#include <cmath>
#include <cstddef>

namespace djaaay {

void Motore::prepara(double sampleRate) {
    sr_ = sampleRate;
    for (auto& d : deck) d.prepara(sampleRate);
    for (auto& e : effetto) e.prepara(sampleRate);
    campionatore.prepara(sampleRate);
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

// SYNC continuo, a ogni blocco: il deck col SYNC acceso va alle stesse battute al secondo dell'altro (anche quando il
// tempo dei brani cambia lungo il brano: si usa la griglia, fissa o variabile, dove si trova ognuno) e corregge la
// fase piano piano (in ~0,4 s, al massimo il 6% di velocità: non si sente). Appena partito (finito l'avvio), dopo
// un salto o all'aggancio la fase si allinea subito, con un salto (con dissolvenza). Mentre si tocca il disco, col DVS o con un
// loop più corto di una battuta (la fase salta a ogni giro) si tiene solo il tempo.
void Motore::sincronizza() {
    for (int d = 0; d < 2; ++d) {
        Deck& segue = deck[d];
        Deck& guida = deck[1 - d];
        // se il SYNC fosse acceso su tutti e due (non dovrebbe), il deck B segue l'A e non viceversa
        const bool attivo = segue.sincro.load(std::memory_order_relaxed) &&
                            !(d == 0 && guida.sincro.load(std::memory_order_relaxed));
        if (!attivo || !segue.haGriglia() || !guida.haGriglia()) {
            segue.impostaSincro(0.0);
            continue;
        }
        // battute al secondo della guida, a motore (anche se è ferma: chi segue parte già a tempo)
        const double battuteAlSecondo = guida.rapportoMotore() / guida.durataBattutaQui();
        double correzione = 0.0;
        if (guida.suona() && guida.aMotore() && segue.aMotore() && !guida.loopCorto() && !segue.loopCorto()) {
            double scarto = guida.battutaQui() - segue.battutaQui();
            scarto -= std::round(scarto);  // −0,5…+0,5 battute
            if (segue.chiedeAllineamento() && segue.aRegime() && guida.aRegime()) {
                if (std::fabs(scarto) > 0.005) segue.spostaDiBattute(scarto);
                segue.allineato();
                scarto = 0.0;
            }
            const double limite = 0.06 * battuteAlSecondo;
            correzione = std::clamp(scarto / 0.4, -limite, limite);
        }
        segue.impostaSincro((battuteAlSecondo + correzione) * segue.durataBattutaQui());
    }
}

void Motore::render(float* uscita, int frame, int canali) {
    SenzaDenormali senza;  // code di riverbero/eco che si spengono: niente calcoli lentissimi
    const bool scheda = canali >= 4;
    while (frame > 0) {
        const int n = std::min(frame, kBlocco);
        const bool dvs = deck[0].dvs.load(std::memory_order_relaxed) || deck[1].dvs.load(std::memory_order_relaxed);
        // il timecode si legge anche senza deck in DVS: serve alla taratura (scope, qualità) nelle opzioni
        if (dvs || ingresso.disponibili() > 0) leggiTimecode(n);
        sincronizza();
        deck[0].render(aL_, aR_, n, dvs ? velTc_ : nullptr);
        deck[1].render(bL_, bR_, n, dvs ? velTc_ : nullptr);
        campionatore.render(bL_, bR_, n);  // si somma al deck B (in One Deck il deck B è fermo)
        effetto[0].render(aL_, aR_, n);
        effetto[1].render(bL_, bR_, n);
        if (scheda) {
            mixer.render(aL_, aR_, bL_, bR_, master_, n, cuffie_);
            for (int i = 0; i < n; ++i) {
                float* f = uscita + static_cast<ptrdiff_t>(i) * canali;
                f[0] = master_[2 * i];
                f[1] = master_[2 * i + 1];
                f[2] = cuffie_[2 * i];
                f[3] = cuffie_[2 * i + 1];
                for (int ch = 4; ch < canali; ++ch) f[ch] = 0.0f;
            }
            registratore.scrivi(master_, n);
        } else {
            mixer.render(aL_, aR_, bL_, bR_, uscita, n);
            registratore.scrivi(uscita, n);
        }
        uscita += static_cast<ptrdiff_t>(canali) * n;
        frame -= n;
    }
}

}  // namespace djaaay
