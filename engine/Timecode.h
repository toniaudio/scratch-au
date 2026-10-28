// DVS: legge il segnale di un vinile timecode (Serato CV02, Traktor, MixVibes) che arriva dalla scheda audio.
//
// Il segnale sono due sinusoidi in quadratura (un canale "primario" e uno "secondario"): dall'angolo tra i due
// si ricava a ogni campione quanto e in che verso gira il disco (velocità); l'ampiezza del primario, letta una
// volta per ciclo, porta un bit di una sequenza pseudo-casuale (LFSR) da cui si ricava la posizione sul disco.
// I parametri dei formati vengono da xwax (Mark Hills, GPL), il programma DVS libero di riferimento.
#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <vector>

namespace djaaay {

enum TipoTimecode : int { kSerato = 0, kTraktor = 1, kMixVibes = 2 };

struct FormatoTimecode {
    const char* nome;
    int risoluzione;          // cicli al secondo a 33⅓ giri e velocità normale (= frequenza in Hz)
    int bit;                  // lunghezza del codice
    uint32_t seme, prese;     // LFSR
    int lunghezza;            // cicli codificati sul lato del disco
    bool primarioDestro;      // il canale primario è il destro
    bool polaritaInvertita;   // il bit si legge col primario negativo
    bool faseInvertita;       // il verso "avanti" è l'opposto
};
const FormatoTimecode& formatoTimecode(int tipo);

// Il bit successivo di un codice (avanti nel disco).
uint32_t codiceSuccessivo(uint32_t codice, const FormatoTimecode& f);

class Timecode {
public:
    Timecode();
    ~Timecode();

    void prepara(double sampleRate);  // fuori dal thread audio
    // Fuori dal thread audio: costruisce (una volta) la tabella delle posizioni del formato e lo sceglie.
    void impostaTipo(int tipo);

    std::atomic<bool> giri45{false};
    std::atomic<float> sensibilita{0.45f};  // a disco fermo: più alta = si ferma prima
    std::atomic<float> guadagnoDb{0.0f};    // livello d'ingresso (−12…+24 dB)
    std::atomic<bool> chiediCalibra{false}; // il thread audio misura i canali per ~1 s
    std::atomic<bool> chiediAzzera{false};

    // Thread audio: `n` campioni stereo; scrive in `velocita` (se non è nullptr) la velocità di ogni campione
    // (1 = normale, negativa = indietro, 0 = fermo).
    void processa(const float* l, const float* r, int n, float* velocita);

    // Dal thread audio, subito dopo processa():
    bool posizioneValida() const { return valida_; }
    double posizioneSecondi() const { return posizione_; }   // posizione sul disco (come brano)
    bool appenaRiacquisito() const { return riacquisito_; }  // la posizione si legge di nuovo
    bool dopoSilenzio() const { return dopoSilenzio_; }       // ...dopo almeno 0,2 s senza segnale (puntina appoggiata)

    // Letture per l'interfaccia (qualsiasi thread)
    float qualita() const { return qualitaLetta_.load(std::memory_order_relaxed); }      // 0…1
    float velocitaLetta() const { return velocitaLetta_.load(std::memory_order_relaxed); }
    double posizioneLetta() const { return posizioneLetta_.load(std::memory_order_relaxed); }  // < 0 = non nota
    float livello() const { return livello_.load(std::memory_order_relaxed); }           // picco d'ingresso
    bool calibrato() const { return calibratoLetto_.load(std::memory_order_relaxed); }
    bool canaliInvertiti() const { return invertiLetto_.load(std::memory_order_relaxed); }
    static constexpr int kPuntiScope = 256;
    // Ultimi punti (primario, secondario) normalizzati −1…1, per lo scope circolare.
    void copiaScope(float* xy) const;

private:
    struct Tabella;
    struct Incrocio {
        bool positivo = false, scambiato = false;
        void processa(float v, float soglia) {
            scambiato = false;
            if (positivo && v < -soglia) {
                positivo = false;
                scambiato = true;
            } else if (!positivo && v > soglia) {
                positivo = true;
                scambiato = true;
            }
        }
    };
    void azzeraStato();

    double sr_ = 48000.0;
    std::atomic<int> tipo_{kSerato};
    std::unique_ptr<Tabella> tabelle_[3];
    std::atomic<const Tabella*> pronte_[3];

    // filtri d'ingresso
    float dcP_ = 0.0f, dcS_ = 0.0f, kDc_ = 0.0f;
    float picco_ = 0.0f;
    // calibrazione: secondario corretto = (s/g − p·sinE)/cosE
    float g_ = 1.0f, sinE_ = 0.0f, cosE_ = 1.0f;
    bool scambia_ = false;
    int64_t misura_ = 0;  // campioni ancora da misurare (calibrazione in corso)
    double sPP_ = 0.0, sSS_ = 0.0, sPS_ = 0.0, sVel_ = 0.0;
    // velocità
    float fasePrima_ = 0.0f;
    float omega1_ = 0.0f, omega2_ = 0.0f, kOmega_ = 0.0f;
    float ampRif_ = 0.02f;
    float ampMedia_ = 0.0f, ampQuad_ = 0.0f, kAmp_ = 0.0f;
    float qualita_ = 0.0f;
    // bit e posizione
    Incrocio prim_, sec_;
    bool avanti_ = true;
    float livelloBit_ = 0.0f;
    uint32_t codice_ = 0, flusso_ = 0;
    int validi_ = 0;
    int64_t senzaBit_ = 0;
    int64_t silenzio_ = 0;      // campioni consecutivi a disco fermo (o puntina alzata)
    int64_t ultimoSilenzio_ = 0;
    bool valida_ = false, riacquisito_ = false, dopoSilenzio_ = false;
    double posizione_ = 0.0;

    std::atomic<float> qualitaLetta_{0.0f}, velocitaLetta_{0.0f}, livello_{0.0f};
    std::atomic<double> posizioneLetta_{-1.0};
    std::atomic<bool> calibratoLetto_{false}, invertiLetto_{false};
    std::atomic<float> scope_[2 * kPuntiScope];
    std::atomic<int> scopeIndice_{0};
    int scopeConta_ = 0;
};

}  // namespace djaaay
