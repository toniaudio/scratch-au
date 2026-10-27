#include "Registratore.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <unistd.h>

namespace djaaay {

namespace {

void scriviU32(FILE* f, uint32_t v) {
    const uint8_t b[4] = {static_cast<uint8_t>(v), static_cast<uint8_t>(v >> 8), static_cast<uint8_t>(v >> 16),
                          static_cast<uint8_t>(v >> 24)};
    std::fwrite(b, 1, 4, f);
}

void scriviU16(FILE* f, uint16_t v) {
    const uint8_t b[2] = {static_cast<uint8_t>(v), static_cast<uint8_t>(v >> 8)};
    std::fwrite(b, 1, 2, f);
}

// Intestazione WAV PCM 16 bit stereo; le dimensioni si completano alla fine.
void intestazione(FILE* f, int sampleRate, uint32_t byteDati) {
    std::fwrite("RIFF", 1, 4, f);
    scriviU32(f, 36 + byteDati);
    std::fwrite("WAVEfmt ", 1, 8, f);
    scriviU32(f, 16);
    scriviU16(f, 1);  // PCM
    scriviU16(f, 2);  // stereo
    scriviU32(f, static_cast<uint32_t>(sampleRate));
    scriviU32(f, static_cast<uint32_t>(sampleRate) * 4);
    scriviU16(f, 4);
    scriviU16(f, 16);
    std::fwrite("data", 1, 4, f);
    scriviU32(f, byteDati);
}

}  // namespace

Registratore::Registratore() : anello_(kFrameAnello * 2, 0.0f) {}

Registratore::~Registratore() { ferma(); }

bool Registratore::avvia(int fd, int sampleRate) {
    if (fd < 0) return false;
    FILE* f = fdopen(fd, "wb");
    if (f == nullptr) {
        close(fd);  // il file descriptor è nostro: va chiuso anche se non si registra
        return false;
    }
    if (!inizia(f, sampleRate)) {
        std::fclose(f);
        return false;
    }
    return true;
}

bool Registratore::avviaSuFile(const char* percorso, int sampleRate) {
    FILE* f = std::fopen(percorso, "wb");
    if (f == nullptr) return false;
    if (!inizia(f, sampleRate)) {
        std::fclose(f);
        return false;
    }
    return true;
}

bool Registratore::inizia(FILE* f, int sampleRate) {
    std::lock_guard<std::mutex> lock(controllo_);
    if (attiva_.load()) return false;
    file_ = f;
    sampleRate_ = sampleRate;
    intestazione(file_, sampleRate_, 0);
    frameNelFile_.store(0);
    persi_.store(0);
    // si riparte da un anello vuoto: quello che c'era prima non fa parte della registrazione
    letti_.store(scritti_.load(std::memory_order_acquire), std::memory_order_release);
    fine_.store(false);
    attiva_.store(true, std::memory_order_release);
    scrittore_ = std::thread([this] { lavora(); });
    return true;
}

void Registratore::ferma() {
    std::lock_guard<std::mutex> lock(controllo_);
    if (!attiva_.load()) return;
    attiva_.store(false, std::memory_order_release);  // il thread audio smette di scrivere
    fine_.store(true);
    if (scrittore_.joinable()) scrittore_.join();
    // completa le dimensioni nell'intestazione
    const uint64_t byteDati = frameNelFile_.load() * 4;
    const uint32_t limite = static_cast<uint32_t>(std::min<uint64_t>(byteDati, 0xFFFFFFFFull - 36));
    std::fflush(file_);
    if (std::fseek(file_, 0, SEEK_SET) == 0) intestazione(file_, sampleRate_, limite);
    std::fclose(file_);
    file_ = nullptr;
}

double Registratore::secondi() const {
    return static_cast<double>(frameNelFile_.load(std::memory_order_relaxed)) / sampleRate_;
}

void Registratore::scrivi(const float* stereo, int frame) {
    if (!attiva_.load(std::memory_order_acquire) || frame <= 0) return;
    const uint64_t w = scritti_.load(std::memory_order_relaxed);
    const uint64_t r = letti_.load(std::memory_order_acquire);
    const uint64_t libero = kFrameAnello - (w - r);
    const uint64_t n = std::min<uint64_t>(static_cast<uint64_t>(frame), libero);
    for (uint64_t i = 0; i < n; ++i) {
        const size_t k = static_cast<size_t>((w + i) & (kFrameAnello - 1)) * 2;
        anello_[k] = stereo[2 * i];
        anello_[k + 1] = stereo[2 * i + 1];
    }
    scritti_.store(w + n, std::memory_order_release);
    if (n < static_cast<uint64_t>(frame)) persi_.fetch_add(static_cast<uint64_t>(frame) - n, std::memory_order_relaxed);
}

size_t Registratore::svuota() {
    const uint64_t w = scritti_.load(std::memory_order_acquire);
    const uint64_t r = letti_.load(std::memory_order_relaxed);
    const size_t n = static_cast<size_t>(w - r);
    if (n == 0) return 0;
    pcm_.resize(n * 2);
    for (size_t i = 0; i < n; ++i) {
        const size_t k = static_cast<size_t>((r + i) & (kFrameAnello - 1)) * 2;
        for (int c = 0; c < 2; ++c) {
            const float v = std::clamp(anello_[k + c], -1.0f, 1.0f);
            pcm_[i * 2 + c] = static_cast<int16_t>(std::lround(v * 32767.0f));
        }
    }
    letti_.store(w, std::memory_order_release);
    std::fwrite(pcm_.data(), sizeof(int16_t), n * 2, file_);
    frameNelFile_.fetch_add(n, std::memory_order_relaxed);
    return n;
}

void Registratore::lavora() {
    while (!fine_.load()) {
        if (svuota() == 0) std::this_thread::sleep_for(std::chrono::milliseconds(20));
    }
    svuota();  // quello che il thread audio ha scritto prima dello stop
}

}  // namespace djaaay
