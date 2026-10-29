// Finché l'oggetto vive, i numeri piccolissimi ("denormali") valgono zero: nei silenzi e nelle code che si spengono
// i filtri ci finirebbero dentro, e su molti processori i calcoli diventano fino a 100 volte più lenti.
// Alla fine si ripristina lo stato di prima. Costa due istruzioni: si può usare anche nel thread audio.
#pragma once

#include <cstdint>

#if defined(__x86_64__) || defined(__i386__)
#include <xmmintrin.h>
#endif

namespace djaaay {

class SenzaDenormali {
public:
#if defined(__aarch64__)
    SenzaDenormali() {
        asm volatile("mrs %0, fpcr" : "=r"(prima_));
        const uint64_t nuovo = prima_ | (uint64_t{1} << 24);  // FZ
        asm volatile("msr fpcr, %0" : : "r"(nuovo));
    }
    ~SenzaDenormali() { asm volatile("msr fpcr, %0" : : "r"(prima_)); }

private:
    uint64_t prima_ = 0;
#elif defined(__x86_64__) || defined(__i386__)
    SenzaDenormali() : prima_(_mm_getcsr()) { _mm_setcsr(prima_ | 0x8040u); }  // FTZ + DAZ
    ~SenzaDenormali() { _mm_setcsr(prima_); }

private:
    unsigned prima_;
#else
    SenzaDenormali() = default;
#endif
    SenzaDenormali(const SenzaDenormali&) = delete;
    SenzaDenormali& operator=(const SenzaDenormali&) = delete;
};

}  // namespace djaaay
