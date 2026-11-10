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
constexpr uint32_t kVersioneFile = 6;  // 2: BPM e prima battuta · 3: griglia variabile · 4: mix · 5: tempi 3/2, deriva su 16 battute · 6: tempo locale, griglia fissa a tratti

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

// Tempo locale lungo il brano (in finestre di curva per battuta, per ogni finestra della curva). Nei mix e nei brani
// lunghi il tempo cambia anche di molto (es. 86 → 105 → 116 BPM da un brano all'altro): si misura il tempo a pezzi di
// 8 s (ogni 2 s, autocorrelazione degli attacchi su 1–4 battute, preferenza dolce attorno a 120) e si sceglie il
// percorso più coerente (Viterbi): cambiare tempo costa (8 per ottava), così dentro un brano il tempo resta fermo e
// cambia solo quando cambia davvero, per più finestre di fila.
std::vector<double> periodiLocali(const std::vector<float>& c, double fps) {
    const auto n = static_cast<int64_t>(c.size());
    const auto lunga = static_cast<int64_t>(8.0 * fps), passo = static_cast<int64_t>(2.0 * fps);
    constexpr double kMin = 60.0, kMax = 180.0, kStep = 0.5;
    const int nb = static_cast<int>((kMax - kMin) / kStep) + 1;
    const auto lagMin = static_cast<int64_t>(std::floor(60.0 / kMax * fps)), lagMax = static_cast<int64_t>(std::ceil(4.0 * 60.0 / kMin * fps)) + 2;
    if (n < lunga + lagMax) return std::vector<double>(static_cast<size_t>(n), 60.0 / 120.0 * fps);
    std::vector<std::vector<float>> punti;  // per finestra, per candidato (0…1)
    std::vector<double> ac(static_cast<size_t>(lagMax + 1));
    for (int64_t inizio = 0; inizio + lunga + lagMax <= n; inizio += passo) {
        for (int64_t lag = lagMin; lag <= lagMax; ++lag) {
            double s = 0.0;
            const float* x = c.data() + inizio;
            const float* y = x + lag;
            for (int64_t i = 0; i < lunga; ++i) s += static_cast<double>(x[i]) * y[i];
            ac[static_cast<size_t>(lag)] = s;
        }
        std::vector<float> v(static_cast<size_t>(nb));
        float massimo = 0.0f;
        for (int k = 0; k < nb; ++k) {
            const double bpm = kMin + k * kStep;
            const double periodo = 60.0 / bpm * fps;
            double s = 0.0;
            for (int m = 1; m <= 4; ++m) {
                const double p = m * periodo;
                const auto i = static_cast<int64_t>(p);
                const double f = p - static_cast<double>(i);
                s += (ac[static_cast<size_t>(i)] * (1.0 - f) + ac[static_cast<size_t>(i + 1)] * f) / m;
            }
            const double ottave = std::log2(bpm / 120.0);
            s *= std::exp(-0.5 * ottave * ottave / (0.45 * 0.45));
            v[static_cast<size_t>(k)] = static_cast<float>(std::max(0.0, s));
            massimo = std::max(massimo, v[static_cast<size_t>(k)]);
        }
        for (auto& x : v) x = massimo > 0.0f ? x / massimo : 1.0f;
        punti.push_back(std::move(v));
    }
    // Viterbi: punti (log) meno il costo dei cambi di tempo
    const size_t w = punti.size();
    std::vector<double> lnB(static_cast<size_t>(nb));
    for (int k = 0; k < nb; ++k) lnB[static_cast<size_t>(k)] = std::log2(kMin + k * kStep);
    constexpr double kCambio = 8.0;  // per ottava
    std::vector<double> acc(static_cast<size_t>(nb)), nuovo(static_cast<size_t>(nb));
    std::vector<std::vector<int>> da(w, std::vector<int>(static_cast<size_t>(nb)));
    for (int k = 0; k < nb; ++k) acc[static_cast<size_t>(k)] = std::log(1e-3 + punti[0][static_cast<size_t>(k)]);
    for (size_t i = 1; i < w; ++i) {
        for (int k = 0; k < nb; ++k) {
            double meglio = -1e300;
            int scelto = k;
            for (int j = 0; j < nb; ++j) {
                const double v = acc[static_cast<size_t>(j)] - kCambio * std::fabs(lnB[static_cast<size_t>(k)] - lnB[static_cast<size_t>(j)]);
                if (v > meglio) {
                    meglio = v;
                    scelto = j;
                }
            }
            nuovo[static_cast<size_t>(k)] = meglio + std::log(1e-3 + punti[i][static_cast<size_t>(k)]);
            da[i][static_cast<size_t>(k)] = scelto;
        }
        acc.swap(nuovo);
    }
    std::vector<int> percorso(w);
    percorso[w - 1] = static_cast<int>(std::max_element(acc.begin(), acc.end()) - acc.begin());
    for (size_t i = w - 1; i > 0; --i) percorso[i - 1] = da[i][static_cast<size_t>(percorso[i])];
    // a ogni finestra della curva il periodo della finestra di tempo più vicina (centro)
    std::vector<double> periodi(static_cast<size_t>(n));
    for (int64_t t = 0; t < n; ++t) {
        const auto i = static_cast<int64_t>(std::llround(static_cast<double>(t - lunga / 2) / static_cast<double>(passo)));
        const int k = percorso[static_cast<size_t>(std::clamp<int64_t>(i, 0, static_cast<int64_t>(w) - 1))];
        periodi[static_cast<size_t>(t)] = 60.0 / (kMin + k * kStep) * fps;
    }
    return periodi;
}

// Battute vere lungo tutto il brano, anche se il tempo cambia: programmazione dinamica (come Ellis, 2007). Ogni
// battuta sta su un attacco forte e dista dalla precedente circa un periodo: allontanarsene costa, così la griglia
// segue rallentamenti e accelerazioni ma non salta sui controtempi. Poi ogni battuta si affina tra le finestre
// (parabola sul picco) e si liscia con le vicine (parabola su ±8 battute): niente tremolii di qualche ms.
std::vector<double> trovaBattute(const std::vector<float>& curva, const std::vector<double>& periodi, double finestreAlSecondo) {
    const auto n = static_cast<int64_t>(curva.size());
    std::vector<double> battute;
    if (n < 4 || periodi.size() != curva.size() || n < static_cast<int64_t>(4 * periodi[0]) || periodi[0] < 4.0) return battute;
    double media = 0.0, q = 0.0;
    for (float v : curva) {
        media += v;
        q += static_cast<double>(v) * v;
    }
    media /= static_cast<double>(n);
    const double devStd = std::sqrt(std::max(1e-12, q / static_cast<double>(n) - media * media));
    // il periodo cambia lungo il brano: intervalli ammessi 0,7–1,3 del periodo locale, costo sullo scarto da quello
    const double periodoMax = *std::max_element(periodi.begin(), periodi.end());
    const auto aMax = static_cast<int64_t>(std::ceil(1.3 * periodoMax));
    std::vector<double> logD(static_cast<size_t>(aMax + 1), 0.0);
    for (int64_t d = 1; d <= aMax; ++d) logD[static_cast<size_t>(d)] = std::log(static_cast<double>(d));
    constexpr double kRigidita = 100.0;
    std::vector<double> punti(static_cast<size_t>(n));
    std::vector<int64_t> prima(static_cast<size_t>(n), -1);
    for (int64_t t = 0; t < n; ++t) {
        const double qui = curva[static_cast<size_t>(t)] / devStd;
        double meglio = 0.0;
        int64_t daDove = -1;
        const double periodo = periodi[static_cast<size_t>(t)], logP = std::log(periodo);
        const auto da = static_cast<int64_t>(std::floor(0.7 * periodo)), a = static_cast<int64_t>(std::ceil(1.3 * periodo));
        for (int64_t d = std::max<int64_t>(1, da); d <= a && d <= t; ++d) {
            const double r = logD[static_cast<size_t>(d)] - logP;
            const double v = punti[static_cast<size_t>(t - d)] - kRigidita * r * r;
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
    for (int64_t k = n - 1; k >= std::max<int64_t>(0, n - static_cast<int64_t>(std::ceil(1.3 * periodi.back()))); --k)
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
            return periodi[static_cast<size_t>(std::clamp<int64_t>(std::llround(indici[static_cast<size_t>(k)]), 0, n - 1))];
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
    // Cambi di brano (nei mix): un intervallo che si scosta più del 5% dai vicini è un salto di fase o di tempo.
    // La lisciatura non lo attraversa, altrimenti mescolerebbe le battute dei due brani. Ma un colpo solo fuori posto
    // (un intervallo lungo seguito da uno corto, o il contrario) non è un salto: lì la lisciatura serve proprio.
    std::vector<double> scosta(static_cast<size_t>(m), 0.0);
    for (int64_t k = 1; k < m; ++k) {
        std::vector<double> vicini;
        for (int64_t j = std::max<int64_t>(1, k - 4); j <= std::min<int64_t>(m - 1, k + 4); ++j)
            if (j != k) vicini.push_back(indici[static_cast<size_t>(j)] - indici[static_cast<size_t>(j - 1)]);
        if (vicini.size() >= 3) {
            std::nth_element(vicini.begin(), vicini.begin() + static_cast<long>(vicini.size() / 2), vicini.end());
            const double mediana = vicini[vicini.size() / 2];
            scosta[static_cast<size_t>(k)] = (indici[static_cast<size_t>(k)] - indici[static_cast<size_t>(k - 1)]) / mediana - 1.0;
        }
    }
    std::vector<int64_t> tratto(static_cast<size_t>(m), 0);
    for (int64_t k = 1; k < m; ++k) {
        const double qui = scosta[static_cast<size_t>(k)];
        bool salto = std::fabs(qui) > 0.05;
        for (int64_t j : {k - 1, k + 1}) {
            if (j < 1 || j >= m) continue;
            const double la = scosta[static_cast<size_t>(j)];
            if (std::fabs(la) > 0.02 && la * qui < 0.0 && std::fabs(la + qui) < 0.025) salto = false;  // si compensano
        }
        tratto[static_cast<size_t>(k)] = tratto[static_cast<size_t>(k - 1)] + (salto ? 1 : 0);
    }
    battute.resize(static_cast<size_t>(m));
    for (int64_t k = 0; k < m; ++k) {
        // parabola ai minimi quadrati sulle battute vicine (dello stesso tratto), valutata in k: segue anche un tempo
        // che cambia (una retta "taglierebbe le curve") e toglie il tremolio dei singoli attacchi
        int64_t i0 = std::max<int64_t>(0, k - 8), i1 = std::min<int64_t>(m - 1, k + 8);
        while (tratto[static_cast<size_t>(i0)] != tratto[static_cast<size_t>(k)]) ++i0;
        while (tratto[static_cast<size_t>(i1)] != tratto[static_cast<size_t>(k)]) --i1;
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

// Griglia fissa di ogni brano dentro un mix. I tratti col tempo locale fermo (entro l'1,5%) sono un brano (o un
// pezzo): su ognuno si cerca, come per un brano singolo, il tempo al centesimo (±2%) e la fase che prendono più
// attacchi su tutto il tratto (le parti con pochi colpi le copre il resto). Due tratti vicini con lo stesso tempo e
// la stessa fase si uniscono e si rimisurano insieme. Nei tratti la griglia diventa quella fissa trovata; fuori (i
// passaggi da un brano all'altro, i pezzi senza ritmo chiaro) restano le battute che seguono.
std::vector<double> grigliaATratti(const std::vector<float>& c, const std::vector<double>& periodi, std::vector<double> battute,
                                   double fps) {
    struct Tratto {
        double da, a;            // finestre della curva
        double periodo = 0.0, fase = 0.0;  // griglia trovata (finestre); fase = prima battuta del tratto
        bool buono = false;
    };
    const auto n = static_cast<int64_t>(c.size());
    std::vector<Tratto> tratti;
    for (int64_t i = 0; i < n;) {
        int64_t j = i + 1;
        while (j < n && std::fabs(periodi[static_cast<size_t>(j)] / periodi[static_cast<size_t>(i)] - 1.0) < 0.015) ++j;
        tratti.push_back({static_cast<double>(i), static_cast<double>(j)});
        i = j;
    }
    auto media = [&](double da, double a) {
        double s = 0.0;
        const auto i0 = static_cast<int64_t>(da), i1 = std::min<int64_t>(n, static_cast<int64_t>(a));
        for (int64_t i = i0; i < i1; ++i) s += c[static_cast<size_t>(i)];
        return i1 > i0 ? s / static_cast<double>(i1 - i0) : 0.0;
    };
    // la griglia (periodo, fase) che prende più attacchi nel tratto, partendo da un periodo vicino
    auto misura = [&](Tratto& t, double periodo0) {
        const double fondo = media(t.da, t.a);
        double meglio = -1.0;
        for (double bpm = 60.0 / periodo0 * fps * 0.98; bpm <= 60.0 / periodo0 * fps * 1.02; bpm += 0.01) {
            const double p = 60.0 / bpm * fps;
            for (double f = 0.0; f < p; f += 0.5) {
                double s = 0.0;
                int conta = 0;
                for (double x = t.da + f; x < t.a; x += p) {
                    s += valoreA(c, x);
                    ++conta;
                }
                if (conta > 0 && s / conta > meglio) {
                    meglio = s / conta;
                    t.periodo = p;
                    t.fase = t.da + f;
                }
            }
        }
        // una griglia vera prende colpi ben più forti della media del tratto
        t.buono = fondo > 0.0 && meglio > 1.25 * fondo;
    };
    // Il tempo nel tratto è davvero fermo? Gli spostamenti dei pezzi (dove la griglia fissa cade meglio sui colpi)
    // con un tempo fermo sono sparsi attorno allo zero; con un tempo che cambia piano seguono una curva. Parabola ai
    // minimi quadrati sugli spostamenti: se tra un capo e l'altro del tratto si muove più di 4 ms, il tempo cambia.
    auto tempoFermo = [](const std::vector<double>& x, const std::vector<double>& y) {
        const auto k = static_cast<int64_t>(x.size());
        if (k < 3) return false;  // troppo pochi pezzi per dirlo: meglio i battiti trovati uno a uno
        const double centro = 0.5 * (x.front() + x.back()), scala = std::max(1.0, 0.5 * (x.back() - x.front()));
        double m[3][4] = {};
        for (int64_t i = 0; i < k; ++i) {
            const double u = (x[static_cast<size_t>(i)] - centro) / scala, v = y[static_cast<size_t>(i)];
            const double p[3] = {1.0, u, u * u};
            for (int r = 0; r < 3; ++r) {
                for (int c = 0; c < 3; ++c) m[r][c] += p[r] * p[c];
                m[r][3] += p[r] * v;
            }
        }
        const int gradi = k >= 5 ? 3 : 2;  // con pochi pezzi solo la retta
        for (int c = 0; c < gradi; ++c)
            for (int r = c + 1; r < gradi; ++r) {
                const double f = m[r][c] / m[c][c];
                for (int j = c; j < 4; ++j) m[r][j] -= f * m[c][j];
            }
        double coef[3] = {0.0, 0.0, 0.0};
        for (int r = gradi - 1; r >= 0; --r) {
            double v = m[r][3];
            for (int j = r + 1; j < gradi; ++j) v -= m[r][j] * coef[j];
            coef[r] = v / m[r][r];
        }
        double minimo = 1e9, massimo = -1e9;
        for (double u = -1.0; u <= 1.0; u += 0.05) {
            const double v = coef[0] + coef[1] * u + coef[2] * u * u;
            minimo = std::min(minimo, v);
            massimo = std::max(massimo, v);
        }
        return massimo - minimo < 0.004;
    };
    for (auto& t : tratti)
        if (t.a - t.da >= 16.0 * fps) misura(t, periodi[static_cast<size_t>(static_cast<int64_t>(t.da))]);
    // unisci i vicini con lo stesso tempo (entro lo 0,3%) e la stessa fase (entro 15 ms): un brano spezzato in due
    for (size_t i = 0; i + 1 < tratti.size();) {
        Tratto& x = tratti[i];
        Tratto& y = tratti[i + 1];
        bool unisci = false;
        if (x.buono && y.buono && std::fabs(x.periodo / y.periodo - 1.0) < 0.003) {
            double r = (y.fase - x.fase) / x.periodo;
            r -= std::round(r);
            unisci = std::fabs(r) * x.periodo / fps < 0.015;
        }
        if (unisci) {
            Tratto u{x.da, y.a};
            misura(u, 0.5 * (x.periodo + y.periodo));
            if (u.buono) {
                x = u;
                tratti.erase(tratti.begin() + static_cast<long>(i) + 1);
                continue;
            }
        }
        ++i;
    }
    // Verifica a pezzi di 8 s: in ogni pezzo con un ritmo chiaro la griglia deve stare sui colpi (lo spostamento
    // migliore entro ±10 ms). Dove non ci sta (un brano nuovo allo stesso tempo ma sfasato, un tempo un po' diverso)
    // il tratto si divide lì e si rimisurano le due parti; i pezzi troppo corti restano con le battute che seguono.
    std::vector<Tratto> verificati;
    std::vector<Tratto> daFare(tratti.rbegin(), tratti.rend());
    const double pezzo = 8.0 * fps;
    while (!daFare.empty()) {
        Tratto t = daFare.back();
        daFare.pop_back();
        if (!t.buono || t.a - t.da < 16.0 * fps) {
            t.buono = false;
            verificati.push_back(t);
            continue;
        }
        double rotto = -1.0;
        std::vector<double> centri, spostamenti;  // dei pezzi con un ritmo chiaro: servono a vedere se il tempo cambia
        for (double x = t.da; x + pezzo <= t.a + 1.0; x += pezzo) {
            const double fondo = media(x, x + pezzo);
            double meglio = -1.0, spostamento = 0.0, alZero = 0.0;
            for (double d = -0.25 * t.periodo; d <= 0.25 * t.periodo; d += 0.5) {
                double s = 0.0;
                int conta = 0;
                const double k0 = std::ceil((x - t.fase) / t.periodo);
                for (double y = t.fase + k0 * t.periodo; y < x + pezzo; y += t.periodo) {
                    s += valoreA(c, y + d);
                    ++conta;
                }
                const double v = conta > 0 ? s / conta : 0.0;
                if (v > meglio) {
                    meglio = v;
                    spostamento = d;
                }
                if (std::fabs(d) < 0.25) alZero = v;
            }
            (void)alZero;
            // conta solo dove il ritmo è chiaro
            if (fondo > 0.0 && meglio > 1.25 * fondo) {
                if (std::fabs(spostamento) / fps > 0.010) {
                    rotto = x;
                    break;
                }
                centri.push_back(x + 0.5 * pezzo);
                spostamenti.push_back(spostamento / fps);
            }
        }
        if (rotto < 0.0) {
            // sta sui colpi: ma il tempo è davvero fermo? (un brano che accelera piano può stare entro 10 ms a pezzi)
            // Se no, i tratti lunghi si dividono a metà e si riprova, i corti restano fuori.
            if (tempoFermo(centri, spostamenti)) {
                verificati.push_back(t);
            } else if (t.a - t.da >= 32.0 * fps) {
                Tratto s1{t.da, 0.5 * (t.da + t.a)}, s2{0.5 * (t.da + t.a), t.a};
                misura(s1, t.periodo);
                misura(s2, t.periodo);
                daFare.push_back(s2);
                daFare.push_back(s1);
            } else {
                t.buono = false;
                verificati.push_back(t);
            }
            continue;
        }
        // si divide: se il pezzo storto è proprio all'inizio, quel pezzo resta fuori
        Tratto sinistra{t.da, rotto}, destra{rotto, t.a};
        if (rotto <= t.da + 0.5) {
            sinistra = {t.da, t.da + pezzo};
            destra = {t.da + pezzo, t.a};
        }
        for (Tratto* q : {&sinistra, &destra})
            if (q->a - q->da >= 16.0 * fps) misura(*q, t.periodo);
        daFare.push_back(destra);
        daFare.push_back(sinistra);
    }
    tratti = verificati;
    // Le battute dei tratti buoni prendono il posto di quelle che seguono. Ai bordi (dove il tratto confina con un
    // passaggio o una rampa di tempo) la griglia fissa si accorcia finché non combacia con le battute trovate
    // (entro 5 ms per tre battute di fila); si tolgono solo le battute coperte davvero dalla griglia.
    const std::vector<double> trovate = battute;
    auto vicinaTrovata = [&](double x) {
        const auto it = std::lower_bound(trovate.begin(), trovate.end(), x);
        double d = 1e9;
        if (it != trovate.end()) d = std::min(d, *it - x);
        if (it != trovate.begin()) d = std::min(d, x - *(it - 1));
        return d;
    };
    struct Pezzo {
        double primo, ultimo, periodo;
    };
    std::vector<Pezzo> messi;
    for (const auto& t : tratti) {
        if (!t.buono) continue;
        const double da = t.da / fps, a = t.a / fps, p = t.periodo / fps;
        std::vector<double> g;
        double x0 = t.fase / fps;
        while (x0 - p >= da) x0 -= p;
        for (double x = x0; x < a; x += p) g.push_back(x);
        if (g.size() < 8) continue;
        size_t i0 = 0, i1 = g.size();
        auto combacia = [&](size_t i) { return vicinaTrovata(g[i]) < 0.005; };
        while (i0 + 3 <= i1 && !(combacia(i0) && combacia(i0 + 1) && combacia(i0 + 2))) ++i0;
        while (i1 >= i0 + 3 && !(combacia(i1 - 1) && combacia(i1 - 2) && combacia(i1 - 3))) --i1;
        if (i1 < i0 + 8) continue;
        const double primo = g[i0], ultimo = g[i1 - 1];
        battute.erase(std::remove_if(battute.begin(), battute.end(),
                                     [&](double b) { return b > primo - 0.5 * p && b < ultimo + 0.5 * p; }),
                      battute.end());
        battute.insert(battute.end(), g.begin() + static_cast<long>(i0), g.begin() + static_cast<long>(i1));
        std::sort(battute.begin(), battute.end());
        messi.push_back({primo, ultimo, p});
    }
    // Due tratti vicini allo stesso tempo e in fase tra loro (lo stesso brano, con un pezzo in mezzo dove la verifica
    // non ha deciso, es. un break): il buco si riempie con battute uguali invece di quelle trovate una a una
    std::sort(messi.begin(), messi.end(), [](const Pezzo& x, const Pezzo& y) { return x.primo < y.primo; });
    for (size_t i = 0; i + 1 < messi.size(); ++i) {
        const Pezzo &l = messi[i], &r = messi[i + 1];
        const double buco = r.primo - l.ultimo, p = 0.5 * (l.periodo + r.periodo);
        if (buco <= 0.5 * p || buco > 10.0 || std::fabs(l.periodo / r.periodo - 1.0) > 0.005) continue;
        const double quante = std::round(buco / p);
        if (quante < 1.0 || std::fabs(buco / quante / p - 1.0) > 0.003) continue;  // tempo in mezzo quasi uguale
        battute.erase(std::remove_if(battute.begin(), battute.end(),
                                     [&](double b) { return b > l.ultimo + 0.25 * p && b < r.primo - 0.25 * p; }),
                      battute.end());
        for (int k = 1; k < static_cast<int>(quante); ++k) battute.push_back(l.ultimo + k * buco / quante);
        std::sort(battute.begin(), battute.end());
    }
    // niente battute troppo vicine dove un tratto incontra le battute che seguono
    std::vector<double> pulite;
    for (double b : battute) {
        if (!pulite.empty() && b - pulite.back() < 0.25) continue;
        pulite.push_back(b);
    }
    return pulite;
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

    // 1b) tempi "parenti" a 3/2 e 2/3: vince quello la cui griglia prende in media i colpi più forti (es. house a 174:
    // l'autocorrelazione preferiva 116, la cui griglia cade una volta su due sui controtempi). Doppio e metà no:
    // con kick e snare di forza diversa la metà prenderebbe solo i colpi forti e vincerebbe sempre.
    {
        auto media = [&](double b) {
            double f = 0.0;
            const double periodo = 60.0 / b * finestreAlSecondo;
            return adattaGriglia(c, periodo, f) / (static_cast<double>(c.size()) / periodo);
        };
        double scelto = bpm, valore = media(bpm);
        for (double r : {1.5, 2.0 / 3.0}) {
            const double b = bpm * r;
            if (b < 60.0 || b > 180.0) continue;
            const double v = media(b);
            if (v > 1.03 * valore) {
                scelto = b;
                valore = v;
            }
        }
        bpm = scelto;
    }

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

    // 3) il tempo cambia lungo il brano? Si cercano le battute vere: se per almeno 16 battute di fila si allontanano
    // dalla griglia fissa più di 20 ms (una deriva vera, non qualche colpo storto), la griglia diventa quella delle
    // battute vere
    // Tempo locale: se resta entro il 3% di quello trovato (a meno di un'ottava) il brano è a tempo costante e le
    // battute si cercano col periodo esatto; altrimenti (mix di brani diversi) si seguono i tempi locali, riportati
    // alla stessa "ottava" del tempo principale (es. 172 → 86 se il principale è 97), così la griglia resta uniforme.
    const double periodo = 60.0 / bpm * finestreAlSecondo;
    std::vector<double> periodi = periodiLocali(intera, finestreAlSecondo);
    bool costante = true;
    for (double& p : periodi) {
        double r = periodo / p;  // tempo locale / tempo principale
        // un tempo locale "parente" (doppio, metà, 3/2, 2/3: le ambiguità della misura) non vuol dire un brano diverso
        bool parente = false;
        for (double f : {1.0, 2.0, 0.5, 1.5, 2.0 / 3.0}) parente = parente || std::fabs(r * f - 1.0) <= 0.03;
        if (!parente) costante = false;
        while (r >= 1.5) r *= 0.5;
        while (r < 0.75) r *= 2.0;
        p = periodo / r;
    }
    if (costante) std::fill(periodi.begin(), periodi.end(), periodo);
    std::vector<double> vere = trovaBattute(intera, periodi, finestreAlSecondo);
    if (!costante) vere = grigliaATratti(intera, periodi, std::move(vere), finestreAlSecondo);
    const double battito = 60.0 / bpm;
    int fuori = costante ? 0 : 1 << 20, maxFuori = costante ? 0 : 1 << 20;  // tempi diversi: griglia variabile di sicuro
    for (double b : vere) {
        const double k = std::round((b - tempo.primoBattito) / battito);
        const double scarto = std::fabs(b - (tempo.primoBattito + k * battito));
        fuori = scarto > 0.020 ? fuori + 1 : 0;
        maxFuori = std::max(maxFuori, fuori);
    }
    if (maxFuori >= 16 && vere.size() >= 32) {
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
