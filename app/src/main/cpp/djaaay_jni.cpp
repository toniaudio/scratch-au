// Ponte tra Kotlin e il motore audio, più l'uscita audio con Oboe.
#include <jni.h>
#include <android/log.h>
#include <oboe/Oboe.h>
#include <memory>
#include <mutex>

#include "Prova.h"

namespace {

const char* kTag = "Djaaay";

class Uscita : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    bool apri() {
        std::lock_guard<std::mutex> lock(mutex_);
        // Se c'è già un flusso aperto (Activity ricreata) lo si chiude: due flussi suonerebbero insieme
        chiudiSenzaLock();
        oboe::AudioStreamBuilder b;
        b.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(oboe::ChannelCount::Stereo)
            ->setDataCallback(this)
            ->setErrorCallback(this);
        const oboe::Result r = b.openStream(stream_);
        if (r != oboe::Result::OK) {
            __android_log_print(ANDROID_LOG_ERROR, kTag, "apertura audio fallita: %s", oboe::convertToText(r));
            return false;
        }
        stream_->setBufferSizeInFrames(stream_->getFramesPerBurst() * 2);
        motore.prepare(stream_->getSampleRate());
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

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* s, void* dati, int32_t frame) override {
        motore.render(static_cast<float*>(dati), frame, s->getChannelCount());
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

    djaaay::Prova motore;

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
    uscita()->chiudi();
}

JNIEXPORT jint JNICALL Java_com_toniaudio_djaaay_Motore_sampleRate(JNIEnv*, jobject) {
    return uscita()->sampleRate();
}

JNIEXPORT void JNICALL Java_com_toniaudio_djaaay_Motore_tonoProva(JNIEnv*, jobject, jboolean acceso) {
    uscita()->motore.setAcceso(acceso == JNI_TRUE);
}

}  // extern "C"
