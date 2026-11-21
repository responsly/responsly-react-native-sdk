import { setDisplayGuard } from './displayGuard';
import { addListener } from './listeners';
import { native } from './native';
import type {
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
