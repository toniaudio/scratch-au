// Un brano decodificato in memoria: PCM 16 bit stereo, alla frequenza del file, a blocchi.
// Si riempie fuori dal thread audio (un solo thread scrive) e si può già suonare mentre si riempie: il thread audio
// vede solo i frame già pubblicati (`frame()`), i blocchi non si spostano mai in memoria.
// Chi la usa (deck, campionatore, caricamento) tiene un riferimento: l'ultimo che la rilascia la libera
// (mai nel thread audio: i deck passano le tracce vecchie al thread dell'interfaccia).
#pragma once

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <memory>

namespace djaaay {

struct Traccia {
    static constexpr int kBit = 16;
    static constexpr int64_t kFrameBlocco = int64_t{1} << kBit;  // ~1,5 s a 44,1 kHz, 256 KB
    static constexpr size_t kMaxBlocchi = size_t{1} << 14;      // oltre 6 ore a 44,1 kHz

    double sampleRate = 44100.0;

    Traccia() : blocchi_(new int16_t*[kMaxBlocchi]()) {}
    ~Traccia() {
        for (size_t i = 0; i < kMaxBlocchi && blocchi_[i] != nullptr; ++i) delete[] blocchi_[i];
    }
    Traccia(const Traccia&) = delete;
    Traccia& operator=(const Traccia&) = delete;

    // Frame già pronti
    int64_t frame() const { return frame_.load(std::memory_order_acquire); }
    // Lunghezza del brano: mentre si decodifica è quella prevista (il deck non si ferma dove la decodifica è arrivata)
    int64_t lunghezza() const {
        const int64_t f = frame();
        return completa_.load(std::memory_order_acquire) ? f : std::max(f, attesi_.load(std::memory_order_relaxed));
    }
    double secondi() const { return static_cast<double>(lunghezza()) / sampleRate; }
    bool completa() const { return completa_.load(std::memory_order_acquire); }

    // Campione (−1…1) con silenzio fuori dai bordi (e dove la decodifica non è ancora arrivata)
    float campione(int64_t i, int canale) const {
        if (i < 0 || i >= frame()) return 0.0f;
        return static_cast<float>(blocchi_[static_cast<size_t>(i >> kBit)][(i & (kFrameBlocco - 1)) * 2 + canale]) *
               (1.0f / 32768.0f);
    }

    // Puntatore ai frame da `i` in poi (L R L R …) e quanti ce ne sono di seguito nello stesso blocco (0 = nessuno)
    const int16_t* pezzo(int64_t i, int64_t& quanti) const {
        const int64_t n = frame();
        if (i < 0 || i >= n) {
            quanti = 0;
            return nullptr;
        }
        const int64_t inBlocco = i & (kFrameBlocco - 1);
        quanti = std::min(n - i, kFrameBlocco - inBlocco);
        return blocchi_[static_cast<size_t>(i >> kBit)] + inBlocco * 2;
    }

    // Durata prevista (es. dichiarata dal file)
    void riserva(int64_t frame) { attesi_.store(std::max<int64_t>(frame, 0), std::memory_order_relaxed); }
    // Decodifica finita: la lunghezza diventa quella vera
    void concludi() { completa_.store(true, std::memory_order_release); }

    // Aggiunge frame da un buffer PCM 16 bit con `canali` canali (1 = mono, >2 = si tengono i primi due).
    void aggiungi(const int16_t* dati, int64_t frame, int canali) {
        if (frame <= 0 || canali <= 0) return;
        int64_t n = frame_.load(std::memory_order_relaxed);
        for (int64_t i = 0; i < frame;) {
            const auto b = static_cast<size_t>(n >> kBit);
            if (b >= kMaxBlocchi) break;  // oltre 6 ore: il resto si perde
            if (blocchi_[b] == nullptr) blocchi_[b] = new int16_t[kFrameBlocco * 2];
            const int64_t inBlocco = n & (kFrameBlocco - 1);
            const int64_t quanti = std::min(frame - i, kFrameBlocco - inBlocco);
            int16_t* dst = blocchi_[b] + inBlocco * 2;
            const int16_t* src = dati + i * canali;
            if (canali == 2) {
                std::copy(src, src + quanti * 2, dst);
            } else {
                for (int64_t k = 0; k < quanti; ++k) {
                    dst[2 * k] = src[k * canali];
                    dst[2 * k + 1] = canali > 1 ? src[k * canali + 1] : src[k * canali];
                }
            }
            i += quanti;
            n += quanti;
            frame_.store(n, std::memory_order_release);  // dopo i dati: chi legge li trova già scritti
        }
    }

    // Riferimenti: chi la crea ne ha uno
    void trattieni() { riferimenti_.fetch_add(1, std::memory_order_relaxed); }
    static void rilascia(Traccia* t) {
        if (t != nullptr && t->riferimenti_.fetch_sub(1, std::memory_order_acq_rel) == 1) delete t;
    }

 private:
    std::unique_ptr<int16_t*[]> blocchi_;
    std::atomic<int64_t> frame_{0};
    std::atomic<int64_t> attesi_{0};
    std::atomic<bool> completa_{false};
    std::atomic<int> riferimenti_{1};
};

}  // namespace djaaay
