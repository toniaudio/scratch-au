#include "Deck.h"

#include <algorithm>
#include <cmath>

namespace djaaay {

namespace {

// Interpolazione di Hermite a 4 punti: suono pulito anche a velocità molto diverse da 1.
inline float hermite(float ym1, float y0, float y1, float y2, float t) {
    const float c1 = 0.5f * (y1 - ym1);
    const float c2 = ym1 - 2.5f * y0 + 2.0f * y1 - 0.5f * y2;
    const float c3 = 0.5f * (y2 - ym1) + 1.5f * (y0 - y1);
    return ((c3 * t + c2) * t + c1) * t + y0;
}

inline void leggi(const Traccia& t, double pos, float& l, float& r) {
    const double p = std::floor(pos);
    const int64_t k = static_cast<int64_t>(p);
    const float fr = static_cast<float>(pos - p);
    l = hermite(t.campione(k - 1, 0), t.campione(k, 0), t.campione(k + 1, 0), t.campione(k + 2, 0), fr);
    r = hermite(t.campione(k - 1, 1), t.campione(k, 1), t.campione(k + 1, 1), t.campione(k + 2, 1), fr);
}

// Lettura con anti-aliasing: oltre la velocità normale ogni campione di uscita "scavalca" più campioni del brano,
// e gli acuti si ripiegherebbero in fruscii metallici. Si fa la media dei punti scavalcati (filtro a finestra
// larga quanto il passo), fino a 8 punti. A velocità normale o più lenta è una lettura semplice.
constexpr int kMaxPunti = 8;
inline void leggiFiltrato(const Traccia& t, double pos, double passo, float& l, float& r) {
    const double ampiezza = std::fabs(passo);
    if (ampiezza <= 1.0) {
        leggi(t, pos, l, r);
        return;
    }
    const int n = std::min(kMaxPunti, static_cast<int>(std::ceil(ampiezza)));
    float sl = 0.0f, sr = 0.0f;
    for (int j = 0; j < n; ++j) {
        float pl, pr;
        leggi(t, pos + passo * ((j + 0.5) / n - 0.5), pl, pr);
        sl += pl;
        sr += pr;
    }
    l = sl / static_cast<float>(n);
    r = sr / static_cast<float>(n);
}

}  // namespace

Deck::Deck() = default;

Deck::~Deck() {
    Traccia::rilascia(traccia_);
    delete griglia_;
    ComandoDeck c;
    while (comandi_.ricevi(c)) {
        Traccia::rilascia(c.traccia);
        delete c.griglia;
    }
    raccogliTracceVecchie();
}

void Deck::prepara(double sampleRateUscita) {
    srUscita_ = sampleRateUscita;
    base_ = traccia_ ? traccia_->sampleRate / srUscita_ : 1.0;
    segui_ = std::exp(-1.0 / (0.002 * srUscita_));
    ritardoTocco_ = 0.006 * srUscita_;
    keylock_.prepara(srUscita_);
    ritardoKey_ = keylock_.ritardo();
    for (auto* v : {&kInL_, &kInR_, &kOutL_, &kOutR_, &kPeso_}) v->assign(kMaxBlocco, 0.0f);
    coeffPesoKey_ = static_cast<float>(std::exp(-1.0 / (0.01 * srUscita_)));
    keylockAcceso_ = false;
    pesoKey_ = 0.0f;
}

void Deck::carica(Traccia* t) {
    ComandoDeck c;
    c.tipo = ComandoDeck::Carica;
    c.traccia = t;
    if (!invia(c)) Traccia::rilascia(t);
}

void Deck::impostaGriglia(Griglia* g) {
    ComandoDeck c;
    c.tipo = ComandoDeck::Griglia;
    c.griglia = g;
    if (!invia(c)) delete g;
}

void Deck::raccogliTracceVecchie() {
    Traccia* t = nullptr;
    while (daLiberare_.ricevi(t)) Traccia::rilascia(t);
    Griglia* g = nullptr;
    while (grigliaDaLiberare_.ricevi(g)) delete g;
}

// ---------------------------------------------------------------------------------------------------------------
// SYNC (thread audio)

double Deck::battutaQui() const { return griglia_->battutaA(pos_ / traccia_->sampleRate); }

double Deck::durataBattutaQui() const { return griglia_->durataA(pos_ / traccia_->sampleRate); }

double Deck::durataMediaQui() const { return griglia_->durataMediaA(pos_ / traccia_->sampleRate); }

double Deck::rapportoBase() const {
    return rapportoBase_ > 0.0 ? rapportoBase_ : 1.0 + static_cast<double>(pitch.load(std::memory_order_relaxed));
}

double Deck::rapportoMotore() const {
    return rapportoSincro_ > 0.0 ? rapportoSincro_ : 1.0 + static_cast<double>(pitch.load(std::memory_order_relaxed));
}

bool Deck::loopCorto() const {
    if (!loop_ || griglia_ == nullptr || traccia_ == nullptr) return false;
    const double sr = traccia_->sampleRate;
    return griglia_->battutaA(loopFine_ / sr) - griglia_->battutaA(loopInizio_ / sr) < 0.95;
}

void Deck::spostaDiBattute(double battute) {
    const double sr = traccia_->sampleRate;
    const double qui = griglia_->battutaA(pos_ / sr);
    salta(griglia_->tempoDi(qui + battute) * sr);
}

// Colpo vero vicino: solo il corpo di kick e snare (sotto ~250 Hz: charleston e piatti non contano), energia a
// finestre di 1 ms; l'attacco è dove l'energia salta rispetto ai 6 ms prima (almeno 4 volte), preferendo i salti più
// grandi e più vicini alla griglia. Si torna un po' prima dell'attacco (~1 ms): il loop riparte con tutto il colpo.
double Deck::attaccoVicino(double pos) const {
    if (traccia_ == nullptr) return -1.0;
    const Traccia& t = *traccia_;
    const int finestra = std::max(8, static_cast<int>(0.001 * t.sampleRate));
    constexpr int kRaggio = 15, kPrima = 6, kAssesta = 20;  // ±15 ms; 20 ms per far assestare il filtro
    constexpr int kTotale = 2 * kRaggio + kPrima + 1;
    double e[kTotale];
    const auto base = static_cast<int64_t>(pos) - static_cast<int64_t>(kRaggio + kPrima) * finestra;
    // passa-basso a due poli (~250 Hz)
    const float k = 1.0f - std::exp(-2.0f * 3.14159265f * 250.0f / static_cast<float>(t.sampleRate));
    float p1 = 0.0f, p2 = 0.0f;
    for (int64_t j = base - static_cast<int64_t>(kAssesta) * finestra; j < base; ++j) {
        p1 += k * (0.5f * (t.campione(j, 0) + t.campione(j, 1)) - p1);
        p2 += k * (p1 - p2);
    }
    double massimo = 0.0;
    for (int f = 0; f < kTotale; ++f) {
        double somma = 0.0;
        for (int i = 0; i < finestra; ++i) {
            const int64_t j = base + static_cast<int64_t>(f) * finestra + i;
            p1 += k * (0.5f * (t.campione(j, 0) + t.campione(j, 1)) - p1);
            p2 += k * (p1 - p2);
            somma += static_cast<double>(p2) * p2;
        }
        e[f] = somma / finestra;
        massimo = std::max(massimo, e[f]);
    }
    if (massimo < 1e-7) return -1.0;  // niente kick né snare qui
    double meglio = 0.0;
    int scelto = -1;
    for (int f = kPrima; f < kTotale; ++f) {
        double prima = 0.0;
        for (int j = f - kPrima; j < f; ++j) prima += e[j];
        prima /= kPrima;
        const double salto = e[f] / (prima + 1e-4 * massimo);
        if (salto < 4.0 || e[f] < 0.05 * massimo) continue;
        const double distanza = std::fabs(static_cast<double>(f - kPrima - kRaggio)) / kRaggio;
        const double punti = salto * (1.0 - 0.5 * distanza);
        if (punti > meglio) {
            meglio = punti;
            scelto = f;
        }
    }
    if (scelto < 0) return -1.0;
    // il salto più netto può arrivare un paio di ms dopo l'inizio del colpo (il corpo del kick cresce, il passa-basso
    // ritarda): si torna indietro finché l'energia sale ancora (sopra il 5% del salto), poi una finestra in più
    int inizio = scelto;
    while (inizio > 1 && e[inizio - 1] < e[inizio] && e[inizio - 1] > 0.05 * e[scelto]) --inizio;
    return static_cast<double>(base) + static_cast<double>(inizio - 1) * finestra;
}

void Deck::salta(double nuovaPos, bool perKeylock) {
    if (nuovaPos == pos_) return;
    if (perKeylock) attesaKey_ = keylock_.riscaldamento();
    const double delta = nuovaPos - pos_;
    posVecchia_ = pos_;
    sfumaRestanti_ = kSfuma;
    pos_ = nuovaPos;
    // se il dito è sul disco, il suo riferimento si sposta insieme alla puntina
    toccoAncora_ += delta;
    toccoBersaglio_ += delta;
    toccoUltimo_ += delta;
}

void Deck::applica(const ComandoDeck& c) {
    const double sr = traccia_ ? traccia_->sampleRate : srUscita_;
    switch (c.tipo) {
        case ComandoDeck::Carica:
            if (traccia_ && !daLiberare_.inviaSenzaLock(traccia_)) {
                // coda piena: meglio perdere memoria che liberarla nel thread audio
            }
            traccia_ = c.traccia;
            base_ = traccia_ ? traccia_->sampleRate / srUscita_ : 1.0;
            // la griglia era del brano vecchio: arriva quella nuova
            if (griglia_ && !grigliaDaLiberare_.inviaSenzaLock(griglia_)) {
            }
            griglia_ = nullptr;
            pos_ = 0.0;
            vel_ = 0.0;
            motore_ = anteprimaCue_ = scivola_ = tocco_ = loop_ = false;
            cue_ = 0.0;
            sfumaRestanti_ = 0;
            break;
        case ComandoDeck::Play:
            anteprimaCue_ = false;
            if (c.a > 0.5 && !motore_) allinea_ = true;
            motore_ = c.a > 0.5;
            break;
        case ComandoDeck::PlayPausa:
            if (anteprimaCue_) {
                // Play mentre si tiene CUE: si continua a suonare anche dopo aver lasciato CUE
                anteprimaCue_ = false;
                motore_ = true;
            } else {
                motore_ = !motore_;
                if (motore_) allinea_ = true;
            }
            break;
        case ComandoDeck::CueGiu:
            if (motore_ && !anteprimaCue_) {
                // in play: torna al cue e si ferma
                salta(cue_);
                motore_ = false;
                vel_ = 0.0;
                scivola_ = false;
            } else if (!motore_) {
                // da fermo: qui c'è il nuovo cue; tenendo premuto si ascolta da qui
                cue_ = pos_;
                anteprimaCue_ = true;
                motore_ = true;
                scivola_ = false;
                vel_ = base_ * rapportoMotore();
            }
            break;
        case ComandoDeck::CueSu:
            if (anteprimaCue_) {
                anteprimaCue_ = false;
                motore_ = false;
                vel_ = 0.0;
                salta(cue_);
            }
            break;
        case ComandoDeck::Vai:
            salta(c.a * sr);
            allinea_ = true;  // col SYNC si rimette subito in fase
            break;
        case ComandoDeck::ToccoInizio:
            tocco_ = true;
            scivola_ = false;
            toccoAncora_ = toccoBersaglio_ = toccoUltimo_ = pos_;
            toccoVelBersaglio_ = 0.0;
            campioniDaUltimoTocco_ = 0.0;
            intervalloTocco_ = 0.0;
            break;
        case ComandoDeck::ToccoSposta:
            if (tocco_) {
                // Il bersaglio va dall'ultima posizione raggiunta a quella nuova del dito nel tempo tipico tra due
                // tocchi: movimento fluido, e quando il dito si ferma il disco si ferma lì (niente "sbandate").
                const double nuovo = toccoAncora_ + c.a * sr;
                const double tra = std::min(campioniDaUltimoTocco_, 0.03 * srUscita_);  // pause lunghe non contano
                if (tra > 0.0) intervalloTocco_ = intervalloTocco_ > 0.0 ? 0.7 * intervalloTocco_ + 0.3 * tra : tra;
                const double durata = intervalloTocco_ > 0.0 ? intervalloTocco_ : 0.008 * srUscita_;
                toccoUltimo_ = nuovo;
                toccoVelBersaglio_ = std::clamp((nuovo - toccoBersaglio_) / durata, -40.0 * base_, 40.0 * base_);
                campioniDaUltimoTocco_ = 0.0;
            }
            break;
        case ComandoDeck::ToccoFine:
            if (tocco_) {
                tocco_ = false;
                scivola_ = true;
            }
            break;
        case ComandoDeck::ImpostaCue:
            cue_ = c.a * sr;
            break;
        case ComandoDeck::Loop:
            // il keylock ha già letto in anticipo oltre la fine del loop nuovo: riparte
            attesaKey_ = keylock_.riscaldamento();
            loop_ = c.b > c.a;
            loopInizio_ = c.a * sr;
            loopFine_ = c.b * sr;
            if (loop_ && c.aggancia) {
                // inizio e fine sui colpi veri vicini alla griglia: il loop riparte col colpo intero e non prende
                // l'inizio di quello dopo. Se un capo non ha un colpo netto vicino si sposta come l'altro.
                const double a = attaccoVicino(loopInizio_), b = attaccoVicino(loopFine_);
                const double da = a >= 0.0 ? a - loopInizio_ : 0.0, db = b >= 0.0 ? b - loopFine_ : da;
                const double nuovoInizio = loopInizio_ + (a >= 0.0 ? da : db);
                const double nuovaFine = loopFine_ + db;
                if (nuovaFine - nuovoInizio > 0.5 * (loopFine_ - loopInizio_)) {
                    loopInizio_ = nuovoInizio;
                    loopFine_ = nuovaFine;
                }
            }
            loopInizioLetto_.store(loop_ ? loopInizio_ / sr : 0.0, std::memory_order_relaxed);
            loopFineLetto_.store(loop_ ? loopFine_ / sr : 0.0, std::memory_order_relaxed);
            // loop accorciato (frecce) mentre si è già oltre la nuova fine: si rientra nel loop allo stesso punto della battuta
            if (loop_ && pos_ >= loopFine_ && pos_ >= loopInizio_) {
                const double lunghezza = loopFine_ - loopInizio_;
                salta(loopInizio_ + std::fmod(pos_ - loopInizio_, lunghezza));
            }
            break;
        case ComandoDeck::Griglia:
            if (griglia_ && !grigliaDaLiberare_.inviaSenzaLock(griglia_)) {
                // coda piena: meglio perdere memoria che liberarla nel thread audio
            }
            griglia_ = c.griglia;
            break;
    }
}

void Deck::render(float* l, float* r, int frame, const float* velocitaTimecode) {
    ComandoDeck c;
    while (comandi_.ricevi(c)) applica(c);
    const float* tc = dvs.load(std::memory_order_relaxed) ? velocitaTimecode : nullptr;
    for (int fatti = 0; fatti < frame; fatti += kMaxBlocco)
        renderBlocco(l + fatti, r + fatti, std::min(kMaxBlocco, frame - fatti), tc ? tc + fatti : nullptr);
}

void Deck::dvsPosizione(double secondi, bool riacquisito, bool dopoSilenzio, bool protezione) {
    if (traccia_ == nullptr || !dvs.load(std::memory_order_relaxed)) return;
    const double sr = traccia_->sampleRate;
    const double nuova = secondi * sr;
    const double diff = std::fabs(nuova - pos_);
    // puntina appoggiata: si va dove dice il vinile; con la protezione i salti mentre suona si ignorano
    const bool salto = (riacquisito && (dopoSilenzio || !protezione)) || (!protezione && diff > 0.25 * sr);
    if (salto && diff > 0.02 * sr) salta(nuova);
}

void Deck::renderBlocco(float* l, float* r, int frame, const float* timecode) {
    if (traccia_ == nullptr) {
        std::fill(l, l + frame, 0.0f);
        std::fill(r, r + frame, 0.0f);
        velocitaLetta_.store(0.0f, std::memory_order_relaxed);
        return;
    }

    const Traccia& t = *traccia_;
    const double fine = static_cast<double>(t.lunghezza());
    // SYNC appena acceso: si allinea subito la fase
    const bool sincroOra = sincro.load(std::memory_order_relaxed);
    if (sincroOra && !sincroPrima_) allinea_ = true;
    sincroPrima_ = sincroOra;
    if (!sincroOra) rapportoSincro_ = rapportoBase_ = 0.0;
    const double velNormale = base_ * rapportoMotore();
    pitchEffettivo_.store(static_cast<float>(rapportoBase() - 1.0), std::memory_order_relaxed);

    // Frizione 0 → costante di tempo 0,6 s (scivola), 1 → 20 ms (riparte subito)
    const double f = std::clamp(static_cast<double>(frizione.load(std::memory_order_relaxed)), 0.0, 1.0);
    const double tauFrizione = 0.6 * std::pow(0.02 / 0.6, f);
    const double coeffFrizione = std::exp(-1.0 / (tauFrizione * srUscita_));
    const double coeffPitch = std::exp(-1.0 / (0.03 * srUscita_));
    const double avvio = avvioSecondi.load(std::memory_order_relaxed);
    const double frenata = frenataSecondi.load(std::memory_order_relaxed);
    const double passoAvvio = avvio > 0.0 ? base_ / (avvio * srUscita_) : 1e9;
    const double passoFrenata = frenata > 0.0 ? base_ / (frenata * srUscita_) : 1e9;
    const double soglia = 0.03 * base_;  // sotto questa velocità il suono sfuma (disco quasi fermo)

    const bool vuoleKey = keylock.load(std::memory_order_relaxed);
    if (vuoleKey != keylockAcceso_) {
        keylockAcceso_ = vuoleKey;
        if (vuoleKey) {
            keylock_.azzera();
            caldoKey_ = 0;
        }
    }

    for (int i = 0; i < frame; ++i) {
        // --- velocità del disco (il motore può spegnersi a metà blocco, a fine brano)
        const double velMotore = motore_ ? velNormale : 0.0;
        if (timecode) {
            // DVS: il disco va come il vinile (il pitch del deck non conta: lo dà il giradischi)
            vel_ = static_cast<double>(timecode[i]) * base_;
        } else if (tocco_) {
            campioniDaUltimoTocco_ += 1.0;
            if (toccoVelBersaglio_ != 0.0) {
                toccoBersaglio_ += toccoVelBersaglio_;
                // arrivato alla posizione del dito: si ferma lì
                if ((toccoVelBersaglio_ > 0.0) == (toccoBersaglio_ >= toccoUltimo_)) {
                    toccoBersaglio_ = toccoUltimo_;
                    toccoVelBersaglio_ = 0.0;
                }
            }
            const double desiderata =
                std::clamp((toccoBersaglio_ - pos_) / ritardoTocco_, -40.0 * base_, 40.0 * base_);
            vel_ = desiderata + (vel_ - desiderata) * segui_;
        } else if (scivola_) {
            vel_ = velMotore + (vel_ - velMotore) * coeffFrizione;
            if (std::fabs(vel_ - velMotore) < 1e-3 * base_) {
                vel_ = velMotore;
                scivola_ = false;
            }
        } else if (vel_ != velMotore) {
            const double diff = velMotore - vel_;
            if (motore_ && vel_ > 0.0 && std::fabs(diff) < 0.1 * base_) {
                vel_ = velMotore + (vel_ - velMotore) * coeffPitch;  // ritocco del pitch
                if (std::fabs(vel_ - velMotore) < 1e-6 * base_) vel_ = velMotore;
            } else {
                const double passo = motore_ ? passoAvvio : passoFrenata;
                vel_ += std::clamp(diff, -passo, passo);
            }
        }

        // --- lettura con interpolazione (e dissolvenza se la puntina è appena saltata)
        // vel_ = frame del brano per frame di uscita: oltre 1 gli acuti del brano superano la metà della frequenza di uscita
        const double passo = vel_;
        float sl, sr;
        leggiFiltrato(t, pos_, passo, sl, sr);
        if (sfumaRestanti_ > 0) {
            float vl, vr;
            leggiFiltrato(t, posVecchia_, passo, vl, vr);
            const float w = static_cast<float>(sfumaRestanti_) / kSfuma;
            sl += (vl - sl) * w;
            sr += (vr - sr) * w;
            posVecchia_ += vel_;
            --sfumaRestanti_;
        }
        const double av = std::fabs(vel_);
        if (av < soglia) {
            const float g = static_cast<float>(av / soglia);
            sl *= g * g;
            sr *= g * g;
        }
        l[i] = sl;
        r[i] = sr;

        if (keylockAcceso_) {
            // dove sarà la puntina tra "ritardo" campioni (anche facendo il giro del loop)
            double avanti = pos_ + ritardoKey_ * vel_;
            if (loop_ && vel_ > 0.0 && pos_ >= loopInizio_ && pos_ < loopFine_ && avanti >= loopFine_)
                avanti = loopInizio_ + std::fmod(avanti - loopInizio_, loopFine_ - loopInizio_);
            leggiFiltrato(t, avanti, passo, kInL_[i], kInR_[i]);
            // keylock solo a velocità di motore: durante scratch, frenate e avvii si sente il disco "vero"
            const bool usa = caldoKey_ + i >= keylock_.riscaldamento() && attesaKey_ <= 0 && motore_ && !tocco_ &&
                             !scivola_ && std::fabs(vel_ - velMotore) < 0.01 * base_;
            pesoKey_ = (usa ? 1.0f : 0.0f) + (pesoKey_ - (usa ? 1.0f : 0.0f)) * coeffPesoKey_;
            kPeso_[i] = pesoKey_;
            if (attesaKey_ > 0) --attesaKey_;
        }

        // --- avanzamento; il loop vale nei due sensi, solo per chi ci sta dentro
        const double prima = pos_;
        pos_ += vel_;
        if (loop_) {
            const double lunghezza = loopFine_ - loopInizio_;
            if (vel_ > 0.0 && prima < loopFine_ && pos_ >= loopFine_ && prima >= loopInizio_) {
                salta(pos_ - lunghezza, false);  // anche scratchando il dito resta agganciato
            } else if (vel_ < 0.0 && prima >= loopInizio_ && pos_ < loopInizio_ && prima <= loopFine_) {
                salta(pos_ + lunghezza, false);
            }
        }
        if (pos_ >= fine) {
            pos_ = fine;
            if (!tocco_ && !timecode) {
                motore_ = anteprimaCue_ = scivola_ = false;
                vel_ = 0.0;
            }
        } else if (pos_ < -60.0 * t.sampleRate) {
            pos_ = -60.0 * t.sampleRate;
        }
    }

    if (keylockAcceso_) {
        const float trasposizione = static_cast<float>(base_ / std::max(velNormale, 0.05 * base_));
        keylock_.processa(kInL_.data(), kInR_.data(), kOutL_.data(), kOutR_.data(), frame, trasposizione);
        caldoKey_ += frame;
        for (int i = 0; i < frame; ++i) {
            l[i] += (kOutL_[i] - l[i]) * kPeso_[i];
            r[i] += (kOutR_[i] - r[i]) * kPeso_[i];
        }
    } else {
        pesoKey_ = 0.0f;
    }
    for (int i = 0; i < frame; ++i) {
        l[i] = dcL_.processa(l[i]);
        r[i] = dcR_.processa(r[i]);
    }
    keylockInUso_.store(keylockAcceso_ && pesoKey_ > 0.5f, std::memory_order_relaxed);

    posizione_.store(pos_ / t.sampleRate, std::memory_order_relaxed);
    durata_.store(t.secondi(), std::memory_order_relaxed);
    velocitaLetta_.store(static_cast<float>(vel_ / base_), std::memory_order_relaxed);
    motoreLetto_.store(motore_, std::memory_order_relaxed);
    cueLetto_.store(cue_ / t.sampleRate, std::memory_order_relaxed);
}

}  // namespace djaaay
