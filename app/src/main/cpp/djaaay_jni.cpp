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

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL Java_com_toniaudio_djaaay_Motore_avvia(JNIEnv*, jobject) {
    return uscita()->apri() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_toniaudio_djaaay_Motore_ferma(JNIEnv*, jobject) {
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

}  // extern "C"
