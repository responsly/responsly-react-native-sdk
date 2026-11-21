import { NativeEventEmitter } from 'react-native';
import NativeResponslySdk, { type Spec } from './NativeResponslySdk';

const LINKING_ERROR =
  'The Responsly native module could not be found. ' +
  'Rebuild the app after installing @responsly/react-native-sdk (on iOS run `pod install` first); ' +
  'reloading JavaScript is not enough. Expo Go cannot load the SDK: use a development build. ' +
  'See https://developers.responsly.com/mobile-sdk/troubleshooting/';

export interface Subscription {
  remove(): void;
}

export function native(): Spec {
  if (NativeResponslySdk == null) {
    throw new Error(LINKING_ERROR);
  }
  return NativeResponslySdk;
}

let emitter: NativeEventEmitter | null = null;

export function eventEmitter(): NativeEventEmitter {
  if (emitter == null) {
    emitter = new NativeEventEmitter(native());
  }
  return emitter;
}
