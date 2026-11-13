# Responsly React Native SDK

Show [Responsly](https://responsly.com) surveys inside your React Native app. You choose in Responsly who should see a survey and when; the SDK downloads that configuration, decides on the device and presents the survey as a bottom sheet, a dialog or full screen.

- iOS and Android, one TypeScript API
- Targeting runs on the device: events, screens, app launch, user traits, sampling, frequency and schedule
- New Architecture (TurboModule), with a fallback for the old architecture; works in Expo development builds
- No advertising identifier, no fingerprinting, no location; ships a privacy manifest on iOS

Full documentation: **https://developers.responsly.com/mobile-sdk/**

## Requirements

- React Native 0.75 or later
- iOS 15 or later, Android `minSdk` 23 or higher
- The team's SDK key (`rsk_…`) from the form's **Share → Mobile app** tab

## Install

### React Native CLI

```bash
npm install @responsly/react-native-sdk
cd ios && pod install
```

Autolinking does the rest. The package contains the native Responsly SDKs for iOS and Android as source, so there is nothing else to add: no CocoaPods source, no Maven repository, no manifest entry.

### Expo

```bash
npx expo install @responsly/react-native-sdk
```

Add the config plugin. It takes no options.

```json
{
  "expo": {
    "plugins": ["@responsly/react-native-sdk"]
  }
}
```

Then create a development build:

```bash
npx expo prebuild
npx expo run:ios
npx expo run:android
```

Or build with EAS using a development profile. The SDK contains native code, so it does not run in Expo Go.

### Platform minimums

Raise the platform minimums if your project is below them:

```ruby
# ios/Podfile
platform :ios, '15.0'
```

```groovy
// android/build.gradle
buildscript {
    ext {
        minSdkVersion = 23
    }
}
```

In Expo, set them with the `expo-build-properties` plugin instead of editing native files.

## Quick start

```ts
// index.js
import { AppRegistry } from 'react-native';
import Responsly from '@responsly/react-native-sdk';
import App from './App';
import { name as appName } from './app.json';

// 1. Start the SDK once at the app root, outside of any component.
Responsly.initialize('rsk_…', { logLevel: __DEV__ ? 'debug' : 'error' });

AppRegistry.registerComponent(appName, () => App);
```

With Expo Router, put the `initialize` call at module level in the root layout (`app/_layout.tsx`).

```ts
// 2. Tell it who the user is. Traits are used for targeting and passed to the survey.
Responsly.identify('user-123', {
  plan: 'pro',
  seats: 5,
  beta: true,
  signed_up: new Date(),
});

// 3. Report what happens in the app. Surveys with a matching trigger show by themselves.
Responsly.track('purchase_completed', { value: 49.9 });
Responsly.screen('Checkout');

// Or show a survey yourself.
Responsly.showSurvey('AbCd1234');

// 4. Listen to what happens.
const unsubscribe = Responsly.addListener({
  onSurveyCompleted: ({ responseId, answers }) =>
    console.log('completed', responseId, answers.length),
  onSurveyNotShown: ({ surveyId, reason }) =>
    console.log('not shown', surveyId, reason),
});

// 5. On logout.
Responsly.reset();
```

Every call returns at once and reports its result through listeners. Calls made before `initialize` are remembered and applied when the SDK starts. `initialize` is idempotent for the same key, so Fast Refresh is not a problem.

## API

The default export is the SDK object. All calls are its methods.

| Call | What it does |
|---|---|
| `initialize(sdkKey, options?)` | Starts the SDK. Options: `apiHost` (defaults to `https://api.responsly.com`) and `logLevel` (`'none'`, `'error'`, `'info'`, `'debug'`; defaults to `'error'`). Idempotent for the same key and host. A different key resets the local state first. |
| `identify(userId, traits?)` | Sets the user ID and merges the traits into the stored ones. |
| `setAttributes(traits)` | Merges traits without changing the user ID. A `null` value removes a trait. |
| `track(event, properties?)` | Adds one to the event's count and evaluates event triggers. |
| `screen(name)` | Sets the current screen and evaluates screen triggers. |
| `showSurvey(surveyId, options?)` | Shows a survey by its Survey ID from the form's **Share → Mobile app** tab (a form with several configurations has one for each). Options: `force`, `hiddenFields`, `locale`, `presentation` (`'bottom_sheet'`, `'dialog'`, `'fullscreen'`). Audience, schedule and frequency apply unless `force` is set. Before `initialize` it reports `not_initialized`. |
| `closeSurvey()` | Closes the survey that is visible or waiting to appear. A visible survey counts as dismissed unless it was completed; one that was still waiting is cancelled without a callback. |
| `pause()` / `resume()` | Stops and resumes surveys started by triggers. `showSurvey` still works while paused. Kept in memory only. |
| `setLocale(tag)` | Overrides the language for surveys and for the languages audience rule. `null` returns to the device language. |
| `setHiddenFields(fields)` | Hidden-field values sent with every survey. Replaces the previous map. |
| `setTrackingEnabled(enabled)` | `false` switches the SDK off: no network, no targeting, nothing shown. Saved on the device and kept by `reset`. |
| `reset()` | Clears the user ID, traits, language override, hidden fields, event counts, display history and cached configuration, and closes a visible survey. Call it on logout. |
| `setDisplayGuard(guard)` | Sets a function that can veto a survey right before it appears. `null` removes it. |
| `addListener(listener)` | Registers survey callbacks. Returns a function that removes the listener. |

Two helpers are named exports:

| Helper | What it does |
|---|---|
| `useResponslyScreen(name)` | A hook. Calls `screen(name)` when the component mounts and whenever `name` changes. |
| `createNavigationTracker(navigationRef)` | Returns `{ onReady, onStateChange }` for React Navigation's `NavigationContainer`. Calls `screen` with the active route name whenever it changes. |

Trait and property values are a `string`, a `number`, a `boolean` or a `Date`. Dates are sent as ISO 8601 strings in UTC with seconds precision, for example `2026-11-10T12:00:00Z`. Hidden-field values are strings.

The package is written in TypeScript and exports its types, among them `ResponslyOptions`, `ResponslyShowOptions`, `ResponslyListener`, `ResponslyDisplayGuard`, `ResponslySurvey`, `ResponslyTrigger`, `ResponslyAnswer`, `ResponslyNotShownReason` and one type per event payload.

### Events

Pass `addListener` an object with the callbacks you need. Payload keys are camelCase.

| Callback | Fires when | Payload |
|---|---|---|
| `onSurveyShown` | The survey became visible. | `survey` (`id`, `name`), `trigger` (`type`, `name`) |
| `onSurveyNotShown` | A survey matched a trigger or was requested with `showSurvey`, but was not shown. | `surveyId`, `reason` |
| `onQuestionAnswered` | The respondent answered a question. | `surveyId`, `questionId`, `type`, `title`, `value` |
| `onSurveyCompleted` | The respondent submitted the survey. | `surveyId`, `responseId`, `answers` (each with `questionId`, `type`, `title`, `value`) |
| `onSurveyDismissed` | The survey was closed before it was completed. | `surveyId` |
| `onSurveyError` | The survey reported an error while it was visible. | `surveyId`, `code`, `message` |

`reason` is one of the [not-shown reasons](https://developers.responsly.com/mobile-sdk/testing/#not-shown-reasons), for example `frequency_limit` or `audience_mismatch`. `trigger.type` is `event`, `screen`, `app_launch` or `manual`; `trigger.name` is `null` for the last two. An answer's `value` is a plain JavaScript value: a string, a number, a boolean, an array or an object, depending on the question type.

In a component, return the unsubscribe function from an effect:

```ts
useEffect(
  () =>
    Responsly.addListener({
      onSurveyCompleted: ({ responseId }) => console.log('completed', responseId),
    }),
  []
);
```

Listeners can be added before or after `initialize`. Events are delivered as they happen: one that fired while nobody was listening is not repeated to a later listener.

### Display guard

```ts
Responsly.setDisplayGuard((survey) => !checkoutInProgress);
```

The guard is asked right before a survey would appear, with the survey's `id` and `name`. Return `false`, or a promise of `false`, to veto it; the survey is then reported to `onSurveyNotShown` with the reason `vetoed`. No answer within 2 seconds counts as allow, and so does a guard that throws or rejects. It is not asked for `showSurvey(…, { force: true })`.

### Screen tracking

**React Navigation.** `createNavigationTracker` reports the active route name every time it changes, so screen triggers work without calling `screen` by hand:

```tsx
import { NavigationContainer, createNavigationContainerRef } from '@react-navigation/native';
import { createNavigationTracker } from '@responsly/react-native-sdk';

const navigationRef = createNavigationContainerRef();
const tracker = createNavigationTracker(navigationRef);

export default function App() {
  return (
    <NavigationContainer
      ref={navigationRef}
      onReady={tracker.onReady}
      onStateChange={tracker.onStateChange}
    >
      {/* navigators */}
    </NavigationContainer>
  );
}
```

The package does not depend on `@react-navigation/native`: any ref with `getCurrentRoute()` works.

**A single component.**

```tsx
import { useResponslyScreen } from '@responsly/react-native-sdk';

function CheckoutScreen() {
  useResponslyScreen('Checkout');
  return <CheckoutForm />;
}
```

**Expo Router.** Feed the current path to the hook in the root layout: `useResponslyScreen(usePathname())`. Screen names are then paths such as `/checkout`.

## Debugging

```ts
Responsly.initialize('rsk_…', { logLevel: 'debug' });
```

With the `debug` level the SDK logs every targeting decision with its reason. The lines come from the native SDK and are prefixed `[Responsly]`: read them in the Xcode console or in Logcat, not in the Metro terminal. To see a survey regardless of targeting, call `Responsly.showSurvey('AbCd1234', { force: true })`.

If a call throws "The Responsly native module could not be found", the app was not rebuilt after the package was installed (run `pod install` and build again; reloading JavaScript is not enough), or it is running in Expo Go.

### Jest

Jest runs without native code, so mock the package in tests of code that calls it:

```js
// jest.setup.js
jest.mock('@responsly/react-native-sdk', () => {
  const Responsly = { addListener: jest.fn(() => jest.fn()) };
  [
    'initialize', 'identify', 'setAttributes', 'track', 'screen', 'showSurvey', 'closeSurvey',
    'pause', 'resume', 'setLocale', 'setHiddenFields', 'setTrackingEnabled', 'reset',
    'setDisplayGuard',
  ].forEach((name) => {
    Responsly[name] = jest.fn();
  });
  return {
    __esModule: true,
    default: Responsly,
    useResponslyScreen: jest.fn(),
    createNavigationTracker: jest.fn(() => ({ onReady: jest.fn(), onStateChange: jest.fn() })),
  };
});
```

## Example app

[`example/`](example) is a bare React Native app (New Architecture) with a button for every call and a scrolling log of the listener callbacks. It uses the package from this folder, not from npm.

```bash
cd example
npm install
npm run pods        # iOS only: pod install
npm start           # Metro, in one terminal
npm run ios         # or: npm run android
```

Enter an SDK key, and an API host if you are not using the default, and press **Initialize**. The example allows plain-HTTP loads on both platforms so that it can talk to a local backend. Do not copy that into an app: the SDK itself never relaxes the platform's transport security.

## How the package is built

The JavaScript API is a thin layer over the native Responsly SDKs. Their sources are stored in this repository and compiled with the package:

| | Native SDK sources | Bridge |
|---|---|---|
| iOS | `ios/ResponslySDK/`, with the privacy manifest in `Resources/PrivacyInfo.xcprivacy` | `ios/ResponslySdk.h`, `ios/ResponslySdk.mm`, `ios/ResponslySdkBridge.swift` |
| Android | `android/src/main/java/com/responsly/sdk/`, `android/src/main/AndroidManifest.xml`, `android/consumer-rules.pro` | `android/src/main/java/com/responsly/reactnative/` |

Do not edit the native sources here. `scripts/sync-native.sh` copies them from the [iOS](https://github.com/responsly/responsly-ios-sdk) and [Android](https://github.com/responsly/responsly-android-sdk) repositories and writes the commits it copied to `native-sdk.json`:

```bash
npm run sync-native        # expects ../responsly-ios-sdk and ../responsly-android-sdk
RESPONSLY_IOS_SDK=<dir> RESPONSLY_ANDROID_SDK=<dir> npm run sync-native
```

On iOS, `ResponslyReactNative.podspec` builds the SDK sources and the bridge as one pod and bundles the privacy manifest. On Android, `android/build.gradle` builds them as one library; the SDK's manifest entries (the `INTERNET` permission, a startup provider and an internal activity) are merged into the app by Gradle, and `androidx.webkit` and `androidx.annotation` are used at the versions the Android SDK pins, read from `native-sdk.json`.

The native module is described once, in `src/NativeResponslySdk.ts`. On the New Architecture, React Native's codegen generates the TurboModule interfaces from it. For the old architecture the same methods are written by hand in `android/src/oldarch/`.

## Development

Use the Node version in `.nvmrc`.

```bash
npm install             # also builds lib/ with react-native-builder-bob
npm run typecheck
npm run lint
npm test
npm pack --dry-run      # what would be published

cd example
npm install
npm run pods
npm run build:ios       # xcodebuild, generic iOS Simulator destination, no code signing
npm run build:android   # ./gradlew assembleDebug for arm64-v8a
```

## License

[MIT](LICENSE)
