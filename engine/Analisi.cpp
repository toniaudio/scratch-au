#include "Analisi.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>

#include "Filtri.h"
#include "SenzaDenormali.h"
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
constexpr uint32_t kVersioneFile = 3;  // 2: con BPM e prima battuta · 3: con la griglia variabile

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
    const uint64_t battiti = tempo.battiti.size();
    ok = ok && std::fwrite(&battiti, sizeof battiti, 1, f) == 1 &&
         (battiti == 0 || std::fwrite(tempo.battiti.data(), sizeof(double), battiti, f) == battiti);
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
    uint64_t battiti = 0;
    ok = ok && std::fread(&battiti, sizeof battiti, 1, f) == 1 && battiti < (1ull << 24);
    if (ok && battiti > 0) {
        letto.battiti.resize(static_cast<size_t>(battiti));
        ok = std::fread(letto.battiti.data(), sizeof(double), battiti, f) == battiti;
    }
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

// Battute vere lungo tutto il brano, anche se il tempo cambia: programmazione dinamica (come Ellis, 2007). Ogni
// battuta sta su un attacco forte e dista dalla precedente circa un periodo: allontanarsene costa, così la griglia
// segue rallentamenti e accelerazioni ma non salta sui controtempi. Poi ogni battuta si affina tra le finestre
// (parabola sul picco) e si liscia con le vicine (parabola su ±8 battute): niente tremolii di qualche ms.
std::vector<double> trovaBattute(const std::vector<float>& curva, double periodo, double finestreAlSecondo) {
    const auto n = static_cast<int64_t>(curva.size());
    std::vector<double> battute;
    if (n < static_cast<int64_t>(4 * periodo) || periodo < 4.0) return battute;
    double media = 0.0, q = 0.0;
    for (float v : curva) {
        media += v;
        q += static_cast<double>(v) * v;
    }
    media /= static_cast<double>(n);
    const double devStd = std::sqrt(std::max(1e-12, q / static_cast<double>(n) - media * media));
    const auto da = static_cast<int64_t>(std::floor(0.7 * periodo)), a = static_cast<int64_t>(std::ceil(1.3 * periodo));
    std::vector<double> costo(static_cast<size_t>(a + 1), 0.0);
    constexpr double kRigidita = 100.0;
    for (int64_t d = da; d <= a; ++d) {
        const double r = std::log(static_cast<double>(d) / periodo);
        costo[static_cast<size_t>(d)] = kRigidita * r * r;
    }
    std::vector<double> punti(static_cast<size_t>(n));
    std::vector<int64_t> prima(static_cast<size_t>(n), -1);
    for (int64_t t = 0; t < n; ++t) {
        const double qui = curva[static_cast<size_t>(t)] / devStd;
        double meglio = 0.0;
        int64_t daDove = -1;
        for (int64_t d = da; d <= a && d <= t; ++d) {
            const double v = punti[static_cast<size_t>(t - d)] - costo[static_cast<size_t>(d)];
            if (daDove < 0 || v > meglio) {
                meglio = v;
                daDove = t - d;
            }
        }
        punti[static_cast<size_t>(t)] = qui + (daDove >= 0 ? meglio : 0.0);
        prima[static_cast<size_t>(t)] = daDove;
    }
    // l'ultima battuta: la migliore nell'ultimo periodo; poi all'indietro
    int64_t t = n - 1;
    for (int64_t k = n - 1; k >= std::max<int64_t>(0, n - a); --k)
        if (punti[static_cast<size_t>(k)] > punti[static_cast<size_t>(t)]) t = k;
    std::vector<double> indici;
    for (; t >= 0; t = prima[static_cast<size_t>(t)]) {
        double f = static_cast<double>(t);
        if (t > 0 && t + 1 < n) {
            const double y0 = curva[static_cast<size_t>(t - 1)], y1 = curva[static_cast<size_t>(t)], y2 = curva[static_cast<size_t>(t + 1)];
            const double den = y0 - 2.0 * y1 + y2;
            if (y1 >= y0 && y1 >= y2 && den < 0.0) f += std::clamp(0.5 * (y0 - y2) / den, -0.5, 0.5);
        }
        indici.push_back(f);
    }
    std::reverse(indici.begin(), indici.end());

    // Battute "ritmiche": su un colpo chiaro, e anche le vicine lo sono. Nei tratti senza ritmo (break, pause con
    // qualche suono sparso) la programmazione dinamica può inseguire un suono isolato: lì le battute si
    // ridistribuiscono uguali tra le ultime ritmiche prima e le prime dopo.
    const auto nb = static_cast<int64_t>(indici.size());
    std::vector<char> forte(static_cast<size_t>(nb)), ritmica(static_cast<size_t>(nb));
    for (int64_t k = 0; k < nb; ++k)
        forte[static_cast<size_t>(k)] = curva[static_cast<size_t>(std::llround(indici[static_cast<size_t>(k)]))] / devStd >= 1.0;
    for (int64_t k = 0; k < nb; ++k)
        ritmica[static_cast<size_t>(k)] = forte[static_cast<size_t>(k)] && (k == 0 || forte[static_cast<size_t>(k - 1)]) &&
                                          (k + 1 == nb || forte[static_cast<size_t>(k + 1)]);
    std::vector<int64_t> ancore;
    for (int64_t k = 0; k < nb; ++k)
        if (ritmica[static_cast<size_t>(k)]) ancore.push_back(k);
    if (ancore.size() >= 2) {
        // periodo vicino a un'ancora: dalle battute ritmiche accanto (altrimenti quello medio)
        auto periodoVicino = [&](int64_t k) {
            for (int64_t j : {k + 1, k - 1})
                if (j >= 0 && j < nb && ritmica[static_cast<size_t>(j)])
                    return std::fabs(indici[static_cast<size_t>(std::max(j, k))] - indici[static_cast<size_t>(std::min(j, k))]);
            return periodo;
        };
        std::vector<double> nuovi;
        // prima della prima ancora: all'indietro col suo periodo fino all'inizio del brano
        const double p0 = periodoVicino(ancore.front());
        for (double t = indici[static_cast<size_t>(ancore.front())] - p0; t >= 0.0; t -= p0) nuovi.push_back(t);
        std::reverse(nuovi.begin(), nuovi.end());
        for (size_t a = 0; a < ancore.size(); ++a) {
            const double ta = indici[static_cast<size_t>(ancore[a])];
            nuovi.push_back(ta);
            if (a + 1 == ancore.size()) break;
            const double tb = indici[static_cast<size_t>(ancore[a + 1])];
            if (ancore[a + 1] == ancore[a] + 1) continue;  // vicine: niente da riempire
            const double p = 0.5 * (periodoVicino(ancore[a]) + periodoVicino(ancore[a + 1]));
            const int64_t passi = std::max<int64_t>(1, std::llround((tb - ta) / p));
            for (int64_t i = 1; i < passi; ++i) nuovi.push_back(ta + (tb - ta) * static_cast<double>(i) / static_cast<double>(passi));
        }
        // dopo l'ultima: avanti fino alla fine
        const double pn = periodoVicino(ancore.back());
        for (double t = indici[static_cast<size_t>(ancore.back())] + pn; t < static_cast<double>(n); t += pn) nuovi.push_back(t);
        indici = std::move(nuovi);
    }
    const auto m = static_cast<int64_t>(indici.size());
    battute.resize(static_cast<size_t>(m));
    for (int64_t k = 0; k < m; ++k) {
        // parabola ai minimi quadrati sulle battute vicine, valutata in k: segue anche un tempo che cambia
        // (una retta "taglierebbe le curve") e toglie il tremolio dei singoli attacchi
        const int64_t i0 = std::max<int64_t>(0, k - 8), i1 = std::min<int64_t>(m - 1, k + 8);
        double s0 = 0, s1 = 0, s2 = 0, s3 = 0, s4 = 0, y0 = 0, y1 = 0, y2 = 0;
        for (int64_t i = i0; i <= i1; ++i) {
            const double x = static_cast<double>(i - k), y = indici[static_cast<size_t>(i)] - indici[static_cast<size_t>(k)];
            s0 += 1;
            s1 += x;
            s2 += x * x;
            s3 += x * x * x;
            s4 += x * x * x * x;
            y0 += y;
            y1 += x * y;
            y2 += x * x * y;
        }
        // sistema 3×3 (Cramer) per y = a + b·x + c·x²: serve solo a
        const double det = s0 * (s2 * s4 - s3 * s3) - s1 * (s1 * s4 - s3 * s2) + s2 * (s1 * s3 - s2 * s2);
        double a0 = 0.0;
        if (std::fabs(det) > 1e-9) {
            a0 = (y0 * (s2 * s4 - s3 * s3) - s1 * (y1 * s4 - s3 * y2) + s2 * (y1 * s3 - s2 * y2)) / det;
        }
        battute[static_cast<size_t>(k)] = (indici[static_cast<size_t>(k)] + a0) / finestreAlSecondo;
    }
    for (size_t k = 1; k < battute.size(); ++k) battute[k] = std::max(battute[k], battute[k - 1] + 1e-3);
    return battute;
}

}  // namespace

// finestre di ~5,8 ms
static int passoAttacchi(double sampleRate) { return std::max(32, static_cast<int>(std::lround(sampleRate * 0.0058))); }

static Tempo tempoDaCurva(const std::vector<float>& grezza, double finestreAlSecondo) {
    Tempo tempo;
    std::vector<float> c = pulisci(grezza, finestreAlSecondo);
    const std::vector<float> intera = c;  // per la griglia variabile serve tutto il brano
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

    // 3) il tempo cambia lungo il brano? Si cercano le battute vere: se per almeno 8 battute di fila si allontanano
    // dalla griglia fissa più di 20 ms, la griglia diventa quella delle battute vere
    std::vector<double> vere = trovaBattute(intera, 60.0 / bpm * finestreAlSecondo, finestreAlSecondo);
    const double battito = 60.0 / bpm;
    int fuori = 0, maxFuori = 0;
    for (double b : vere) {
        const double k = std::round((b - tempo.primoBattito) / battito);
        const double scarto = std::fabs(b - (tempo.primoBattito + k * battito));
        fuori = scarto > 0.020 ? fuori + 1 : 0;
        maxFuori = std::max(maxFuori, fuori);
    }
    if (maxFuori >= 8 && vere.size() >= 16) {
        std::vector<double> intervalli;
        for (size_t k = 1; k < vere.size(); ++k) intervalli.push_back(vere[k] - vere[k - 1]);
        std::nth_element(intervalli.begin(), intervalli.begin() + static_cast<long>(intervalli.size() / 2), intervalli.end());
        tempo.bpm = std::round(60.0 / intervalli[intervalli.size() / 2] * 100.0) / 100.0;
        tempo.primoBattito = vere.front();
        tempo.battiti = std::move(vere);
    }
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
    SenzaDenormali senza;  // nei silenzi i filtri sarebbero lentissimi
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
