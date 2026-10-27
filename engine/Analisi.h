// Analisi di una traccia appena caricata (fuori dal thread audio): dati per disegnare la waveform.
#pragma once

#include <cstdint>
#include <vector>

#include "Traccia.h"

namespace djaaay {

// Waveform a colori: per ogni "colonna" (kFramePerColonna frame della traccia) il picco di bassi, medi e alti
// (0…255, scala radice quadrata per vedere bene anche i suoni deboli). Più livelli di zoom: ogni livello ha
// colonne larghe il doppio del precedente.
struct Waveform {
    static constexpr int kFramePerColonna = 64;
    struct Livello {
        std::vector<uint8_t> bassi, medi, alti;
        int framePerColonna = kFramePerColonna;
        size_t colonne() const { return bassi.size(); }
    };
    std::vector<Livello> livelli;
};

Waveform analizzaWaveform(const Traccia& t);

}  // namespace djaaay
