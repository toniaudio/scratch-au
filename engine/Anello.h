// Anello stereo senza lock tra un thread che scrive (ingresso della scheda audio) e uno che legge (thread audio).
#pragma once

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <vector>

namespace djaaay {

class AnelloStereo {
public:
    explicit AnelloStereo(int capacita = 16384) : l_(static_cast<size_t>(capacita)), r_(static_cast<size_t>(capacita)) {}

    // Chi scrive: `frame` campioni interleaved con `canali` canali; si prendono i canali `primo` e `primo` + 1.
    // Se l'anello è pieno i campioni in più si perdono (chi legge è in ritardo).
    int scrivi(const float* dati, int frame, int canali, int primo) {
        const int64_t s = scritti_.load(std::memory_order_relaxed);
        const int64_t liberi = static_cast<int64_t>(l_.size()) - (s - letti_.load(std::memory_order_acquire));
        const int n = static_cast<int>(std::min<int64_t>(frame, liberi));
        const auto cap = static_cast<int64_t>(l_.size());
        for (int i = 0; i < n; ++i) {
            const auto k = static_cast<size_t>((s + i) % cap);
            l_[k] = dati[i * canali + primo];
            r_[k] = canali > primo + 1 ? dati[i * canali + primo + 1] : dati[i * canali + primo];
        }
        scritti_.store(s + n, std::memory_order_release);
        return n;
    }

    // Chi legge
    int disponibili() const {
        return static_cast<int>(scritti_.load(std::memory_order_acquire) - letti_.load(std::memory_order_relaxed));
    }
    int leggi(float* l, float* r, int frame) {
        const int64_t g = letti_.load(std::memory_order_relaxed);
        const int n = std::min(frame, disponibili());
        const auto cap = static_cast<int64_t>(l_.size());
        for (int i = 0; i < n; ++i) {
            const auto k = static_cast<size_t>((g + i) % cap);
            l[i] = l_[k];
            r[i] = r_[k];
        }
        letti_.store(g + n, std::memory_order_release);
        return n;
    }

private:
    std::vector<float> l_, r_;
    std::atomic<int64_t> scritti_{0}, letti_{0};
};

}  // namespace djaaay
