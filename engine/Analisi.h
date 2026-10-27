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

// Tempo del brano: BPM e istante della prima battuta (in secondi). bpm = 0 se non si riesce a stimarlo
// (es. brano senza ritmo). Le battute successive cadono a primoBattito + k · 60 / bpm.
struct Tempo {
    double bpm = 0.0;
    double primoBattito = 0.0;
};

Tempo analizzaTempo(const Traccia& t);

// Salvataggio dell'analisi: la seconda volta che si carica un brano la waveform è pronta subito.
// Si salva solo il livello di dettaglio massimo, gli altri si ricostruiscono al caricamento (è rapidissimo).
// Nel file c'è anche il tempo, così BPM e griglia non vanno ricalcolati.
bool salvaWaveform(const Waveform& w, const Tempo& tempo, const char* percorso);
bool caricaWaveform(const char* percorso, Waveform& w, Tempo& tempo);

}  // namespace djaaay
