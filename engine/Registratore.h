// Registrazione del mix (REC) in un file WAV 16 bit stereo.
//
// Il thread audio non deve mai aspettare il disco: scrive in un anello di memoria (circa 5 secondi) e un thread
// a parte lo svuota sul file. Se il disco fosse troppo lento per più di 5 secondi, i frame in eccesso si perdono
// e vengono contati (persi()), ma l'audio non si interrompe mai.
#pragma once

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <mutex>
#include <thread>
#include <vector>

namespace djaaay {

class Registratore {
public:
    Registratore();
    ~Registratore();

    // Fuori dal thread audio. Il Registratore diventa proprietario del file descriptor e lo chiude sempre
    // (a fine registrazione, o subito se non riesce a partire).
    bool avvia(int fd, int sampleRate);
    bool avviaSuFile(const char* percorso, int sampleRate);  // per i test e per Android < 10
    void ferma();  // svuota l'anello, completa l'intestazione WAV e chiude il file

    bool attiva() const { return attiva_.load(std::memory_order_acquire); }
    double secondi() const;
    uint64_t framePersi() const { return persi_.load(std::memory_order_relaxed); }

    // Thread audio: `frame` campioni stereo interleaved. Non blocca e non alloca.
    void scrivi(const float* stereo, int frame);

private:
    bool inizia(FILE* f, int sampleRate);
    void lavora();
    size_t svuota();

    static constexpr size_t kFrameAnello = 1u << 18;  // 262144 frame: ~5,5 s a 48 kHz
    std::vector<float> anello_;
    std::atomic<uint64_t> scritti_{0};  // frame scritti dal thread audio
    std::atomic<uint64_t> letti_{0};    // frame già passati al file
    std::atomic<uint64_t> persi_{0};
    std::atomic<bool> attiva_{false};
    std::atomic<bool> fine_{false};

    std::mutex controllo_;  // avvia/ferma da thread diversi (interfaccia, servizio)
    std::thread scrittore_;
    FILE* file_ = nullptr;
    int sampleRate_ = 48000;
    std::atomic<uint64_t> frameNelFile_{0};
    std::vector<int16_t> pcm_;  // buffer di conversione del thread scrittore
};

}  // namespace djaaay
