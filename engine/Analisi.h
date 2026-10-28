// Analisi di una traccia appena caricata (fuori dal thread audio): dati per disegnare la waveform.
#pragma once

#include <cstdint>
#include <vector>

#include "Filtri.h"
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

// Analisi a pezzi, mentre il brano si decodifica: aggiorna() dopo ogni pezzo aggiunto alla traccia (dallo stesso
// thread che la riempie), finisci() alla fine. Il risultato è identico a quello calcolato in un colpo solo.
class AnalisiProgressiva {
public:
    explicit AnalisiProgressiva(double sampleRate);
    void aggiorna(const Traccia& t);
    void finisci(const Traccia& t);
    Waveform waveform() const;  // fin dove è arrivata la decodifica
    Tempo tempo() const;        // dopo finisci()

private:
    void chiudiColonna();
    void chiudiFinestra();

    double sr_;
    int64_t fatti_ = 0;  // frame già analizzati
    // si analizza a metà frequenza (media di due frame): basta per waveform e attacchi, e costa la metà
    float meta_ = 0.0f;
    bool dispari_ = false;  // c'è un frame in attesa del suo compagno
    // waveform: colonna in corso
    Isolatore3 eqOnda_;
    Waveform::Livello base_;
    float picco_[3] = {0.0f, 0.0f, 0.0f};
    int inColonna_ = 0;
    // attacchi: finestra in corso
    Isolatore3 eqTempo_;
    int passo_;
    std::vector<float> attacchi_;  // curva grezza (una finestra ogni passo_ frame)
    double energia_[3] = {0.0, 0.0, 0.0};
    float prima_[3] = {0.0f, 0.0f, 0.0f};
    int inFinestra_ = 0;
};

// Salvataggio dell'analisi: la seconda volta che si carica un brano la waveform è pronta subito.
// Si salva solo il livello di dettaglio massimo, gli altri si ricostruiscono al caricamento (è rapidissimo).
// Nel file c'è anche il tempo, così BPM e griglia non vanno ricalcolati.
bool salvaWaveform(const Waveform& w, const Tempo& tempo, const char* percorso);
bool caricaWaveform(const char* percorso, Waveform& w, Tempo& tempo);

}  // namespace djaaay
