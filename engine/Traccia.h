// Un brano decodificato in memoria: PCM 16 bit stereo interleaved, alla frequenza del file.
// Si costruisce fuori dal thread audio e poi non cambia più (il thread audio la legge e basta).
#pragma once

#include <cstdint>
#include <vector>

namespace djaaay {

struct Traccia {
    std::vector<int16_t> pcm;  // L R L R …
    double sampleRate = 44100.0;

    int64_t frame() const { return static_cast<int64_t>(pcm.size() / 2); }
    double secondi() const { return static_cast<double>(frame()) / sampleRate; }

    // Campione (−1…1) con silenzio fuori dai bordi: si può scratchare anche prima dell'inizio.
    float campione(int64_t i, int canale) const {
        if (i < 0 || i >= frame()) return 0.0f;
        return static_cast<float>(pcm[static_cast<size_t>(i) * 2 + canale]) * (1.0f / 32768.0f);
    }

    // Aggiunge frame da un buffer PCM 16 bit con `canali` canali (1 = mono, >2 = si tengono i primi due).
    void aggiungi(const int16_t* dati, int64_t frame, int canali) {
        pcm.reserve(pcm.size() + static_cast<size_t>(frame) * 2);
        for (int64_t i = 0; i < frame; ++i) {
            const int16_t l = dati[i * canali];
            const int16_t r = canali > 1 ? dati[i * canali + 1] : l;
            pcm.push_back(l);
            pcm.push_back(r);
        }
    }
};

}  // namespace djaaay
