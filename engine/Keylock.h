// Keylock: corregge il tono di un audio già accelerato/rallentato, così cambiando il pitch cambia solo la velocità.
// Usa Signalsmith Stretch (terzi/, licenza MIT). L'header pesante resta qui dentro, Deck vede solo questa classe.
#pragma once

#include <memory>

namespace djaaay {

class Keylock {
public:
    Keylock();
    ~Keylock();

    // Fuori dal thread audio: prepara (e alloca) tutto per la frequenza di uscita.
    void prepara(double sampleRate);
    // Ritardo introdotto, in campioni di uscita: chi usa il keylock legge il brano in anticipo di questo tanto.
    int ritardo() const { return ritardo_; }
    // Campioni da dare prima che l'uscita sia affidabile (dopo l'accensione o un salto).
    int riscaldamento() const { return riscaldamento_; }

    // Thread audio
    void azzera();
    // `trasposizione` = fattore di frequenza (es. 1/1,08 per riportare giù un brano accelerato dell'8%).
    void processa(const float* inL, const float* inR, float* outL, float* outR, int frame, float trasposizione);

private:
    struct Dentro;
    std::unique_ptr<Dentro> d_;
    int ritardo_ = 0;
    int riscaldamento_ = 0;
};

}  // namespace djaaay
