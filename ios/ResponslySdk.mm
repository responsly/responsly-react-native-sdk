#import "ResponslySdk.h"

// ResponslySdkBridge (Swift) and the Responsly iOS SDK sources are compiled into this pod.
#if __has_include(<ResponslyReactNative/ResponslyReactNative-Swift.h>)
#import <ResponslyReactNative/ResponslyReactNative-Swift.h>
#else
#import "ResponslyReactNative-Swift.h"
#endif

@implementation ResponslySdk {
  ResponslySdkBridge *_sdk;
}

RCT_EXPORT_MODULE(ResponslySdk)

+ (BOOL)requiresMainQueueSetup
{
  return NO;
}

- (instancetype)init
{
  if (self = [super init]) {
    __weak __typeof(self) weakSelf = self;
    _sdk = [[ResponslySdkBridge alloc] initWithEmitter:^(NSString *name, NSDictionary<NSString *, id> *body) {
      [weakSelf sendEventWithName:name body:body];
    }];
  }
  return self;
}

- (void)dealloc
{
  [_sdk invalidate];
}

- (void)invalidate
{
  [_sdk invalidate];
  [super invalidate];
}

#pragma mark - Events

- (NSArray<NSString *> *)supportedEvents
{
  return @[ ResponslySdkBridge.eventName, ResponslySdkBridge.displayGuardEventName ];
}

- (void)startObserving
{
  [_sdk setObserving:YES];
}

- (void)stopObserving
{
  [_sdk setObserving:NO];
}

#pragma mark - Calls

RCT_EXPORT_METHOD(initialize : (NSString *)sdkKey options : (NSDictionary *)options)
{
  [_sdk initialize:sdkKey options:options ?: @{}];
}

RCT_EXPORT_METHOD(identify : (NSString *)userId traits : (NSDictionary *)traits removed : (NSArray *)removed)
{
  [_sdk identify:userId traits:traits ?: @{} removed:[ResponslySdk strings:removed]];
}

RCT_EXPORT_METHOD(setAttributes : (NSDictionary *)traits removed : (NSArray *)removed)
{
  [_sdk setAttributes:traits ?: @{} removed:[ResponslySdk strings:removed]];
}

RCT_EXPORT_METHOD(track : (NSString *)event properties : (NSDictionary *)properties)
{
  [_sdk track:event properties:properties ?: @{}];
}

RCT_EXPORT_METHOD(screen : (NSString *)name)
{
  [_sdk screen:name];
}

RCT_EXPORT_METHOD(showSurvey : (NSString *)surveyId options : (NSDictionary *)options)
{
  [_sdk showSurvey:surveyId options:options ?: @{}];
}

RCT_EXPORT_METHOD(closeSurvey)
{
  [_sdk closeSurvey];
}

RCT_EXPORT_METHOD(pause)
{
  [_sdk pause];
}

RCT_EXPORT_METHOD(resume)
{
  [_sdk resume];
}

RCT_EXPORT_METHOD(setLocale : (NSString *_Nullable)locale)
{
  [_sdk setLocale:locale];
}

RCT_EXPORT_METHOD(setHiddenFields : (NSDictionary *)fields)
{
  [_sdk setHiddenFields:fields ?: @{}];
}

RCT_EXPORT_METHOD(setTrackingEnabled : (BOOL)enabled)
{
  [_sdk setTrackingEnabled:enabled];
}

RCT_EXPORT_METHOD(reset)
{
  [_sdk reset];
}

RCT_EXPORT_METHOD(setDisplayGuardEnabled : (BOOL)enabled)
{
  [_sdk setDisplayGuardEnabled:enabled];
}

RCT_EXPORT_METHOD(resolveDisplayGuard : (NSString *)requestId allow : (BOOL)allow)
{
  [_sdk resolveDisplayGuard:requestId allow:allow];
}

// addListener: and removeListeners: of the spec are implemented by RCTEventEmitter.

#pragma mark - Helpers

+ (NSArray<NSString *> *)strings:(NSArray *)array
{
  NSMutableArray<NSString *> *result = [NSMutableArray new];
  for (id item in array) {
    if ([item isKindOfClass:[NSString class]]) {
      [result addObject:item];
    }
  }
  return result;
}

#ifdef RCT_NEW_ARCH_ENABLED
- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:
    (const facebook::react::ObjCTurboModule::InitParams &)params
{
  return std::make_shared<facebook::react::NativeResponslySdkSpecJSI>(params);
}
#endif

@end
