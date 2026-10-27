// Test del DSP, senza Apple: g++ -std=c++17 -IDSP tests/test_dsp.cpp -o test_dsp && ./test_dsp
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <vector>

#include "ScratchDSP.hpp"

using namespace scratch;

static int errori = 0;

static void verifica(bool ok, const char* cosa) {
    std::printf("%s %s\n", ok ? "ok  " : "ERR ", cosa);
    if (!ok) ++errori;
}

static bool vicino(float a, float b, float tol = 1e-4f) { return std::fabs(a - b) <= tol; }

// Fa girare il DSP su `frame` campioni a 1,0 e restituisce l'ultimo campione in uscita.
static float uscitaStabile(ScratchDSP& dsp, int frame = 4800) {
    std::vector<float> l(frame, 1.0f), r(frame, 1.0f);
    float* canali[2] = {l.data(), r.data()};
    dsp.process(canali, canali, 2, frame);  // sul posto, come può chiedere l'host
    return l.back();
}

int main() {
    // curve
    Guadagni g = guadagniCrossfader(0.5f, kCurvaTaglio, false);
    verifica(vicino(g.traccia, 1) && vicino(g.ingresso, 1), "taglio: al centro entrambi pieni");
    g = guadagniCrossfader(0.0f, kCurvaTaglio, false);
    verifica(vicino(g.traccia, 1) && vicino(g.ingresso, 0), "taglio: tutto a sinistra solo traccia");
    g = guadagniCrossfader(1.0f, kCurvaTaglio, false);
    verifica(vicino(g.traccia, 0) && vicino(g.ingresso, 1), "taglio: tutto a destra solo ingresso");
    g = guadagniCrossfader(0.05f, kCurvaTaglio, false);
    verifica(vicino(g.ingresso, 1), "taglio: ingresso pieno appena fuori dalla zona di taglio");
    g = guadagniCrossfader(0.0f, kCurvaTaglio, true);
    verifica(vicino(g.traccia, 0) && vicino(g.ingresso, 1), "inverti scambia i lati");
    g = guadagniCrossfader(0.25f, kCurvaLineare, false);
    verifica(vicino(g.traccia, 0.75f) && vicino(g.ingresso, 0.25f), "lineare");
    g = guadagniCrossfader(0.5f, kCurvaPotenza, false);
    verifica(vicino(g.traccia * g.traccia + g.ingresso * g.ingresso, 1), "potenza costante");
    g = guadagniCrossfader(7.0f, kCurvaLineare, false);
    verifica(vicino(g.ingresso, 1), "valori fuori scala limitati");

    // parametri
    ScratchDSP dsp;
    dsp.prepare(48000, 2);
    dsp.setParameter(kParamCurva, 5);
    verifica(vicino(dsp.getParameter(kParamCurva), 2), "curva limitata a 0..2");
    dsp.setParameter(kParamCurva, kCurvaTaglio);
    dsp.setParameter(kParamCrossfader, -1);
    verifica(vicino(dsp.getParameter(kParamCrossfader), 0), "crossfader limitato a 0..1");

    // thru e taglio
    dsp.setParameter(kParamCrossfader, 0.5f);
    dsp.prepare(48000, 2);
    verifica(vicino(uscitaStabile(dsp), 1), "al centro l'ingresso passa invariato");
    dsp.setParameter(kParamCrossfader, 0.0f);
    verifica(vicino(uscitaStabile(dsp), 0), "crossfader a sinistra: ingresso tagliato");

    // il taglio deve essere rapido (smoothing ~1 ms): dopo 5 ms a 48 kHz l'ingresso è già pieno
    dsp.setParameter(kParamCrossfader, 1.0f);
    const float dopo5ms = uscitaStabile(dsp, 240);
    verifica(dopo5ms > 0.99f, "il taglio si apre entro 5 ms");

    // niente salto istantaneo: il primo campione dopo il cambio non è già pieno (evita click)
    dsp.setParameter(kParamCrossfader, 0.0f);
    uscitaStabile(dsp);
    dsp.setParameter(kParamCrossfader, 1.0f);
    std::vector<float> l(4, 1.0f), r(4, 1.0f);
    float* canali[2] = {l.data(), r.data()};
    dsp.process(canali, canali, 2, 4);
    verifica(l[0] > 0.0f && l[0] < 0.5f, "smoothing: primo campione intermedio");

    // nessun ingresso: silenzio, nessun crash
    std::vector<float> o(64, 9.0f);
    float* out[1] = {o.data()};
    dsp.process(nullptr, out, 1, 64);
    verifica(vicino(o[63], 0) && vicino(o[0], 0), "senza ingresso esce silenzio");

    std::printf(errori ? "\n%d ERRORI\n" : "\ntutto ok\n", errori);
    return errori ? EXIT_FAILURE : EXIT_SUCCESS;
}
