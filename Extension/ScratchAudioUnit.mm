#import "ScratchAudioUnit.h"

#import <AVFoundation/AVFoundation.h>

#include "ScratchDSP.hpp"

// Buffer in cui il plugin legge l'audio in ingresso (quello che AUM gli manda).
// Viene preparato fuori dal thread audio, in allocateRenderResources.
struct IngressoAudio {
    const AudioBufferList *originale = nullptr;  // puntatori "veri" del buffer
    AudioBufferList *modificabile = nullptr;     // quello passato all'host, che può cambiarne i puntatori
    AUAudioFrameCount maxFrame = 0;
};

@implementation ScratchAudioUnit {
    scratch::ScratchDSP _dsp;
    IngressoAudio _ingresso;
    AVAudioPCMBuffer *_bufferIngresso;
    AUAudioUnitBus *_busIngresso;
    AUAudioUnitBus *_busUscita;
    AUAudioUnitBusArray *_busesIngresso;
    AUAudioUnitBusArray *_busesUscita;
    AUParameterTree *_alberoParametri;
}

- (instancetype)initWithComponentDescription:(AudioComponentDescription)componentDescription
                                     options:(AudioComponentInstantiationOptions)options
                                       error:(NSError **)outError {
    self = [super initWithComponentDescription:componentDescription options:options error:outError];
    if (self == nil) { return nil; }

    AVAudioFormat *formato = [[AVAudioFormat alloc] initStandardFormatWithSampleRate:44100.0 channels:2];
    _busIngresso = [[AUAudioUnitBus alloc] initWithFormat:formato error:outError];
    _busUscita = [[AUAudioUnitBus alloc] initWithFormat:formato error:outError];
    if (_busIngresso == nil || _busUscita == nil) { return nil; }
    _busesIngresso = [[AUAudioUnitBusArray alloc] initWithAudioUnit:self
                                                            busType:AUAudioUnitBusTypeInput
                                                             busses:@[_busIngresso]];
    _busesUscita = [[AUAudioUnitBusArray alloc] initWithAudioUnit:self
                                                          busType:AUAudioUnitBusTypeOutput
                                                           busses:@[_busUscita]];

    const AudioUnitParameterOptions flag = kAudioUnitParameterFlag_IsReadable | kAudioUnitParameterFlag_IsWritable;
    AUParameter *crossfader = [AUParameterTree createParameterWithIdentifier:@"crossfader"
                                                                        name:@"Crossfader"
                                                                     address:ScratchParamCrossfader
                                                                         min:0 max:1
                                                                        unit:kAudioUnitParameterUnit_Generic
                                                                    unitName:nil
                                                                       flags:flag
                                                                valueStrings:nil
                                                         dependentParameters:nil];
    AUParameter *curva = [AUParameterTree createParameterWithIdentifier:@"curva"
                                                                   name:@"Curva crossfader"
                                                                address:ScratchParamCurva
                                                                    min:0 max:2
                                                                   unit:kAudioUnitParameterUnit_Indexed
                                                               unitName:nil
                                                                  flags:flag
                                                           valueStrings:@[@"Taglio (scratch)", @"Lineare", @"Potenza costante"]
                                                    dependentParameters:nil];
    AUParameter *inverti = [AUParameterTree createParameterWithIdentifier:@"inverti"
                                                                     name:@"Inverti crossfader"
                                                                  address:ScratchParamInverti
                                                                      min:0 max:1
                                                                     unit:kAudioUnitParameterUnit_Boolean
                                                                 unitName:nil
                                                                    flags:flag
                                                             valueStrings:nil
                                                      dependentParameters:nil];
    crossfader.value = 0.5f;
    curva.value = scratch::kCurvaTaglio;
    inverti.value = 0.0f;
    _alberoParametri = [AUParameterTree createTreeWithChildren:@[crossfader, curva, inverti]];

    // I blocchi catturano il puntatore al DSP, non self: niente ARC nel thread audio.
    scratch::ScratchDSP *dsp = &_dsp;
    for (AUParameter *p in _alberoParametri.allParameters) { dsp->setParameter(p.address, p.value); }
    _alberoParametri.implementorValueObserver = ^(AUParameter *param, AUValue valore) {
        dsp->setParameter(param.address, valore);
    };
    _alberoParametri.implementorValueProvider = ^AUValue(AUParameter *param) {
        return dsp->getParameter(param.address);
    };
    _alberoParametri.implementorStringFromValueCallback = ^NSString *(AUParameter *param, const AUValue *valore) {
        const AUValue v = valore ? *valore : param.value;
        if (param.address == ScratchParamCrossfader) {
            return [NSString stringWithFormat:@"%.0f%%", v * 100.0f];
        }
        if (param.address == ScratchParamInverti) {
            return v >= 0.5f ? @"Sì" : @"No";
        }
        NSArray<NSString *> *testi = param.valueStrings;
        const NSInteger i = (NSInteger)lroundf(v);
        return (testi && i >= 0 && i < (NSInteger)testi.count) ? testi[i] : [NSString stringWithFormat:@"%.0f", v];
    };

    self.maximumFramesToRender = 4096;
    return self;
}

- (AUParameterTree *)parameterTree { return _alberoParametri; }
- (AUAudioUnitBusArray *)inputBusses { return _busesIngresso; }
- (AUAudioUnitBusArray *)outputBusses { return _busesUscita; }

// Mono o stereo, purché ingresso e uscita abbiano gli stessi canali.
- (NSArray<NSNumber *> *)channelCapabilities { return @[@(-1), @(-1)]; }
- (BOOL)canProcessInPlace { return YES; }

- (BOOL)allocateRenderResourcesAndReturnError:(NSError **)outError {
    if (![super allocateRenderResourcesAndReturnError:outError]) { return NO; }
    if (_busIngresso.format.channelCount != _busUscita.format.channelCount) {
        if (outError) {
            *outError = [NSError errorWithDomain:NSOSStatusErrorDomain
                                            code:kAudioUnitErr_FailedInitialization
                                        userInfo:nil];
        }
        [super deallocateRenderResources];
        return NO;
    }
    _bufferIngresso = [[AVAudioPCMBuffer alloc] initWithPCMFormat:_busIngresso.format
                                                    frameCapacity:self.maximumFramesToRender];
    _ingresso.originale = _bufferIngresso.audioBufferList;
    _ingresso.modificabile = _bufferIngresso.mutableAudioBufferList;
    _ingresso.maxFrame = self.maximumFramesToRender;
    _dsp.prepare(_busUscita.format.sampleRate, (int)_busUscita.format.channelCount);
    return YES;
}

- (void)deallocateRenderResources {
    _ingresso = IngressoAudio();
    _bufferIngresso = nil;
    [super deallocateRenderResources];
}

- (AUInternalRenderBlock)internalRenderBlock {
    scratch::ScratchDSP *dsp = &_dsp;
    IngressoAudio *ingresso = &_ingresso;

    return ^AUAudioUnitStatus(AudioUnitRenderActionFlags *actionFlags,
                              const AudioTimeStamp *timestamp,
                              AVAudioFrameCount frameCount,
                              NSInteger outputBusNumber,
                              AudioBufferList *outputData,
                              const AURenderEvent *realtimeEventListHead,
                              AURenderPullInputBlock pullInputBlock) {
        if (ingresso->modificabile == nullptr) { return kAudioUnitErr_Uninitialized; }
        if (frameCount > ingresso->maxFrame) { return kAudioUnitErr_TooManyFramesToProcess; }

        // Parametri arrivati dall'host (automazioni, MIDI learn di AUM)
        for (const AURenderEvent *e = realtimeEventListHead; e != nullptr; e = e->head.next) {
            if (e->head.eventType == AURenderEventParameter || e->head.eventType == AURenderEventParameterRamp) {
                dsp->setParameter(e->parameter.parameterAddress, e->parameter.value);
            }
        }

        // Legge l'ingresso nel nostro buffer
        AudioBufferList *in = ingresso->modificabile;
        const UInt32 byte = frameCount * sizeof(float);
        for (UInt32 i = 0; i < in->mNumberBuffers; ++i) {
            in->mBuffers[i].mData = ingresso->originale->mBuffers[i].mData;
            in->mBuffers[i].mDataByteSize = byte;
        }
        bool haIngresso = false;
        if (pullInputBlock != nullptr) {
            AudioUnitRenderActionFlags flagIngresso = 0;
            haIngresso = pullInputBlock(&flagIngresso, timestamp, frameCount, 0, in) == noErr;
        }

        // Se l'host non dà buffer di uscita, si lavora "sul posto" in quelli dell'ingresso
        const UInt32 canali = std::min(in->mNumberBuffers, outputData->mNumberBuffers);
        const float *canaliIn[8] = {};
        float *canaliOut[8] = {};
        const UInt32 n = std::min<UInt32>(canali, 8);
        for (UInt32 i = 0; i < n; ++i) {
            if (outputData->mBuffers[i].mData == nullptr) {
                outputData->mBuffers[i].mData = in->mBuffers[i].mData;
            }
            outputData->mBuffers[i].mDataByteSize = byte;
            canaliIn[i] = static_cast<const float *>(in->mBuffers[i].mData);
            canaliOut[i] = static_cast<float *>(outputData->mBuffers[i].mData);
        }
        dsp->process(haIngresso ? canaliIn : nullptr, canaliOut, (int)n, (int)frameCount);
        return noErr;
    };
}

@end
