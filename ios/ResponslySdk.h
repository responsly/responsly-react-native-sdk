#import <React/RCTEventEmitter.h>

#ifdef RCT_NEW_ARCH_ENABLED
#import <ResponslySdkSpec/ResponslySdkSpec.h>

/// The React Native module `ResponslySdk`: a TurboModule on the New Architecture.
@interface ResponslySdk : RCTEventEmitter <NativeResponslySdkSpec>
#else
#import <React/RCTBridgeModule.h>

/// The React Native module `ResponslySdk`: a bridge module on the old architecture.
@interface ResponslySdk : RCTEventEmitter <RCTBridgeModule>
#endif

@end
