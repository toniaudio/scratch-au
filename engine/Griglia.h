// Griglia delle battute di un brano, come la vede il motore (per il SYNC continuo). Fissa (periodo e prima battuta)
// o variabile (l'istante di ogni battuta vera, per i brani col tempo che cambia). "Battuta frazionaria": 0 = prima
// battuta, 1,5 = a metà tra la seconda e la terza. Si costruisce fuori dal thread audio e poi non cambia più.
#pragma once

#include <algorithm>
#include <cmath>
#include <vector>

namespace djaaay {

struct Griglia {
    double periodo = 0.5;          // secondi del brano per battuta (griglia fissa)
    double primo = 0.0;            // prima battuta (s)
    std::vector<double> battiti;   // se ha almeno 2 battute: griglia variabile

    bool variabile() const { return battiti.size() >= 2; }

    // In che battuta cade l'istante `s` (secondi del brano)
    double battutaA(double s) const {
        if (!variabile()) return (s - primo) / periodo;
        const auto& b = battiti;
        const size_t n = b.size();
        if (s < b[0]) return (s - b[0]) / (b[1] - b[0]);
        if (s >= b[n - 1]) return static_cast<double>(n - 1) + (s - b[n - 1]) / (b[n - 1] - b[n - 2]);
        const size_t i = static_cast<size_t>(std::upper_bound(b.begin(), b.end(), s) - b.begin()) - 1;
        return static_cast<double>(i) + (s - b[i]) / (b[i + 1] - b[i]);
    }

    // Istante (s) della battuta frazionaria `x`
    double tempoDi(double x) const {
        if (!variabile()) return primo + x * periodo;
        const auto& b = battiti;
        const size_t n = b.size();
        if (x <= 0.0) return b[0] + x * (b[1] - b[0]);
        if (x >= static_cast<double>(n - 1)) return b[n - 1] + (x - static_cast<double>(n - 1)) * (b[n - 1] - b[n - 2]);
        const auto i = static_cast<size_t>(x);
        return b[i] + (x - static_cast<double>(i)) * (b[i + 1] - b[i]);
    }

    // Durata media (s) di una battuta attorno all'istante `s`, su ±8 battute: il tempo "stabile" del brano lì
    // (le singole battute di un brano vero hanno qualche ms di imprecisione: non devono far ballare il SYNC)
    double durataMediaA(double s) const {
        if (!variabile()) return periodo;
        // 16 battute attorno a `s`, tenute dentro il brano (all'inizio e alla fine la finestra non esce)
        const double ultima = static_cast<double>(battiti.size() - 1);
        const double ampiezza = std::min(16.0, ultima);
        const double da = std::clamp(battutaA(s) - 0.5 * ampiezza, 0.0, ultima - ampiezza);
        return (tempoDi(da + ampiezza) - tempoDi(da)) / ampiezza;
    }

    // Durata (s) della battuta in corso all'istante `s`
    double durataA(double s) const {
        if (!variabile()) return periodo;
        const double i = std::floor(battutaA(s));
        return tempoDi(i + 1.0) - tempoDi(i);
    }
};

}  // namespace djaaay
