#include "Analisi.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>

#include "Filtri.h"
#include <vector>

namespace djaaay {

namespace {

uint8_t scala(float picco) {
    const float v = std::sqrt(std::min(1.0f, picco)) * 255.0f;
    return static_cast<uint8_t>(std::lround(v));
}

// Livelli più "lontani": massimo di coppie di colonne, finché restano più di 256 colonne.
void costruisciLivelli(Waveform& w) {
    w.livelli.resize(1);
    while (w.livelli.back().colonne() > 256) {
        const Waveform::Livello& prima = w.livelli.back();
        Waveform::Livello l;
        l.framePerColonna = prima.framePerColonna * 2;
        const size_t m = (prima.colonne() + 1) / 2;
        l.bassi.resize(m);
        l.medi.resize(m);
        l.alti.resize(m);
        for (size_t i = 0; i < m; ++i) {
            const size_t j = std::min(2 * i + 1, prima.colonne() - 1);
            l.bassi[i] = std::max(prima.bassi[2 * i], prima.bassi[j]);
            l.medi[i] = std::max(prima.medi[2 * i], prima.medi[j]);
            l.alti[i] = std::max(prima.alti[2 * i], prima.alti[j]);
        }
        w.livelli.push_back(std::move(l));
    }
}

const char kFirma[4] = {'D', 'V', 'S', 'W'};
constexpr uint32_t kVersioneFile = 2;  // 2: con BPM e prima battuta

}  // namespace

bool salvaWaveform(const Waveform& w, const Tempo& tempo, const char* percorso) {
    if (w.livelli.empty()) return false;
    const Waveform::Livello& l = w.livelli[0];
    FILE* f = std::fopen(percorso, "wb");
    if (f == nullptr) return false;
    const uint32_t testa[2] = {kVersioneFile, static_cast<uint32_t>(l.framePerColonna)};
    const uint64_t colonne = l.colonne();
    bool ok = std::fwrite(kFirma, 1, 4, f) == 4 && std::fwrite(testa, sizeof testa, 1, f) == 1 &&
              std::fwrite(&colonne, sizeof colonne, 1, f) == 1 && std::fwrite(&tempo.bpm, sizeof(double), 1, f) == 1 &&
              std::fwrite(&tempo.primoBattito, sizeof(double), 1, f) == 1;
    for (const auto* banda : {&l.bassi, &l.medi, &l.alti})
        ok = ok && std::fwrite(banda->data(), 1, banda->size(), f) == banda->size();
    return std::fclose(f) == 0 && ok;
}

bool caricaWaveform(const char* percorso, Waveform& w, Tempo& tempo) {
    FILE* f = std::fopen(percorso, "rb");
    if (f == nullptr) return false;
    char firma[4];
    uint32_t testa[2];
    uint64_t colonne = 0;
    bool ok = std::fread(firma, 1, 4, f) == 4 && std::memcmp(firma, kFirma, 4) == 0 &&
              std::fread(testa, sizeof testa, 1, f) == 1 && testa[0] == kVersioneFile &&
              testa[1] == Waveform::kFramePerColonna && std::fread(&colonne, sizeof colonne, 1, f) == 1 &&
              colonne < (1ull << 28);  // file rovinato: non si alloca all'infinito
    Tempo letto;
    ok = ok && std::fread(&letto.bpm, sizeof(double), 1, f) == 1 && std::fread(&letto.primoBattito, sizeof(double), 1, f) == 1 &&
         letto.bpm >= 0.0 && letto.bpm < 1000.0;
    Waveform::Livello l;
    if (ok) {
        for (auto* banda : {&l.bassi, &l.medi, &l.alti}) {
            banda->resize(static_cast<size_t>(colonne));
            ok = ok && std::fread(banda->data(), 1, banda->size(), f) == banda->size();
        }
    }
    std::fclose(f);
    if (!ok) return false;
    w.livelli.clear();
    w.livelli.push_back(std::move(l));
    costruisciLivelli(w);
    tempo = letto;
    return true;
}

// ---------------------------------------------------------------------------------------------------------------
// BPM
//
// 1. "Attacchi": ogni ~11 ms si misura l'energia di bassi, medi e alti; dove cresce di colpo c'è un attacco
//    (la cassa pesa di più). 2. Per ogni BPM candidato (70…180, passo 0,05) si somma quanto la curva degli
//    attacchi somiglia a se stessa spostata di 1, 2, 3, 4 battute; si preferiscono i tempi vicini a 120 per
//    risolvere i casi doppio/metà. 3. Si affina al centesimo e si cerca la fase (dove cade la prima battuta).

namespace {

// Toglie la media mobile (~1 s) dalla curva grezza degli attacchi: restano i picchi
std::vector<float> pulisci(const std::vector<float>& curva, double finestreAlSecondo) {
    const auto finestre = static_cast<int64_t>(curva.size());
    const int mezzo = static_cast<int>(0.5 * finestreAlSecondo);
    std::vector<float> pulita(curva.size(), 0.0f);
    double somma = 0.0;
    int64_t da = 0, a = -1;
    for (int64_t i = 0; i < finestre; ++i) {
        while (a < std::min<int64_t>(finestre - 1, i + mezzo)) somma += curva[static_cast<size_t>(++a)];
        while (da < i - mezzo) somma -= curva[static_cast<size_t>(da++)];
        const float media = static_cast<float>(somma / static_cast<double>(a - da + 1));
        pulita[static_cast<size_t>(i)] = std::max(0.0f, curva[static_cast<size_t>(i)] - media);
    }
    return pulita;
}

// Valore della curva in una posizione frazionaria (interpolazione lineare)
float valoreA(const std::vector<float>& c, double pos) {
    if (pos < 0.0) return 0.0f;
    const auto i = static_cast<size_t>(pos);
    if (i + 1 >= c.size()) return 0.0f;
    const float f = static_cast<float>(pos - static_cast<double>(i));
    return c[i] + (c[i + 1] - c[i]) * f;
}

// Somiglianza della curva con se stessa spostata di `ritardo` finestre (autocorrelazione)
double autocorrelazione(const std::vector<float>& c, double ritardo) {
    double s = 0.0;
    const size_t fine = c.size() - std::min(c.size(), static_cast<size_t>(ritardo) + 2);
    for (size_t i = 0; i < fine; i += 1) s += c[i] * valoreA(c, static_cast<double>(i) + ritardo);
    return s;
}

double punteggio(const std::vector<float>& c, double bpm, double finestreAlSecondo) {
    const double periodo = 60.0 / bpm * finestreAlSecondo;
    double s = 0.0;
    for (int k = 1; k <= 4; ++k) s += autocorrelazione(c, k * periodo) / k;
    // preferenza per i tempi "da DJ": massima a 120, metà/doppio penalizzati dolcemente
    const double ottave = std::log2(bpm / 120.0);
    return s * std::exp(-0.5 * ottave * ottave / (0.45 * 0.45));
}

// L'attacco viene "visto" con un po' di ritardo rispetto all'inizio vero del colpo (in finestre)
constexpr double kCorrezioneFase = 0.0;

// Quanto una griglia di battute (periodo in finestre) prende gli attacchi, con la fase migliore
double adattaGriglia(const std::vector<float>& c, double periodo, double& faseMigliore) {
    double migliore = -1.0;
    for (double fase = 0.0; fase < periodo; fase += 0.5) {
        double s = 0.0;
        for (double p = fase; p < static_cast<double>(c.size()); p += periodo) s += valoreA(c, p);
        if (s > migliore) {
            migliore = s;
            faseMigliore = fase;
        }
    }
    return migliore;
}

}  // namespace

// finestre di ~5,8 ms
static int passoAttacchi(double sampleRate) { return std::max(32, static_cast<int>(std::lround(sampleRate * 0.0058))); }

static Tempo tempoDaCurva(const std::vector<float>& grezza, double finestreAlSecondo) {
    Tempo tempo;
    std::vector<float> c = pulisci(grezza, finestreAlSecondo);
    const size_t massimo = static_cast<size_t>(240.0 * finestreAlSecondo);  // bastano i primi 4 minuti
    if (c.size() > massimo) c.resize(massimo);
    if (c.size() < static_cast<size_t>(8.0 * finestreAlSecondo)) return tempo;  // troppo corto

    // Ritmo presente? Gli attacchi veri (cassa, rullante) sono picchi netti; un suono continuo non ne ha.
    {
        std::vector<float> ordinati(c.begin() + static_cast<long>(finestreAlSecondo), c.end());
        const size_t k = ordinati.size() * 99 / 100;
        std::nth_element(ordinati.begin(), ordinati.begin() + static_cast<long>(k), ordinati.end());
        if (ordinati[k] < 0.2f) return tempo;
    }

    // 1) ricerca grossolana con l'autocorrelazione (decide anche tra doppio e metà)
    double migliore = 0.0, bpm = 0.0;
    for (double b = 70.0; b <= 180.0; b += 0.5) {
        const double p = punteggio(c, b, finestreAlSecondo);
        if (p > migliore) {
            migliore = p;
            bpm = b;
        }
    }
    if (bpm <= 0.0) return tempo;

    // 2) ricerca fine: la griglia di battute che prende più attacchi su tutto il brano
    double fase = 0.0, migliorGriglia = -1.0, bpmFine = bpm;
    for (double b = bpm - 0.6; b <= bpm + 0.6; b += 0.01) {
        double f = 0.0;
        const double s = adattaGriglia(c, 60.0 / b * finestreAlSecondo, f);
        if (s > migliorGriglia) {
            migliorGriglia = s;
            bpmFine = b;
            fase = f;
        }
    }
    bpm = bpmFine;
    // i brani hanno quasi sempre BPM intero o mezzo: vicinissimi si arrotonda (se la griglia regge uguale)
    const double arrotondato = std::round(bpm * 2.0) / 2.0;
    if (std::fabs(arrotondato - bpm) < 0.03) {
        double f = 0.0;
        if (adattaGriglia(c, 60.0 / arrotondato * finestreAlSecondo, f) >= 0.98 * migliorGriglia) {
            bpm = arrotondato;
            fase = f;
        }
    }
    tempo.bpm = bpm;
    tempo.primoBattito = (fase + kCorrezioneFase) / finestreAlSecondo;
    return tempo;
}

AnalisiProgressiva::AnalisiProgressiva(double sampleRate) : sr_(0.5 * sampleRate), passo_(passoAttacchi(0.5 * sampleRate)) {
    // stesse bande dell'EQ del mixer (250 Hz e 2,5 kHz) per la waveform; per gli attacchi 150 Hz e 4 kHz
    eqOnda_.imposta(250.0f, 2500.0f, static_cast<float>(sr_));
    eqTempo_.imposta(150.0f, std::min(4000.0f, 0.4f * static_cast<float>(sr_)), static_cast<float>(sr_));
}

// Fine di una colonna della waveform (picchi di bassi, medi e alti)
void AnalisiProgressiva::chiudiColonna() {
    base_.bassi.push_back(scala(picco_[0]));
    base_.medi.push_back(scala(picco_[1]));
    base_.alti.push_back(scala(picco_[2]));
    picco_[0] = picco_[1] = picco_[2] = 0.0f;
    inColonna_ = 0;
}

// Fine di una finestra della curva degli attacchi: dove l'energia di una banda cresce di colpo (la cassa pesa di più)
void AnalisiProgressiva::chiudiFinestra() {
    const float peso[3] = {1.0f, 0.5f, 0.35f};
    float flusso = 0.0f;
    for (int k = 0; k < 3; ++k) {
        const float v = std::log1p(1000.0f * static_cast<float>(std::sqrt(energia_[k] / passo_)));
        // la prima finestra non ha un "prima": come se la precedente fosse silenzio
        flusso += peso[k] * std::max(0.0f, v - prima_[k]);
        prima_[k] = v;
        energia_[k] = 0.0;
    }
    attacchi_.push_back(flusso);
    inFinestra_ = 0;
}

// Un solo passaggio sul mono L+R per tutte e due le analisi
void AnalisiProgressiva::aggiorna(const Traccia& t) {
    int64_t quanti = 0;
    while (const int16_t* p = t.pezzo(fatti_, quanti)) {
        for (int64_t i = 0; i < quanti; ++i) {
            const float mono = (static_cast<float>(p[2 * i]) + static_cast<float>(p[2 * i + 1])) * (0.5f / 32768.0f);
            if (!dispari_) {
                meta_ = mono;
                dispari_ = true;
                continue;
            }
            dispari_ = false;
            const float x = 0.5f * (meta_ + mono);
            float b, m, a;
            eqOnda_.separa(x, b, m, a);
            picco_[0] = std::max(picco_[0], std::fabs(b));
            picco_[1] = std::max(picco_[1], std::fabs(m));
            picco_[2] = std::max(picco_[2], std::fabs(a));
            if (++inColonna_ == Waveform::kFramePerColonna / 2) chiudiColonna();
            eqTempo_.separa(x, b, m, a);
            energia_[0] += b * b;
            energia_[1] += m * m;
            energia_[2] += a * a;
            if (++inFinestra_ == passo_) chiudiFinestra();
        }
        fatti_ += quanti;
    }
}

void AnalisiProgressiva::finisci(const Traccia& t) {
    aggiorna(t);
    // l'ultima colonna, più corta (l'ultima finestra incompleta invece non conta)
    if (inColonna_ > 0 || dispari_) {
        if (dispari_) {
            const float x = meta_;
            float b, m, a;
            eqOnda_.separa(x, b, m, a);
            picco_[0] = std::max(picco_[0], std::fabs(b));
            picco_[1] = std::max(picco_[1], std::fabs(m));
            picco_[2] = std::max(picco_[2], std::fabs(a));
            dispari_ = false;
        }
        chiudiColonna();
    }
}

Waveform AnalisiProgressiva::waveform() const {
    Waveform w;
    w.livelli.push_back(base_);
    costruisciLivelli(w);
    return w;
}

Tempo AnalisiProgressiva::tempo() const { return tempoDaCurva(attacchi_, sr_ / passo_); }

Waveform analizzaWaveform(const Traccia& t) {
    AnalisiProgressiva a(t.sampleRate);
    a.finisci(t);
    return a.waveform();
}

Tempo analizzaTempo(const Traccia& t) {
    AnalisiProgressiva a(t.sampleRate);
    a.finisci(t);
    return a.tempo();
}

}  // namespace djaaay
