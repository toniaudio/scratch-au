// Coda di messaggi tra thread: chi scrive (interfaccia, MIDI) prende un mutex tra sé, chi legge non prende lock.
// Se scrive un solo thread (es. quello audio) si usa inviaSenzaLock(). Capacità fissa, nessuna allocazione dopo la costruzione.
#pragma once

#include <array>
#include <atomic>
#include <cstddef>
#include <mutex>

namespace djaaay {

template <typename T, size_t N>
class Coda {
    static_assert((N & (N - 1)) == 0, "N deve essere una potenza di 2");

public:
    // Qualsiasi thread tranne quello audio. Falso se la coda è piena (il messaggio si perde).
    bool invia(const T& v) {
        std::lock_guard<std::mutex> lock(scrittori_);
        const size_t t = coda_.load(std::memory_order_relaxed);
        if (t - testa_.load(std::memory_order_acquire) >= N) return false;
        buf_[t & (N - 1)] = v;
        coda_.store(t + 1, std::memory_order_release);
        return true;
    }

    // Variante senza lock per quando scrive un solo thread (es. il thread audio verso l'interfaccia).
    bool inviaSenzaLock(const T& v) {
        const size_t t = coda_.load(std::memory_order_relaxed);
        if (t - testa_.load(std::memory_order_acquire) >= N) return false;
        buf_[t & (N - 1)] = v;
        coda_.store(t + 1, std::memory_order_release);
        return true;
    }

    // Solo il thread che legge.
    bool ricevi(T& v) {
        const size_t h = testa_.load(std::memory_order_relaxed);
        if (h == coda_.load(std::memory_order_acquire)) return false;
        v = buf_[h & (N - 1)];
        testa_.store(h + 1, std::memory_order_release);
        return true;
    }

private:
    std::array<T, N> buf_{};
    std::atomic<size_t> testa_{0};
    std::atomic<size_t> coda_{0};
    std::mutex scrittori_;
};

}  // namespace djaaay
