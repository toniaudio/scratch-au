// Test del motore audio, senza Android:
//   g++ -std=c++17 -Iengine tests/*.cpp engine/*.cpp -o test_motore -lpthread && ./test_motore
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "Analisi.h"
#include "Motore.h"

using namespace djaaay;

static int errori = 0;

static void verifica(bool ok, const char* cosa) {
    std::printf("%s %s\n", ok ? "ok  " : "ERR ", cosa);
    if (!ok) ++errori;
}

// Brano di prova: sinusoide stereo a `hz`, `secondi` di durata.
static Traccia* sinusoide(double hz, double secondi, double sr = 44100.0, float ampiezza = 0.5f) {
    auto* t = new Traccia();
    t->sampleRate = sr;
    const int64_t n = static_cast<int64_t>(secondi * sr);
    std::vector<int16_t> buf(static_cast<size_t>(n) * 2);
    for (int64_t i = 0; i < n; ++i) {
        const auto v = static_cast<int16_t>(std::lround(ampiezza * 32767.0 * std::sin(2.0 * M_PI * hz * i / sr)));
        buf[static_cast<size_t>(i) * 2] = v;
        buf[static_cast<size_t>(i) * 2 + 1] = v;
    }
    t->aggiungi(buf.data(), n, 2);
    return t;
}

static ComandoDeck cmd(ComandoDeck::Tipo tipo, double a = 0.0, double b = 0.0) {
    ComandoDeck c;
    c.tipo = tipo;
    c.a = a;
    c.b = b;
    return c;
}

// Fa girare il motore per `secondi`; restituisce il picco dell'uscita e segnala NaN.
struct Esito {
    float picco = 0.0f;
    bool nan = false;
};
static Esito gira(Motore& m, double secondi, int blocco = 192) {
    Esito e;
    std::vector<float> out(static_cast<size_t>(blocco) * 2);
    const int64_t totale = static_cast<int64_t>(secondi * m.sampleRate());
    for (int64_t fatti = 0; fatti < totale; fatti += blocco) {
        m.render(out.data(), blocco);
        for (float v : out) {
            if (!std::isfinite(v)) e.nan = true;
            e.picco = std::max(e.picco, std::fabs(v));
        }
    }
    return e;
}

static bool vicino(double a, double b, double tol) { return std::fabs(a - b) <= tol; }

static void testDeck() {
    Motore m;
    m.prepara(48000.0);
    Deck& d = m.deck[0];
    d.avvioSecondi = 0.1f;
    d.frenataSecondi = 0.3f;
    d.carica(sinusoide(440.0, 20.0));
    m.mixer.crossfader = 0.0f;  // tutto sul deck A

    gira(m, 0.05);
    verifica(vicino(d.durataSecondi(), 20.0, 0.01), "durata della traccia letta dal deck");
    verifica(!d.inPlay() && d.velocita() == 0.0f, "dopo il caricamento il deck è fermo");

    d.invia(cmd(ComandoDeck::Play, 1.0));
    Esito e = gira(m, 1.0);
    verifica(d.inPlay(), "Play avvia il motore");
    verifica(vicino(d.velocita(), 1.0, 1e-3), "a regime la velocità è quella normale");
    verifica(vicino(d.posizioneSecondi(), 1.0 - 0.05, 0.08), "in un secondo si avanza di circa un secondo (meno l'avvio)");
    verifica(e.picco > 0.2f && !e.nan, "si sente il brano, nessun NaN");

    d.pitch = 0.08f;
    gira(m, 0.5);
    verifica(vicino(d.velocita(), 1.08, 2e-3), "pitch +8% → velocità 1,08");
    d.pitch = 0.0f;
    gira(m, 0.5);

    d.invia(cmd(ComandoDeck::Play, 0.0));
    gira(m, 0.15);
    const float aMeta = d.velocita();
    verifica(aMeta > 0.2f && aMeta < 0.8f, "la frenata rallenta gradualmente (a metà tempo velocità intermedia)");
    gira(m, 0.3);
    verifica(d.velocita() == 0.0f, "dopo il tempo di frenata il disco è fermo");

    // Dito sul disco: tenendo fermo il dito il disco si ferma anche col motore acceso
    d.invia(cmd(ComandoDeck::Play, 1.0));
    gira(m, 0.5);
    d.invia(cmd(ComandoDeck::ToccoInizio));
    gira(m, 0.05);
    verifica(std::fabs(d.velocita()) < 0.05f, "dito fermo sul disco: il disco si ferma");

    // Il disco segue il dito: spostamento di +0,5 s in 0,25 s
    const double partenza = d.posizioneSecondi();
    for (int i = 1; i <= 25; ++i) {
        d.invia(cmd(ComandoDeck::ToccoSposta, 0.5 * i / 25.0));
        gira(m, 0.01);
    }
    gira(m, 0.05);
    verifica(vicino(d.posizioneSecondi() - partenza, 0.5, 0.02), "il disco segue il dito in avanti");
    for (int i = 1; i <= 25; ++i) {
        d.invia(cmd(ComandoDeck::ToccoSposta, 0.5 - 1.0 * i / 25.0));
        gira(m, 0.01);
    }
    gira(m, 0.05);
    verifica(vicino(d.posizioneSecondi() - partenza, -0.5, 0.02), "e anche all'indietro");

    // Rilascio: torna alla velocità del motore con la frizione
    d.frizione = 1.0f;  // riparte subito
    d.invia(cmd(ComandoDeck::ToccoFine));
    gira(m, 0.15);
    verifica(vicino(d.velocita(), 1.0, 0.01), "frizione alta: dopo il rilascio riparte quasi subito");

    d.frizione = 0.0f;  // scivola a lungo
    d.invia(cmd(ComandoDeck::ToccoInizio));
    gira(m, 0.05);
    d.invia(cmd(ComandoDeck::ToccoFine));
    gira(m, 0.15);
    const float lenta = d.velocita();
    verifica(lenta > 0.05f && lenta < 0.5f, "frizione bassa: dopo 0,15 s è ancora lento");
    gira(m, 3.0);
    verifica(vicino(d.velocita(), 1.0, 0.01), "frizione bassa: alla fine torna alla velocità normale");
}

static void testCueLoop() {
    Motore m;
    m.prepara(48000.0);
    Deck& d = m.deck[0];
    d.avvioSecondi = 0.0f;
    d.frenataSecondi = 0.0f;
    d.carica(sinusoide(440.0, 30.0));
    gira(m, 0.01);

    d.invia(cmd(ComandoDeck::Vai, 5.0));
    d.invia(cmd(ComandoDeck::CueGiu));
    gira(m, 0.5);
    verifica(vicino(d.cueSecondi(), 5.0, 1e-6), "CUE da fermo fissa il punto di cue");
    verifica(d.inPlay() && d.posizioneSecondi() > 5.3, "tenendo premuto CUE si ascolta da lì");
    d.invia(cmd(ComandoDeck::CueSu));
    gira(m, 0.05);
    verifica(!d.inPlay() && vicino(d.posizioneSecondi(), 5.0, 1e-3), "rilasciando CUE si torna al cue e ci si ferma");

    d.invia(cmd(ComandoDeck::Play, 1.0));
    gira(m, 1.0);
    d.invia(cmd(ComandoDeck::CueGiu));
    d.invia(cmd(ComandoDeck::CueSu));
    gira(m, 0.05);
    verifica(!d.inPlay() && vicino(d.posizioneSecondi(), 5.0, 1e-3), "CUE durante il play: torna al cue e si ferma");

    d.invia(cmd(ComandoDeck::Loop, 5.0, 6.0));
    d.invia(cmd(ComandoDeck::Play, 1.0));
    gira(m, 3.5);
    const double p = d.posizioneSecondi();
    verifica(p >= 5.0 && p < 6.0, "il loop di 1 s fa ripetere la stessa zona");
    d.invia(cmd(ComandoDeck::Loop, 0.0, 0.0));
    gira(m, 1.5);
    verifica(d.posizioneSecondi() > 6.0, "spento il loop si prosegue");

    d.invia(cmd(ComandoDeck::Vai, 29.5));
    gira(m, 1.0);
    verifica(!d.inPlay() && vicino(d.posizioneSecondi(), 30.0, 1e-3), "a fine brano il deck si ferma");
}

static void testRevisione() {
    // Play premuto mentre si tiene CUE: lasciando CUE si continua a suonare
    {
        Motore m;
        m.prepara(48000.0);
        Deck& d = m.deck[0];
        d.avvioSecondi = 0.0f;
        d.carica(sinusoide(440.0, 30.0));
        gira(m, 0.01);
        d.invia(cmd(ComandoDeck::CueGiu));
        gira(m, 0.2);
        d.invia(cmd(ComandoDeck::PlayPausa));
        d.invia(cmd(ComandoDeck::CueSu));
        gira(m, 0.3);
        verifica(d.inPlay() && d.posizioneSecondi() > 0.4, "Play mentre si tiene CUE: continua a suonare dopo CUE");
    }
    // Loop all'indietro: scratchando indietro dentro il loop si rientra dalla fine
    {
        Motore m;
        m.prepara(48000.0);
        Deck& d = m.deck[0];
        d.carica(sinusoide(440.0, 30.0));
        gira(m, 0.01);
        d.invia(cmd(ComandoDeck::Vai, 10.2));
        d.invia(cmd(ComandoDeck::Loop, 10.0, 11.0));
        d.invia(cmd(ComandoDeck::ToccoInizio));
        for (int i = 1; i <= 30; ++i) {
            d.invia(cmd(ComandoDeck::ToccoSposta, -0.5 * i / 30.0));
            gira(m, 0.01);
        }
        gira(m, 0.05);
        const double p = d.posizioneSecondi();
        verifica(p > 10.6 && p < 10.8, "scratch indietro oltre l'inizio del loop: si rientra dalla fine");
        d.invia(cmd(ComandoDeck::ToccoFine));
    }
    // Loop accorciato mentre si è oltre la nuova fine: si rientra allo stesso punto della battuta
    {
        Motore m;
        m.prepara(48000.0);
        Deck& d = m.deck[0];
        d.carica(sinusoide(440.0, 30.0));
        gira(m, 0.01);
        d.invia(cmd(ComandoDeck::Vai, 13.5));
        d.invia(cmd(ComandoDeck::Loop, 10.0, 14.0));
        d.invia(cmd(ComandoDeck::Loop, 10.0, 12.0));  // ◀: da 4 a 2
        gira(m, 0.01);
        verifica(vicino(d.posizioneSecondi(), 11.5, 0.01), "loop accorciato con le frecce: si rientra nel loop");
    }
    // Salto di posizione mentre il dito è sul disco: il disco resta dove è saltato
    {
        Motore m;
        m.prepara(48000.0);
        Deck& d = m.deck[0];
        d.carica(sinusoide(440.0, 30.0));
        gira(m, 0.01);
        d.invia(cmd(ComandoDeck::ToccoInizio));
        gira(m, 0.05);
        d.invia(cmd(ComandoDeck::Vai, 10.0));
        gira(m, 0.2);
        verifica(vicino(d.posizioneSecondi(), 10.0, 0.01), "salto durante lo scratch: il dito non riporta indietro");
    }
    // Nessun click sui salti: dissolvenza di ~2 ms
    {
        Motore m;
        m.prepara(48000.0);
        Deck& d = m.deck[0];
        d.avvioSecondi = 0.0f;
        d.carica(sinusoide(100.0, 30.0));
        m.mixer.crossfader = 0.0f;
        d.invia(cmd(ComandoDeck::Play, 1.0));
        gira(m, 0.5);
        std::vector<float> out(2 * 512);
        float salto = 0.0f, prec = 0.0f;
        bool primo = true;
        for (int blocco = 0; blocco < 20; ++blocco) {
            if (blocco == 10) d.invia(cmd(ComandoDeck::Vai, 7.0037));  // fase della sinusoide diversa
            m.render(out.data(), 512);
            for (int i = 0; i < 512; ++i) {
                if (!primo) salto = std::max(salto, std::fabs(out[2 * i] - prec));
                prec = out[2 * i];
                primo = false;
            }
        }
        char testo[80];
        std::snprintf(testo, sizeof testo, "salto di posizione senza click (salto massimo %.3f)", salto);
        verifica(salto < 0.03f, testo);
    }
    // Caricamento a pezzi veloce (prima ogni pezzo ricopiava tutto il brano)
    {
        Traccia t;
        std::vector<int16_t> pezzo(4096 * 2, 1000);
        for (int i = 0; i < 3229; ++i) t.aggiungi(pezzo.data(), 4096, 2);  // 5 minuti
        verifica(t.frame() == 3229LL * 4096 && t.pcm.capacity() < t.pcm.size() * 2 + 16,
                 "caricamento a pezzi: dimensione giusta, memoria non sprecata");
        Traccia mono;
        const int16_t m3[3] = {1, 2, 3};
        mono.aggiungi(m3, 3, 1);
        verifica(mono.frame() == 3 && mono.pcm[2] == 2 && mono.pcm[3] == 2, "un file mono diventa stereo");
    }
}

static void testScratchRegolare() {
    // Dito che si muove a velocità costante (aggiornamenti ogni 10 ms): il disco deve andare regolare, non a scatti
    Motore m;
    m.prepara(48000.0);
    Deck& d = m.deck[0];
    d.carica(sinusoide(440.0, 30.0));
    gira(m, 0.01);
    const double ancora = d.posizioneSecondi();  // punto in cui si appoggia il dito
    d.invia(cmd(ComandoDeck::ToccoInizio));
    float vmin = 1e9f, vmax = -1e9f;
    for (int i = 1; i <= 100; ++i) {
        d.invia(cmd(ComandoDeck::ToccoSposta, 2.0 * i / 100.0));  // 2 s di brano in 1 s
        gira(m, 0.005, 240);
        if (i > 20) {
            vmin = std::min(vmin, d.velocita());
            vmax = std::max(vmax, d.velocita());
        }
        gira(m, 0.005, 240);
    }
    char testo[96];
    std::snprintf(testo, sizeof testo, "scratch a velocità costante: disco regolare (%.2f…%.2f, atteso 2)", vmin, vmax);
    verifica(vmin > 1.9f && vmax < 2.1f, testo);

    // Dito veloce che si ferma di colpo (a 2,6 s dal punto d'appoggio): il disco si ferma lì, senza andare oltre
    const double inizio = ancora + 2.0;
    for (int i = 1; i <= 20; ++i) {
        d.invia(cmd(ComandoDeck::ToccoSposta, 2.0 + 0.6 * i / 20.0));  // 3 volte la velocità normale
        gira(m, 0.008, 192);
    }
    double massimo = 0.0;
    for (int i = 0; i < 40; ++i) {
        gira(m, 0.002, 96);
        massimo = std::max(massimo, d.posizioneSecondi() - inizio);
    }
    std::snprintf(testo, sizeof testo, "dito che si ferma: il disco non va oltre (massimo %.4f, fine %.4f s)", massimo,
                  d.posizioneSecondi() - inizio);
    verifica(massimo < 0.6 + 0.003 && vicino(d.posizioneSecondi() - inizio, 0.6, 0.002) && std::fabs(d.velocita()) < 0.01,
             testo);
    d.invia(cmd(ComandoDeck::ToccoFine));
}

static void testAntiAliasing() {
    // Un 15 kHz scratchato a 3 volte la velocità finirebbe a 45 kHz e si ripiegherebbe a ~3 kHz (fruscio metallico):
    // con l'anti-aliasing quasi non si sente.
    Motore m;
    m.prepara(48000.0);
    Deck& d = m.deck[0];
    d.carica(sinusoide(15000.0, 30.0, 44100.0, 0.5f));
    m.mixer.crossfader = 0.0f;
    m.mixer.canale[0].volume = 1.0f;
    m.mixer.master = 1.0f;
    gira(m, 0.01);
    d.invia(cmd(ComandoDeck::ToccoInizio));
    float picco = 0.0f;
    std::vector<float> out(2 * 240);
    for (int i = 1; i <= 100; ++i) {  // 3 secondi di brano in 1 secondo
        d.invia(cmd(ComandoDeck::ToccoSposta, 3.0 * i / 100.0));
        for (int b = 0; b < 2; ++b) {  // 2 × 240 frame = 10 ms per spostamento del dito
            m.render(out.data(), 240);
            if (i > 30)
                for (float v : out) picco = std::max(picco, std::fabs(v));
        }
    }
    char testo[96];
    std::snprintf(testo, sizeof testo, "scratch veloce: acuti ripiegati quasi spariti (%.3f su 0,5)", picco);
    verifica(picco < 0.08f, testo);
    d.invia(cmd(ComandoDeck::ToccoFine));
}

static void testMixer() {
    verifica(vicino(guadagniCrossfader(0.5f, kCurvaTaglio, false).a, 1.0, 1e-6) &&
                 vicino(guadagniCrossfader(0.5f, kCurvaTaglio, false).b, 1.0, 1e-6),
             "taglio: al centro entrambi pieni");
    verifica(vicino(guadagniCrossfader(0.0f, kCurvaTaglio, false).b, 0.0, 1e-6), "taglio: tutto a sinistra B muto");
    verifica(vicino(guadagniCrossfader(0.03f, kCurvaTaglio, false).b, 0.75, 1e-3), "taglio: B apre entro il 4%");
    verifica(vicino(guadagniCrossfader(0.0f, kCurvaTaglio, true).a, 0.0, 1e-6), "inverti scambia i lati");
    const Guadagni g = guadagniCrossfader(0.5f, kCurvaMorbida, false);
    verifica(vicino(g.a * g.a + g.b * g.b, 1.0, 1e-5), "morbida: potenza costante");
    verifica(vicino(guadagnoEQ(0.5f), 1.0, 1e-6) && guadagnoEQ(0.0f) == 0.0f && vicino(guadagnoEQ(1.0f), 2.0, 1e-6),
             "EQ: centro neutro, tutto a sinistra kill, tutto a destra +6 dB");

    // EQ: kill dei bassi su 80 Hz, kill degli alti su 6 kHz; neutro lascia passare
    struct Caso {
        double hz;
        int banda;  // 0 bassi, 2 alti
    };
    for (Caso c : {Caso{80.0, 0}, Caso{6000.0, 2}}) {
        Motore m;
        m.prepara(48000.0);
        m.deck[0].avvioSecondi = 0.0f;
        m.deck[0].carica(sinusoide(c.hz, 10.0));
        m.mixer.crossfader = 0.0f;
        m.mixer.canale[0].volume = 1.0f;
        m.mixer.master = 1.0f;
        m.deck[0].invia(cmd(ComandoDeck::Play, 1.0));
        gira(m, 0.5);
        const float neutro = gira(m, 0.5).picco;
        (c.banda == 0 ? m.mixer.canale[0].bassi : m.mixer.canale[0].alti) = 0.0f;
        gira(m, 0.5);
        const float kill = gira(m, 0.5).picco;
        char testo[96];
        std::snprintf(testo, sizeof testo, "EQ: neutro passa (%.2f), kill a %.0f Hz toglie (%.3f)", neutro, c.hz, kill);
        verifica(neutro > 0.45f && kill < 0.1f * neutro, testo);
    }

    // EQ al centro: volume invariato anche sulle frequenze di confine tra le bande
    for (double hz : {250.0, 1000.0, 2500.0}) {
        Motore m;
        m.prepara(48000.0);
        m.deck[0].avvioSecondi = 0.0f;
        m.deck[0].carica(sinusoide(hz, 10.0));
        m.mixer.crossfader = 0.0f;
        m.mixer.canale[0].volume = 1.0f;
        m.mixer.master = 1.0f;
        m.deck[0].invia(cmd(ComandoDeck::Play, 1.0));
        gira(m, 0.5);
        const float p = gira(m, 0.5).picco;
        char testo[80];
        std::snprintf(testo, sizeof testo, "EQ al centro: %.0f Hz passa invariato (%.3f)", hz, p);
        verifica(vicino(p, 0.5, 0.02), testo);
    }

    // Filtro: tutto a sinistra (passa-basso) toglie un 5 kHz, tutto a destra (passa-alto) toglie un 60 Hz
    for (double hz : {5000.0, 60.0}) {
        Motore m;
        m.prepara(48000.0);
        m.deck[0].avvioSecondi = 0.0f;
        m.deck[0].carica(sinusoide(hz, 10.0));
        m.mixer.crossfader = 0.0f;
        m.mixer.canale[0].volume = 1.0f;
        m.mixer.master = 1.0f;
        m.deck[0].invia(cmd(ComandoDeck::Play, 1.0));
        gira(m, 0.5);
        m.mixer.canale[0].filtro = hz > 1000.0 ? -1.0f : 1.0f;
        gira(m, 0.5);
        const float filtrato = gira(m, 0.5).picco;
        char testo[80];
        std::snprintf(testo, sizeof testo, "filtro %s toglie %.0f Hz (%.3f)", hz > 1000.0 ? "LP" : "HP", hz, filtrato);
        verifica(filtrato < 0.05f, testo);
    }

    // Filtro che salta da passa-basso a passa-alto (es. da MIDI): niente click
    {
        Motore m;
        m.prepara(48000.0);
        m.deck[0].avvioSecondi = 0.0f;
        m.deck[0].carica(sinusoide(200.0, 20.0, 48000.0, 0.37f));
        m.mixer.crossfader = 0.0f;
        m.mixer.canale[0].volume = 1.0f;
        m.mixer.master = 1.0f;
        m.mixer.canale[0].filtro = -0.3f;
        m.deck[0].invia(cmd(ComandoDeck::Play, 1.0));
        std::vector<float> out(2 * 256);
        float prec = 0.0f, normale = 0.0f, cambio = 0.0f;
        for (int k = 0; k < 400; ++k) {
            if (k == 300) m.mixer.canale[0].filtro = 0.3f;
            m.render(out.data(), 256);
            for (int i = 0; i < 256; ++i) {
                const float d = std::fabs(out[2 * i] - prec);
                prec = out[2 * i];
                if (k > 200 && k < 300) normale = std::max(normale, d);
                if (k >= 300) cambio = std::max(cambio, d);
            }
        }
        char testo[96];
        std::snprintf(testo, sizeof testo, "filtro da LP a HP senza click (%.4f, normale %.4f)", cambio, normale);
        verifica(cambio < 1.5f * normale, testo);
    }

    // Due deck a tutto volume con gain al massimo: il limitatore tiene l'uscita entro ±1
    Motore m;
    m.prepara(48000.0);
    for (auto& d : m.deck) {
        d.avvioSecondi = 0.0f;
        d.carica(sinusoide(100.0, 5.0, 44100.0, 0.99f));
        d.invia(cmd(ComandoDeck::Play, 1.0));
    }
    for (auto& c : m.mixer.canale) {
        c.gain = 1.0f;
        c.volume = 1.0f;
        c.bassi = 1.0f;
    }
    m.mixer.master = 1.0f;
    const Esito e = gira(m, 1.0);
    verifica(e.picco <= 1.0f && !e.nan, "limitatore: niente oltre ±1 e nessun NaN");
    verifica(m.mixer.livelloL.load() > 0.5f, "il meter del master si muove");
}

static void testWaveform() {
    Traccia* t = sinusoide(100.0, 3.0);
    const Waveform w = analizzaWaveform(*t);
    const size_t attese = static_cast<size_t>((t->frame() + 63) / 64);
    verifica(!w.livelli.empty() && w.livelli[0].colonne() == attese, "waveform: una colonna ogni 64 frame");
    verifica(w.livelli.back().colonne() <= 256 && w.livelli.size() > 3, "waveform: livelli di zoom fino a ≤256 colonne");
    const size_t c = attese / 2;
    verifica(w.livelli[0].bassi[c] > 150 && w.livelli[0].alti[c] < 40, "waveform: un 100 Hz è nei bassi, non negli alti");
    delete t;
}

// Frequenza dominante stimata contando i passaggi per lo zero (canale sinistro)
static double frequenza(const std::vector<float>& stereo, double sr) {
    int passaggi = 0;
    for (size_t i = 2; i < stereo.size(); i += 2)
        if (stereo[i - 2] < 0.0f && stereo[i] >= 0.0f) ++passaggi;
    return passaggi * sr / (stereo.size() / 2.0);
}

// Brano di silenzio con un "colpo" (5 ms di 2 kHz) ogni mezzo secondo: serve a misurare l'allineamento nel tempo
static Traccia* colpi(double secondi, double sr = 48000.0) {
    auto* t = new Traccia();
    t->sampleRate = sr;
    const int64_t n = static_cast<int64_t>(secondi * sr);
    std::vector<int16_t> buf(static_cast<size_t>(n) * 2, 0);
    for (int64_t i = 0; i < n; ++i) {
        const double inMezzo = std::fmod(i / sr, 0.5);
        if (inMezzo < 0.005) {
            const auto v = static_cast<int16_t>(16000 * std::sin(2.0 * M_PI * 2000.0 * i / sr));
            buf[static_cast<size_t>(i) * 2] = buf[static_cast<size_t>(i) * 2 + 1] = v;
        }
    }
    t->aggiungi(buf.data(), n, 2);
    return t;
}

static void testKeylock() {
    // 1) tono: pitch +8% su un 1000 Hz → senza keylock ~1080 Hz, con keylock ~1000 Hz
    for (bool key : {false, true}) {
        Motore m;
        m.prepara(48000.0);
        Deck& d = m.deck[0];
        d.avvioSecondi = 0.0f;
        d.carica(sinusoide(1000.0, 30.0, 48000.0, 0.5f));
        d.pitch = 0.08f;
        d.keylock = key;
        m.mixer.crossfader = 0.0f;
        m.mixer.canale[0].volume = 1.0f;
        m.mixer.master = 1.0f;
        d.invia(cmd(ComandoDeck::Play, 1.0));
        gira(m, 1.0);
        std::vector<float> out(2 * 48000);
        m.render(out.data(), 48000);
        const double f = frequenza(out, 48000.0);
        char testo[96];
        std::snprintf(testo, sizeof testo, "pitch +8%% %s keylock: %.0f Hz (atteso %s)", key ? "con" : "senza", f,
                      key ? "1000" : "1080");
        verifica(key ? vicino(f, 1000.0, 10.0) : vicino(f, 1080.0, 10.0), testo);
        if (key) verifica(d.keylockInUso(), "keylock in uso mentre il disco gira al motore");
        if (key) verifica(vicino(d.velocita(), 1.08, 1e-3), "con keylock la velocità resta +8%");
    }

    // 2) allineamento: il colpo deve uscire nello stesso istante con e senza keylock (ritardo compensato)
    long momento[2] = {-1, -1};
    for (int k = 0; k < 2; ++k) {
        Motore m;
        m.prepara(48000.0);
        Deck& d = m.deck[0];
        d.avvioSecondi = 0.0f;
        d.carica(colpi(20.0));
        d.keylock = (k == 1);
        m.mixer.crossfader = 0.0f;
        m.mixer.canale[0].volume = 1.0f;
        m.mixer.master = 1.0f;
        d.invia(cmd(ComandoDeck::Vai, 2.55));  // a deck fermo: la dissolvenza del salto è muta
        gira(m, 0.05);
        d.invia(cmd(ComandoDeck::Play, 1.0));
        std::vector<float> out(2 * 48000);
        m.render(out.data(), 48000);  // da 2,55 s: il colpo dei 3,0 s arriva dopo 0,45 s = 21600 campioni
        for (long i = 0; i < 48000 && momento[k] < 0; ++i)
            if (std::fabs(out[2 * i]) > 0.1f) momento[k] = i;
        if (k == 1) verifica(d.keylockInUso(), "keylock in uso durante la misura dell'allineamento");
    }
    char testo[112];
    std::snprintf(testo, sizeof testo, "keylock allineato alla puntina (colpo a %ld vs %ld campioni)", momento[1], momento[0]);
    verifica(std::labs(momento[0] - 21600) < 48 && std::labs(momento[1] - momento[0]) < 240, testo);

    // 3) accenderlo mentre suona: niente click; 4) dito sul disco: keylock da parte
    {
        Motore m;
        m.prepara(48000.0);
        Deck& d = m.deck[0];
        d.avvioSecondi = 0.0f;
        d.carica(sinusoide(200.0, 30.0, 48000.0, 0.4f));
        d.pitch = 0.05f;
        m.mixer.crossfader = 0.0f;
        m.mixer.canale[0].volume = 1.0f;
        m.mixer.master = 1.0f;
        d.invia(cmd(ComandoDeck::Play, 1.0));
        gira(m, 0.5);
        std::vector<float> out(2 * 256);
        float prec = 0.0f, salto = 0.0f;
        for (int b = 0; b < 400; ++b) {
            if (b == 50) d.keylock = true;
            m.render(out.data(), 256);
            for (int i = 0; i < 256; ++i) {
                if (b > 0 || i > 0) salto = std::max(salto, std::fabs(out[2 * i] - prec));
                prec = out[2 * i];
            }
        }
        std::snprintf(testo, sizeof testo, "accendere il keylock mentre suona: niente click (%.4f)", salto);
        verifica(salto < 0.03f && d.keylockInUso(), testo);
        d.invia(cmd(ComandoDeck::ToccoInizio));
        gira(m, 0.05);
        verifica(!d.keylockInUso(), "dito sul disco: il keylock si fa da parte (scratch naturale)");
        d.invia(cmd(ComandoDeck::ToccoFine));
        gira(m, 1.5);
        verifica(d.keylockInUso(), "rilasciato il disco il keylock torna");
    }

    // 5) loop con keylock: continua a suonare, niente buchi
    {
        Motore m;
        m.prepara(48000.0);
        Deck& d = m.deck[0];
        d.avvioSecondi = 0.0f;
        d.carica(sinusoide(300.0, 30.0, 48000.0, 0.4f));
        d.keylock = true;
        d.pitch = 0.04f;
        m.mixer.crossfader = 0.0f;
        m.mixer.canale[0].volume = 1.0f;
        m.mixer.master = 1.0f;
        d.invia(cmd(ComandoDeck::Play, 1.0));
        gira(m, 1.0);
        d.invia(cmd(ComandoDeck::Loop, 2.0, 3.0));
        std::vector<float> out(2 * 480);
        float minimo = 1.0f;
        for (int b = 0; b < 300; ++b) {  // 3 secondi
            m.render(out.data(), 480);
            float p = 0.0f;
            for (float v : out) p = std::max(p, std::fabs(v));
            if (b > 20) minimo = std::min(minimo, p);
        }
        const double pos = d.posizioneSecondi();
        std::snprintf(testo, sizeof testo, "loop con keylock: resta nel loop (%.2f s) e suona sempre (minimo %.2f)", pos, minimo);
        verifica(pos >= 2.0 && pos < 3.0 && minimo > 0.2f, testo);
    }
}

static std::string fileTemporaneo(const char* nome) {
    const char* dir = std::getenv("TMPDIR");
    return std::string(dir ? dir : "/tmp") + "/" + nome;
}

static void testRegistrazione() {
    Motore m;
    m.prepara(48000.0);
    m.deck[0].avvioSecondi = 0.0f;
    m.deck[0].carica(sinusoide(440.0, 30.0));
    m.mixer.crossfader = 0.0f;
    m.deck[0].invia(cmd(ComandoDeck::Play, 1.0));
    gira(m, 0.2);

    const std::string percorso = fileTemporaneo("dvs_test_rec.wav");
    verifica(m.registratore.avviaSuFile(percorso.c_str(), 48000), "REC: si avvia");
    verifica(!m.registratore.avviaSuFile(percorso.c_str(), 48000), "REC: non si avvia due volte");
    // 2 secondi di mix, tenendo una copia di quello che esce
    std::vector<float> atteso;
    std::vector<float> out(2 * 192);
    for (int i = 0; i < 500; ++i) {
        m.render(out.data(), 192);
        atteso.insert(atteso.end(), out.begin(), out.end());
    }
    m.registratore.ferma();
    verifica(!m.registratore.attiva() && m.registratore.framePersi() == 0, "REC: fermata, nessun frame perso");

    FILE* f = std::fopen(percorso.c_str(), "rb");
    std::vector<unsigned char> wav;
    if (f) {
        unsigned char buf[65536];
        size_t n;
        while ((n = std::fread(buf, 1, sizeof buf, f)) > 0) wav.insert(wav.end(), buf, buf + n);
        std::fclose(f);
    }
    auto u32 = [&](size_t o) { return wav[o] | (wav[o + 1] << 8) | (wav[o + 2] << 16) | (uint32_t(wav[o + 3]) << 24); };
    const bool intestazioneOk = wav.size() > 44 && std::memcmp(wav.data(), "RIFF", 4) == 0 &&
                                std::memcmp(wav.data() + 8, "WAVE", 4) == 0 && u32(24) == 48000 &&
                                u32(40) == wav.size() - 44 && u32(4) == wav.size() - 8;
    verifica(intestazioneOk, "REC: file WAV valido con le dimensioni giuste");
    const size_t frame = (wav.size() - 44) / 4;
    verifica(frame == atteso.size() / 2, "REC: durata giusta (2 secondi)");
    float diff = 0.0f;
    for (size_t i = 0; i < std::min(frame * 2, atteso.size()); ++i) {
        const int16_t v = static_cast<int16_t>(wav[44 + 2 * i] | (wav[44 + 2 * i + 1] << 8));
        diff = std::max(diff, std::fabs(v / 32767.0f - atteso[i]));
    }
    verifica(diff < 1e-3f, "REC: il file contiene esattamente il mix");
    std::remove(percorso.c_str());
}

static void testCacheWaveform() {
    Traccia* t = sinusoide(3000.0, 4.0);
    const Waveform w = analizzaWaveform(*t);
    const std::string percorso = fileTemporaneo("dvs_test.wavef");
    Waveform letta;
    const bool ok = salvaWaveform(w, percorso.c_str()) && caricaWaveform(percorso.c_str(), letta);
    bool uguale = ok && letta.livelli.size() == w.livelli.size();
    for (size_t i = 0; uguale && i < w.livelli.size(); ++i)
        uguale = letta.livelli[i].bassi == w.livelli[i].bassi && letta.livelli[i].medi == w.livelli[i].medi &&
                 letta.livelli[i].alti == w.livelli[i].alti && letta.livelli[i].framePerColonna == w.livelli[i].framePerColonna;
    verifica(uguale, "waveform salvata e ricaricata identica (tutti i livelli)");
    // file rovinato: rifiutato senza danni
    if (FILE* f = std::fopen(percorso.c_str(), "r+b")) {
        std::fseek(f, 0, SEEK_SET);
        std::fputc('X', f);
        std::fclose(f);
    }
    Waveform rovinata;
    verifica(!caricaWaveform(percorso.c_str(), rovinata), "waveform: file rovinato rifiutato");
    verifica(!caricaWaveform(fileTemporaneo("non_esiste.wavef").c_str(), rovinata), "waveform: file mancante rifiutato");
    std::remove(percorso.c_str());
    delete t;
}

int main() {
    testDeck();
    testCueLoop();
    testRevisione();
    testScratchRegolare();
    testAntiAliasing();
    testMixer();
    testWaveform();
    testKeylock();
    testCacheWaveform();
    testRegistrazione();
    std::printf(errori ? "\n%d ERRORI\n" : "\ntutto ok\n", errori);
    return errori ? EXIT_FAILURE : EXIT_SUCCESS;
}
