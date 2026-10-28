// Ponte tra Kotlin e il motore audio, più l'uscita audio con Oboe.
#include <jni.h>
#include <android/log.h>
#include <oboe/Oboe.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include <unistd.h>

#include "Analisi.h"
#include "Motore.h"

namespace {

const char* kTag = "DVS";

class Uscita : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    bool apri() {
        std::lock_guard<std::mutex> lock(mutex_);
        // già in funzione (es. l'Activity è stata ricreata): non si riapre, la musica non si interrompe
        if (stream_ && stream_->getState() == oboe::StreamState::Started) return true;
        chiudiSenzaLock();
        oboe::AudioStreamBuilder b;
        b.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(oboe::ChannelCount::Stereo)
            ->setChannelConversionAllowed(true)
            ->setDataCallback(this)
            ->setErrorCallback(this);
        const oboe::Result r = b.openStream(stream_);
        if (r != oboe::Result::OK) {
            __android_log_print(ANDROID_LOG_ERROR, kTag, "apertura audio fallita: %s", oboe::convertToText(r));
            stream_.reset();
            return false;
        }
        applicaBuffer();
        // il flusso non è ancora partito: si può preparare il motore senza disturbare il thread audio
        motore.prepara(stream_->getSampleRate());
        caricoPicco_.store(0.0f);
        __android_log_print(ANDROID_LOG_INFO, kTag, "audio %d Hz, burst %d", stream_->getSampleRate(),
                            stream_->getFramesPerBurst());
        return stream_->requestStart() == oboe::Result::OK;
    }

    void chiudi() {
        std::lock_guard<std::mutex> lock(mutex_);
        chiudiSenzaLock();
    }

    int sampleRate() {
        std::lock_guard<std::mutex> lock(mutex_);
        return stream_ ? stream_->getSampleRate() : 0;
    }

    void impostaBuffer(int multiplo) {
        std::lock_guard<std::mutex> lock(mutex_);
        multiplo_ = std::clamp(multiplo, 1, 8);
        applicaBuffer();
    }

    std::string diagnostica() {
        std::lock_guard<std::mutex> lock(mutex_);
        for (auto& d : motore.deck) d.raccogliTracceVecchie();  // libera i brani sostituiti
        if (!stream_) return "Audio non aperto";
        const int sr = stream_->getSampleRate();
        const int burst = stream_->getFramesPerBurst();
        const int buffer = stream_->getBufferSizeInFrames();
        const auto latenza = stream_->calculateLatencyMillis();
        const auto xrun = stream_->getXRunCount();
        const std::string testoLatenza =
            latenza ? std::to_string(static_cast<int>(std::lround(latenza.value()))) + " ms" : "non disponibile";
        const std::string testoXrun = xrun ? std::to_string(xrun.value()) : "non disponibile";
        const std::string testoRec =
            motore.registratore.attiva()
                ? std::to_string(static_cast<int>(motore.registratore.secondi())) + " s, frame persi " +
                      std::to_string(motore.registratore.framePersi())
                : "spenta";
        char testo[512];
        std::snprintf(testo, sizeof testo,
                      "Frequenza: %d Hz\n"
                      "Buffer: %d frame (%d × %d) = %.1f ms\n"
                      "Ritardo totale stimato: %s\n"
                      "Interruzioni del suono: %s\n"
                      "Carico audio: %.0f%% (picco %.0f%%)\n"
                      "Modo: %s, %s\n"
                      "Keylock deck A: %s\n"
                      "REC: %s",
                      sr, buffer, multiplo_, burst, 1000.0 * buffer / sr, testoLatenza.c_str(), testoXrun.c_str(),
                      100.0f * caricoUltimo_.load(), 100.0f * caricoPicco_.exchange(0.0f),
                      stream_->getAudioApi() == oboe::AudioApi::AAudio ? "AAudio" : "OpenSL ES",
                      stream_->getSharingMode() == oboe::SharingMode::Exclusive ? "esclusivo" : "condiviso",
                      !motore.deck[0].keylock.load() ? "spento" : motore.deck[0].keylockInUso() ? "in uso" : "acceso, in attesa",
                      testoRec.c_str());
        return testo;
    }

    // Brano di prova: 8 secondi di La a 440 Hz nel deck A, in loop (8 s = numero intero di periodi, niente salti)
    void tonoProva(bool acceso) {
        if (!tonoCaricato_) {
            auto* t = new djaaay::Traccia();
            t->sampleRate = 44100.0;
            const int64_t n = 8 * 44100;
            std::vector<int16_t> pcm(static_cast<size_t>(n) * 2);
            for (int64_t i = 0; i < n; ++i) {
                const auto v = static_cast<int16_t>(std::lround(12000.0 * std::sin(2.0 * M_PI * 440.0 * i / 44100.0)));
                pcm[static_cast<size_t>(i) * 2] = pcm[static_cast<size_t>(i) * 2 + 1] = v;
            }
            t->aggiungi(pcm.data(), n, 2);
            motore.deck[0].carica(t);
            djaaay::ComandoDeck loop;
            loop.tipo = djaaay::ComandoDeck::Loop;
            loop.a = 0.0;
            loop.b = 8.0;
            motore.deck[0].invia(loop);
            tonoCaricato_ = true;
        }
        djaaay::ComandoDeck play;
        play.tipo = djaaay::ComandoDeck::Play;
        play.a = acceso ? 1.0 : 0.0;
        motore.deck[0].invia(play);
    }

    bool avviaRegistrazione(int fd) {
        const int sr = sampleRate();
        if (sr <= 0) {
            close(fd);  // audio spento: il file descriptor ricevuto va comunque chiuso
            return false;
        }
        return motore.registratore.avvia(fd, sr);
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* s, void* dati, int32_t frame) override {
        const auto inizio = std::chrono::steady_clock::now();
        motore.render(static_cast<float*>(dati), frame);
        // carico = tempo di calcolo / durata del blocco (vicino al 100% il suono si interrompe)
        const double usato = std::chrono::duration<double>(std::chrono::steady_clock::now() - inizio).count();
        const float carico = static_cast<float>(usato * s->getSampleRate() / std::max(1, frame));
        caricoUltimo_.store(carico, std::memory_order_relaxed);
        if (carico > caricoPicco_.load(std::memory_order_relaxed)) caricoPicco_.store(carico, std::memory_order_relaxed);
        return oboe::DataCallbackResult::Continue;
    }

    // Cuffie/altoparlante/scheda USB cambiati: Oboe ha già chiuso il flusso, lo riapriamo sul nuovo dispositivo
    void onErrorAfterClose(oboe::AudioStream* chiuso, oboe::Result errore) override {
        if (errore != oboe::Result::ErrorDisconnected) return;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (stream_.get() != chiuso) return;  // nel frattempo l'app l'ha chiuso o riaperto lei
            stream_.reset();
        }
        apri();
    }

    djaaay::Motore motore;

private:
    void chiudiSenzaLock() {
        if (stream_) {
            stream_->stop();
            stream_->close();
            stream_.reset();
        }
    }

    void applicaBuffer() {
        if (stream_) stream_->setBufferSizeInFrames(stream_->getFramesPerBurst() * multiplo_);
    }

    std::shared_ptr<oboe::AudioStream> stream_;
    std::mutex mutex_;
    int multiplo_ = 2;
    bool tonoCaricato_ = false;
    std::atomic<float> caricoUltimo_{0.0f};
    std::atomic<float> caricoPicco_{0.0f};
};

Uscita* uscita() {
    static Uscita u;
    return &u;
}

// Ingresso della scheda audio per il DVS: il giradischi col vinile timecode. I campioni (due canali scelti)
// vanno nell'anello del motore, che li decodifica nel thread di uscita.
class IngressoDvs : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    // `primo` = primo canale (0 = ingressi 1-2, 2 = ingressi 3-4). Restituisce "" se va, altrimenti il problema.
    std::string apri(int deviceId, int primo) {
        std::lock_guard<std::mutex> lock(mutex_);
        chiudiSenzaLock();
        const int sr = uscita()->sampleRate();
        if (sr <= 0) return "l'audio di uscita non è attivo";
        primo_ = primo;
        for (int tentativo = 0; tentativo < 2; ++tentativo) {
            oboe::AudioStreamBuilder b;
            b.setDirection(oboe::Direction::Input)
                ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
                ->setSharingMode(tentativo == 0 ? oboe::SharingMode::Exclusive : oboe::SharingMode::Shared)
                ->setFormat(oboe::AudioFormat::Float)
                ->setChannelCount(primo + 2)
                ->setSampleRate(sr)  // stessa frequenza dell'uscita: il timecode si legge a quella
                ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
                ->setInputPreset(oboe::InputPreset::Unprocessed)  // niente filtri "voce": il segnale deve arrivare intatto
                ->setDataCallback(this)
                ->setErrorCallback(this);
            if (deviceId > 0) b.setDeviceId(deviceId);
            const oboe::Result r = b.openStream(stream_);
            if (r != oboe::Result::OK) {
                stream_.reset();
                if (tentativo == 1) return std::string("la scheda non si apre (") + oboe::convertToText(r) + ")";
                continue;
            }
            if (stream_->getChannelCount() < primo + 2) {
                chiudiSenzaLock();
                return "la scheda non ha gli ingressi 3-4";
            }
            if (stream_->requestStart() != oboe::Result::OK) {
                chiudiSenzaLock();
                if (tentativo == 1) return "la scheda non parte";
                continue;
            }
            __android_log_print(ANDROID_LOG_INFO, kTag, "ingresso DVS %d Hz, %d canali", stream_->getSampleRate(),
                                stream_->getChannelCount());
            return "";
        }
        return "la scheda non si apre";
    }

    void chiudi() {
        std::lock_guard<std::mutex> lock(mutex_);
        chiudiSenzaLock();
    }

    bool aperto() {
        std::lock_guard<std::mutex> lock(mutex_);
        return stream_ != nullptr;
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* s, void* dati, int32_t frame) override {
        uscita()->motore.ingresso.scrivi(static_cast<const float*>(dati), frame, s->getChannelCount(), primo_);
        return oboe::DataCallbackResult::Continue;
    }

    // scheda scollegata: l'ingresso si chiude (l'app lo vede dallo stato e lo dice)
    void onErrorAfterClose(oboe::AudioStream* chiuso, oboe::Result) override {
        // se l'app sta già chiudendo (mutex occupato) non serve fare niente: e si evita di aspettarsi a vicenda
        std::unique_lock<std::mutex> lock(mutex_, std::try_to_lock);
        if (lock.owns_lock() && stream_.get() == chiuso) stream_.reset();
    }

private:
    void chiudiSenzaLock() {
        if (stream_) {
            stream_->stop();
            stream_->close();
            stream_.reset();
        }
    }

    std::shared_ptr<oboe::AudioStream> stream_;
    std::mutex mutex_;
    int primo_ = 0;
};

IngressoDvs* ingressoDvs() {
    static IngressoDvs i;
    return &i;
}

// Un brano in caricamento: si riempie a pezzi dal decodificatore Android, poi si analizza e si passa a un deck.
struct Caricamento {
    djaaay::Traccia* traccia = new djaaay::Traccia();
    djaaay::Waveform waveform;
    djaaay::Tempo tempo;
    ~Caricamento() { delete traccia; }
};

Caricamento* caricamento(jlong h) { return reinterpret_cast<Caricamento*>(h); }

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL Java_com_toniaudio_djaaay_Motore_avvia(JNIEnv*, jobject) {
    return uscita()->apri() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_toniaudio_djaaay_Motore_ferma(JNIEnv*, jobject) {
    ingressoDvs()->chiudi();
    uscita()->motore.registratore.ferma();
    uscita()->chiudi();
}

JNIEXPORT jint JNICALL Java_com_toniaudio_djaaay_Motore_sampleRate(JNIEnv*, jobject) {
    return uscita()->sampleRate();
}

JNIEXPORT void JNICALL Java_com_toniaudio_djaaay_Motore_tonoProva(JNIEnv*, jobject, jboolean acceso) {
    uscita()->tonoProva(acceso == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_com_toniaudio_djaaay_Motore_keylock(JNIEnv*, jobject, jint deck, jboolean acceso) {
    if (deck >= 0 && deck < 2) uscita()->motore.deck[deck].keylock.store(acceso == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_com_toniaudio_djaaay_Motore_pitch(JNIEnv*, jobject, jint deck, jfloat valore) {
    if (deck >= 0 && deck < 2) uscita()->motore.deck[deck].pitch.store(std::clamp(valore, -0.5f, 0.5f));
}

JNIEXPORT void JNICALL Java_com_toniaudio_djaaay_Motore_impostaBuffer(JNIEnv*, jobject, jint multiplo) {
    uscita()->impostaBuffer(multiplo);
}

JNIEXPORT jstring JNICALL Java_com_toniaudio_djaaay_Motore_diagnostica(JNIEnv* env, jobject) {
    return env->NewStringUTF(uscita()->diagnostica().c_str());
}

JNIEXPORT jboolean JNICALL Java_com_toniaudio_djaaay_Motore_avviaRegistrazione(JNIEnv*, jobject, jint fd) {
    return uscita()->avviaRegistrazione(fd) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_toniaudio_djaaay_Motore_fermaRegistrazione(JNIEnv*, jobject) {
    uscita()->motore.registratore.ferma();
}

JNIEXPORT jboolean JNICALL Java_com_toniaudio_djaaay_Motore_registrazioneAttiva(JNIEnv*, jobject) {
    return uscita()->motore.registratore.attiva() ? JNI_TRUE : JNI_FALSE;
}

// ---------------------------------------------------------------------------------------------------------------
// Caricamento dei brani

JNIEXPORT jlong JNICALL Java_com_toniaudio_djaaay_Motore_nuovaTraccia(JNIEnv*, jobject, jint sampleRate, jlong frameAttesi) {
    auto* c = new Caricamento();
    c->traccia->sampleRate = sampleRate > 0 ? sampleRate : 44100;
    c->traccia->riserva(frameAttesi);
    return reinterpret_cast<jlong>(c);
}

// PCM dal decodificatore: 16 bit (formatoFloat = false) o float, `canali` canali interleaved, in un ByteBuffer diretto
JNIEXPORT jboolean JNICALL Java_com_toniaudio_djaaay_Motore_aggiungiPcm(JNIEnv* env, jobject, jlong h, jobject buffer,
                                                                        jint offset, jint byte, jint canali,
                                                                        jboolean formatoFloat) {
    auto* dati = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (h == 0 || dati == nullptr || canali <= 0 || byte <= 0) return JNI_FALSE;
    dati += offset;
    if (formatoFloat == JNI_TRUE) {
        const int64_t frame = byte / (4 * canali);
        const auto* f = reinterpret_cast<const float*>(dati);
        std::vector<int16_t> pcm(static_cast<size_t>(frame * canali));
        for (size_t i = 0; i < pcm.size(); ++i)
            pcm[i] = static_cast<int16_t>(std::lround(std::clamp(f[i], -1.0f, 1.0f) * 32767.0f));
        caricamento(h)->traccia->aggiungi(pcm.data(), frame, canali);
    } else {
        const int64_t frame = byte / (2 * canali);
        caricamento(h)->traccia->aggiungi(reinterpret_cast<const int16_t*>(dati), frame, canali);
    }
    return JNI_TRUE;
}

// La frequenza vera si sa solo dal primo pezzo decodificato (es. AAC con SBR): si corregge qui
JNIEXPORT void JNICALL Java_com_toniaudio_djaaay_Motore_frequenzaTraccia(JNIEnv*, jobject, jlong h, jint sampleRate) {
    if (h != 0 && sampleRate > 0) caricamento(h)->traccia->sampleRate = sampleRate;
}

// Waveform e tempo: dal file di analisi se c'è e vale, altrimenti calcolati e salvati. Restituisce [bpm, primo battito, secondi]
JNIEXPORT jdoubleArray JNICALL Java_com_toniaudio_djaaay_Motore_analizza(JNIEnv* env, jobject, jlong h, jstring cache) {
    if (h == 0) return nullptr;
    Caricamento* c = caricamento(h);
    const char* percorso = cache ? env->GetStringUTFChars(cache, nullptr) : nullptr;
    if (percorso == nullptr || !djaaay::caricaWaveform(percorso, c->waveform, c->tempo)) {
        c->waveform = djaaay::analizzaWaveform(*c->traccia);
        c->tempo = djaaay::analizzaTempo(*c->traccia);
        if (percorso != nullptr) djaaay::salvaWaveform(c->waveform, c->tempo, percorso);
    }
    if (percorso != nullptr) env->ReleaseStringUTFChars(cache, percorso);
    const jdouble valori[3] = {c->tempo.bpm, c->tempo.primoBattito, c->traccia->secondi()};
    jdoubleArray r = env->NewDoubleArray(3);
    env->SetDoubleArrayRegion(r, 0, 3, valori);
    return r;
}

// Livelli della waveform per l'interfaccia: per livello [bassi…, medi…, alti…] (colonne di 64·2^livello frame)
JNIEXPORT jobjectArray JNICALL Java_com_toniaudio_djaaay_Motore_waveform(JNIEnv* env, jobject, jlong h) {
    if (h == 0) return nullptr;
    const auto& livelli = caricamento(h)->waveform.livelli;
    jobjectArray r = env->NewObjectArray(static_cast<jsize>(livelli.size()), env->FindClass("[B"), nullptr);
    for (size_t i = 0; i < livelli.size(); ++i) {
        const auto& l = livelli[i];
        const auto n = static_cast<jsize>(l.colonne());
        jbyteArray a = env->NewByteArray(3 * n);
        env->SetByteArrayRegion(a, 0, n, reinterpret_cast<const jbyte*>(l.bassi.data()));
        env->SetByteArrayRegion(a, n, n, reinterpret_cast<const jbyte*>(l.medi.data()));
        env->SetByteArrayRegion(a, 2 * n, n, reinterpret_cast<const jbyte*>(l.alti.data()));
        env->SetObjectArrayElement(r, static_cast<jsize>(i), a);
        env->DeleteLocalRef(a);
    }
    return r;
}

// Il brano passa al deck (che ne diventa proprietario); il caricamento sparisce
JNIEXPORT void JNICALL Java_com_toniaudio_djaaay_Motore_caricaNelDeck(JNIEnv*, jobject, jlong h, jint deck) {
    if (h == 0) return;
    Caricamento* c = caricamento(h);
    if (deck >= 0 && deck < 2 && c->traccia->frame() > 0) {
        uscita()->motore.deck[deck].carica(c->traccia);
        c->traccia = nullptr;
    }
    delete c;
}

JNIEXPORT void JNICALL Java_com_toniaudio_djaaay_Motore_scartaTraccia(JNIEnv*, jobject, jlong h) {
    delete caricamento(h);
}

// ---------------------------------------------------------------------------------------------------------------
// Comandi e stato

JNIEXPORT void JNICALL Java_com_toniaudio_djaaay_Motore_comando(JNIEnv*, jobject, jint deck, jint tipo, jdouble a, jdouble b) {
    if (deck < 0 || deck > 1 || tipo < djaaay::ComandoDeck::Play || tipo > djaaay::ComandoDeck::Loop) return;  // Carica no
    djaaay::ComandoDeck c;
    c.tipo = static_cast<djaaay::ComandoDeck::Tipo>(tipo);
    c.a = a;
    c.b = b;
    uscita()->motore.deck[deck].invia(c);
}

// Parametri del deck: 0 pitch, 1 frizione, 2 avvio (s), 3 frenata (s), 4 keylock (0/1), 5 DVS (0/1)
JNIEXPORT void JNICALL Java_com_toniaudio_djaaay_Motore_impostaDeck(JNIEnv*, jobject, jint deck, jint parametro, jfloat v) {
    if (deck < 0 || deck > 1) return;
    djaaay::Deck& d = uscita()->motore.deck[deck];
    switch (parametro) {
        case 0: d.pitch.store(std::clamp(v, -0.5f, 0.5f)); break;
        case 1: d.frizione.store(std::clamp(v, 0.0f, 1.0f)); break;
        case 2: d.avvioSecondi.store(std::clamp(v, 0.0f, 5.0f)); break;
        case 3: d.frenataSecondi.store(std::clamp(v, 0.0f, 5.0f)); break;
        case 4: d.keylock.store(v >= 0.5f); break;
        case 5: d.dvs.store(v >= 0.5f); break;
        default: break;
    }
}

// Mixer: per canale 0 gain, 1 alti, 2 medi, 3 bassi, 4 filtro, 5 volume; generali 10 crossfader, 11 curva, 12 inverti, 13 master
JNIEXPORT void JNICALL Java_com_toniaudio_djaaay_Motore_impostaMixer(JNIEnv*, jobject, jint parametro, jint canale, jfloat v) {
    djaaay::Mixer& m = uscita()->motore.mixer;
    if (parametro < 10) {
        if (canale < 0 || canale > 1) return;
        djaaay::Canale& c = m.canale[canale];
        switch (parametro) {
            case 0: c.gain.store(v); break;
            case 1: c.alti.store(v); break;
            case 2: c.medi.store(v); break;
            case 3: c.bassi.store(v); break;
            case 4: c.filtro.store(v); break;
            case 5: c.volume.store(v); break;
            default: break;
        }
        return;
    }
    switch (parametro) {
        case 10: m.crossfader.store(v); break;
        case 11: m.curva.store(static_cast<int>(v)); break;
        case 12: m.inverti.store(v >= 0.5f); break;
        case 13: m.master.store(v); break;
        default: break;
    }
}

// --- DVS
JNIEXPORT jstring JNICALL Java_com_toniaudio_djaaay_Motore_dvsApri(JNIEnv* env, jobject, jint deviceId, jint coppia) {
    return env->NewStringUTF(ingressoDvs()->apri(deviceId, coppia == 1 ? 2 : 0).c_str());
}

JNIEXPORT void JNICALL Java_com_toniaudio_djaaay_Motore_dvsChiudi(JNIEnv*, jobject) { ingressoDvs()->chiudi(); }

// 0 tipo di timecode, 1 45 giri, 2 assoluta, 3 sensibilità a disco fermo, 4 protezione salto puntina,
// 5 livello d'ingresso (dB), 6 calibra, 7 azzera
JNIEXPORT void JNICALL Java_com_toniaudio_djaaay_Motore_impostaDvs(JNIEnv*, jobject, jint parametro, jfloat v) {
    djaaay::Motore& m = uscita()->motore;
    switch (parametro) {
        case 0: m.timecode.impostaTipo(static_cast<int>(v)); break;  // la prima volta costruisce la tabella
        case 1: m.timecode.giri45.store(v >= 0.5f); break;
        case 2: m.dvsAssoluta.store(v >= 0.5f); break;
        case 3: m.timecode.sensibilita.store(std::clamp(v, 0.0f, 1.0f)); break;
        case 4: m.dvsProtezione.store(v >= 0.5f); break;
        case 5: m.timecode.guadagnoDb.store(std::clamp(v, -12.0f, 24.0f)); break;
        case 6: m.timecode.chiediCalibra.store(true); break;
        case 7: m.timecode.chiediAzzera.store(true); break;
        default: break;
    }
}

// [0 qualità, 1 velocità, 2 posizione (< 0 = non nota), 3 livello d'ingresso, 4 ingresso aperto, 5 calibrato,
//  6 canali invertiti, 7 —] poi lo scope: 256 punti (x, y)
JNIEXPORT void JNICALL Java_com_toniaudio_djaaay_Motore_statoDvs(JNIEnv* env, jobject, jfloatArray dest) {
    constexpr int kPunti = djaaay::Timecode::kPuntiScope;
    if (env->GetArrayLength(dest) < 8 + 2 * kPunti) return;
    const djaaay::Timecode& t = uscita()->motore.timecode;
    jfloat v[8 + 2 * kPunti];
    v[0] = t.qualita();
    v[1] = t.velocitaLetta();
    v[2] = static_cast<jfloat>(t.posizioneLetta());
    v[3] = t.livello();
    v[4] = ingressoDvs()->aperto() ? 1.0f : 0.0f;
    v[5] = t.calibrato() ? 1.0f : 0.0f;
    v[6] = t.canaliInvertiti() ? 1.0f : 0.0f;
    v[7] = 0.0f;
    t.copiaScope(v + 8);
    env->SetFloatArrayRegion(dest, 0, 8 + 2 * kPunti, v);
}

// Effetti del deck: 0 acceso (0/1), 1 tipo (0 echo, 1 dub, 2 reverb, 3 stretch), 6 secondi per battito;
// per tipo `t`: 2 dry/wet, 3 parametro 1, 4 parametro 2, 5 scelta della riga in basso (indice)
JNIEXPORT void JNICALL Java_com_toniaudio_djaaay_Motore_impostaFx(JNIEnv*, jobject, jint deck, jint parametro, jint t, jfloat v) {
    if (deck < 0 || deck > 1 || t < 0 || t > 3) return;
    djaaay::Effetto& e = uscita()->motore.effetto[deck];
    switch (parametro) {
        case 0: e.acceso.store(v >= 0.5f); break;
        case 1: e.tipo.store(std::clamp(static_cast<int>(v), 0, 3)); break;
        case 2: e.dryWet[t].store(std::clamp(v, 0.0f, 1.0f)); break;
        case 3: e.param1[t].store(std::clamp(v, 0.0f, 1.0f)); break;
        case 4: e.param2[t].store(std::clamp(v, 0.0f, 1.0f)); break;
        case 5: e.scelta[t].store(static_cast<int>(v)); break;
        case 6: e.secondiBattito.store(std::clamp(v, 0.05f, 4.0f)); break;
        default: break;
    }
}

// Istantanea per l'interfaccia (a ogni fotogramma), senza allocazioni:
// per deck (×8, da 0 e da 8): posizione, durata, velocità, in play, cue, keylock in uso, livello canale, 0;
// 16 livello master L, 17 livello master R, 18 REC attiva, 19 secondi registrati
JNIEXPORT void JNICALL Java_com_toniaudio_djaaay_Motore_stato(JNIEnv* env, jobject, jdoubleArray dest) {
    if (env->GetArrayLength(dest) < 20) return;
    djaaay::Motore& m = uscita()->motore;
    for (auto& d : m.deck) d.raccogliTracceVecchie();  // libera i brani sostituiti (sempre dal thread dell'interfaccia)
    jdouble v[20] = {};
    for (int d = 0; d < 2; ++d) {
        const djaaay::Deck& k = m.deck[d];
        v[d * 8 + 0] = k.posizioneSecondi();
        v[d * 8 + 1] = k.durataSecondi();
        v[d * 8 + 2] = k.velocita();
        v[d * 8 + 3] = k.inPlay() ? 1.0 : 0.0;
        v[d * 8 + 4] = k.cueSecondi();
        v[d * 8 + 5] = k.keylockInUso() ? 1.0 : 0.0;
        v[d * 8 + 6] = m.mixer.canale[d].livello.load();
    }
    v[16] = m.mixer.livelloL.load();
    v[17] = m.mixer.livelloR.load();
    v[18] = m.registratore.attiva() ? 1.0 : 0.0;
    v[19] = m.registratore.secondi();
    env->SetDoubleArrayRegion(dest, 0, 20, v);
}

}  // extern "C"
