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

std::vector<float> curvaAttacchi(const Traccia& t, int passo) {
    const int64_t n = t.frame();
    const int64_t finestre = n / passo;
    std::vector<float> curva(static_cast<size_t>(std::max<int64_t>(finestre, 0)), 0.0f);
    if (finestre < 2) return curva;
    Isolatore3 eq;
    eq.imposta(150.0f, 4000.0f, static_cast<float>(t.sampleRate));
    float prima[3] = {0.0f, 0.0f, 0.0f};
    const float peso[3] = {1.0f, 0.5f, 0.35f};
    for (int64_t w = 0; w < finestre; ++w) {
        double e[3] = {0.0, 0.0, 0.0};
        for (int64_t i = w * passo; i < (w + 1) * passo; ++i) {
            const float x = 0.5f * (t.campione(i, 0) + t.campione(i, 1));
            float b, m, a;
            eq.separa(x, b, m, a);
            e[0] += b * b;
            e[1] += m * m;
            e[2] += a * a;
        }
        float flusso = 0.0f;
        for (int k = 0; k < 3; ++k) {
            const float v = std::log1p(1000.0f * static_cast<float>(std::sqrt(e[k] / passo)));
            flusso += peso[k] * std::max(0.0f, v - prima[k]);
            prima[k] = v;
        }
        curva[static_cast<size_t>(w)] = flusso;
    }
    // toglie la media mobile (~1 s): restano i picchi degli attacchi
    const int mezzo = static_cast<int>(0.5 * t.sampleRate / passo);
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

Tempo analizzaTempo(const Traccia& t) {
    Tempo tempo;
    // finestre di ~5,8 ms
    const int passo = std::max(64, static_cast<int>(std::lround(t.sampleRate * 0.0058)));
    const double finestreAlSecondo = t.sampleRate / passo;
    std::vector<float> c = curvaAttacchi(t, passo);
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

Waveform analizzaWaveform(const Traccia& t) {
    Waveform w;
    const int64_t n = t.frame();
    const int64_t colonne = (n + Waveform::kFramePerColonna - 1) / Waveform::kFramePerColonna;
    Waveform::Livello base;
    base.bassi.resize(static_cast<size_t>(colonne));
    base.medi.resize(static_cast<size_t>(colonne));
    base.alti.resize(static_cast<size_t>(colonne));

    // Stesse bande dell'EQ del mixer (250 Hz e 2,5 kHz), sul mono L+R
    Isolatore3 eq;
    eq.imposta(250.0f, 2500.0f, static_cast<float>(t.sampleRate));
    for (int64_t c = 0; c < colonne; ++c) {
        float pb = 0.0f, pm = 0.0f, pa = 0.0f;
        const int64_t fine = std::min(n, (c + 1) * Waveform::kFramePerColonna);
        for (int64_t i = c * Waveform::kFramePerColonna; i < fine; ++i) {
            const float x = 0.5f * (t.campione(i, 0) + t.campione(i, 1));
            float b, m, a;
            eq.separa(x, b, m, a);
            pb = std::max(pb, std::fabs(b));
            pm = std::max(pm, std::fabs(m));
            pa = std::max(pa, std::fabs(a));
        }
        base.bassi[c] = scala(pb);
        base.medi[c] = scala(pm);
        base.alti[c] = scala(pa);
    }
    w.livelli.push_back(std::move(base));
    costruisciLivelli(w);
    return w;
}

}  // namespace djaaay
