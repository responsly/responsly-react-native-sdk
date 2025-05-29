import { NativeEventEmitter } from 'react-native';
import NativeResponslySdk, { type Spec } from './NativeResponslySdk';
import { convertNativeEvent, toSurvey, type NativeEvent } from './events';
import type {
  ResponslyDisplayGuard,
  ResponslyListener,
  ResponslyOptions,
  ResponslyProperties,
  ResponslyShowOptions,
  ResponslyTraits,
} from './types';
import {
  toNativeHiddenFields,
  toNativeOptions,
  toNativeProperties,
  toNativeShowOptions,
  toNativeTraits,
} from './values';

const LINKING_ERROR =
  'The Responsly native module could not be found. ' +
  'Rebuild the app after installing @responsly/react-native-sdk (on iOS run `pod install` first); ' +
  'reloading JavaScript is not enough. Expo Go cannot load the SDK: use a development build. ' +
  'See https://developers.responsly.com/mobile-sdk/troubleshooting/';

/** Listener callbacks: `{ type, payload }` with the native snake_case payload. */
const EVENT = 'ResponslyEvent';
/** A question from the native display guard: `{ requestId, survey }`. */
const DISPLAY_GUARD_EVENT = 'ResponslyDisplayGuard';

interface Subscription {
  remove(): void;
}

interface DisplayGuardRequest {
  requestId?: unknown;
  survey?: unknown;
}

function native(): Spec {
  if (NativeResponslySdk == null) {
    throw new Error(LINKING_ERROR);
  }
  return NativeResponslySdk;
}

let emitter: NativeEventEmitter | null = null;

function eventEmitter(): NativeEventEmitter {
  if (emitter == null) {
    emitter = new NativeEventEmitter(native());
  }
  return emitter;
}

// --- Listeners --------------------------------------------------------------------------------

/** One entry per `addListener` call, so the same object can be registered more than once. */
interface Registration {
  listener: ResponslyListener;
}

const registrations = new Set<Registration>();
let eventSubscription: Subscription | null = null;

function onNativeEvent(event: unknown): void {
  const converted = convertNativeEvent(event as NativeEvent);
  if (converted == null) {
    return;
  }
  // A copy, so that a callback may add or remove listeners.
  for (const { listener } of Array.from(registrations)) {
    const callback = listener[converted.callback] as
      ((payload: unknown) => void) | undefined;
    if (typeof callback !== 'function') {
      continue;
    }
    try {
      callback.call(listener, converted.payload);
    } catch (error) {
      console.error(
        `[Responsly] A listener threw in ${converted.callback}`,
        error
      );
    }
  }
}

function addListener(listener: ResponslyListener): () => void {
  const registration: Registration = { listener: listener ?? {} };
  if (eventSubscription == null) {
    eventSubscription = eventEmitter().addListener(EVENT, onNativeEvent);
  }
  registrations.add(registration);

  return () => {
    if (!registrations.delete(registration)) {
      return;
    }
    if (registrations.size === 0 && eventSubscription != null) {
      eventSubscription.remove();
      eventSubscription = null;
    }
  };
}

// --- Display guard ----------------------------------------------------------------------------

let displayGuard: ResponslyDisplayGuard | null = null;
let displayGuardSubscription: Subscription | null = null;

function isPromiseLike(value: unknown): value is PromiseLike<unknown> {
  return (
    value != null &&
    (typeof value === 'object' || typeof value === 'function') &&
    typeof (value as { then?: unknown }).then === 'function'
  );
}

function onDisplayGuardRequest(event: unknown): void {
  const request = (event ?? {}) as DisplayGuardRequest;
  const requestId = request.requestId;
  if (typeof requestId !== 'string') {
    return;
  }
  // Only an explicit `false` vetoes. A guard that was removed in the meantime, throws, rejects
  // or returns something else lets the survey through, like a guard that does not answer.
  const answer = (result: unknown) => {
    native().resolveDisplayGuard(requestId, result !== false);
  };

  const guard = displayGuard;
  if (guard == null) {
    answer(true);
    return;
  }

  let result: unknown;
  try {
    result = guard(toSurvey(request.survey));
  } catch (error) {
    console.error(
      '[Responsly] The display guard threw; the survey is shown',
      error
    );
    answer(true);
    return;
  }

  if (isPromiseLike(result)) {
    result.then(answer, (error: unknown) => {
      console.error(
        '[Responsly] The display guard rejected; the survey is shown',
        error
      );
      answer(true);
    });
  } else {
    answer(result);
  }
}

function setDisplayGuard(guard: ResponslyDisplayGuard | null): void {
  if (typeof guard === 'function') {
    displayGuard = guard;
    // Listen first, so that no question from the native guard is missed.
    if (displayGuardSubscription == null) {
      displayGuardSubscription = eventEmitter().addListener(
        DISPLAY_GUARD_EVENT,
        onDisplayGuardRequest
      );
    }
    native().setDisplayGuardEnabled(true);
  } else {
    displayGuard = null;
    native().setDisplayGuardEnabled(false);
    if (displayGuardSubscription != null) {
      displayGuardSubscription.remove();
      displayGuardSubscription = null;
    }
  }
}

// --- Public API -------------------------------------------------------------------------------

/**
 * The Responsly SDK. Every call is fire-and-forget and safe before `initialize`: it is
 * remembered and applied when the SDK starts (`showSurvey` before `initialize` reports
 * `not_initialized`). Results are reported to listeners.
 */
const Responsly = {
  /**
   * Starts the SDK. Call it once at the app root. Calling it again with the same key and API
   * host does nothing. The same key with another `apiHost` switches to that host and downloads
   * the configuration again; a different key resets the local state first.
   *
   * @param sdkKey the team's SDK key (`rsk_…`)
   */
  initialize(sdkKey: string, options?: ResponslyOptions): void {
    native().initialize(sdkKey, toNativeOptions(options));
  },

  /** Sets the user ID and merges the traits into the stored ones. */
  identify(userId: string, traits?: ResponslyTraits): void {
    const { values, removed } = toNativeTraits(traits);
    native().identify(userId, values, removed);
  },

  /** Merges traits without changing the user ID. A `null` value removes a trait. */
  setAttributes(traits: ResponslyTraits): void {
    const { values, removed } = toNativeTraits(traits);
    native().setAttributes(values, removed);
  },

  /** Counts the event and evaluates event triggers. */
  track(event: string, properties?: ResponslyProperties): void {
    native().track(event, toNativeProperties(properties));
  },

  /** Sets the current screen and evaluates screen triggers. */
  screen(name: string): void {
    native().screen(name);
  },

  /**
   * Shows a survey by its Survey ID. Audience, schedule and frequency apply unless `force` is
   * set. The outcome is reported to listeners: `onSurveyShown`, or `onSurveyNotShown` with the
   * reason.
   */
  showSurvey(surveyId: string, options?: ResponslyShowOptions): void {
    native().showSurvey(surveyId, toNativeShowOptions(options));
  },

  /**
   * Closes the visible survey; it counts as dismissed unless it was completed. A survey that is
   * not on screen yet (waiting or still loading) is cancelled without a callback.
   */
  closeSurvey(): void {
    native().closeSurvey();
  },

  /** Stops surveys started by triggers. `showSurvey` still works. Kept in memory only. */
  pause(): void {
    native().pause();
  },

  /** Resumes surveys started by triggers. */
  resume(): void {
    native().resume();
  },

  /**
   * Overrides the language for surveys and for the languages audience rule. `null` returns to
   * the device language.
   */
  setLocale(locale: string | null): void {
    native().setLocale(typeof locale === 'string' ? locale : null);
  },

  /** Hidden-field values sent with every survey. Replaces the previous map. */
  setHiddenFields(fields: Record<string, string>): void {
    native().setHiddenFields(toNativeHiddenFields(fields));
  },

  /** `false` switches the SDK off: no network, no targeting, nothing shown. Saved on the device. */
  setTrackingEnabled(enabled: boolean): void {
    native().setTrackingEnabled(Boolean(enabled));
  },

  /**
   * Clears the user ID, traits, language override, hidden fields, event counts, display history
   * and cached configuration, and closes a visible survey. Call it on logout.
   */
  reset(): void {
    native().reset();
  },

  /**
   * Sets a function that is asked right before a survey becomes visible. Return `false`, or a
   * promise of `false`, to veto the survey. No answer within 2 seconds counts as allow, and so
   * does a guard that throws or rejects. Not asked for `showSurvey` with `force`. `null` removes
   * the guard.
   */
  setDisplayGuard,

  /**
   * Registers survey callbacks; pass only the ones you need. Returns a function that removes
   * the listener. Works before and after `initialize`.
   */
  addListener,
};

export default Responsly;
