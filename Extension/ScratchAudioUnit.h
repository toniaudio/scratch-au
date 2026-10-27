// Audio Unit del plugin (Objective-C++). Il lavoro sull'audio è in DSP/ScratchDSP.hpp.
#import <AudioToolbox/AudioToolbox.h>

NS_ASSUME_NONNULL_BEGIN

// Indirizzi dei parametri (uguali a scratch::ParamAddress in ScratchDSP.hpp)
static const AUParameterAddress ScratchParamCrossfader = 0;
static const AUParameterAddress ScratchParamCurva = 1;
static const AUParameterAddress ScratchParamInverti = 2;

@interface ScratchAudioUnit : AUAudioUnit
@end

NS_ASSUME_NONNULL_END
