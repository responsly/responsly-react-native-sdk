import { TurboModuleRegistry, type TurboModule } from 'react-native';

/**
 * The native module (codegen spec). It is a thin layer over the Responsly iOS and Android SDKs;
 * use the default export of the package instead of calling it directly.
 *
 * Arguments are already plain data here: dates are ISO 8601 strings, removed traits travel as
 * a list of keys, options hold only the fields that were set.
 */
export interface Spec extends TurboModule {
  /** `options`: `{ apiHost?: string, logLevel?: 'none' | 'error' | 'info' | 'debug' }` */
  initialize(sdkKey: string, options: Object): void;
  /** `traits`: string, number and boolean values. `removed`: keys of traits to remove. */
  identify(userId: string, traits: Object, removed: Array<string>): void;
  setAttributes(traits: Object, removed: Array<string>): void;
  track(event: string, properties: Object): void;
  screen(name: string): void;
  /** `options`: `{ force: boolean, hiddenFields: {[key]: string}, locale?: string, presentation?: string }` */
  showSurvey(surveyId: string, options: Object): void;
  closeSurvey(): void;
  pause(): void;
  resume(): void;
  setLocale(locale: string | null): void;
  setHiddenFields(fields: Object): void;
  setTrackingEnabled(enabled: boolean): void;
  reset(): void;

  /**
   * Installs or removes the native display guard. While installed, the native side emits
   * `ResponslyDisplayGuard` with `{ requestId, survey }` and waits for `resolveDisplayGuard`.
   */
  setDisplayGuardEnabled(enabled: boolean): void;
  resolveDisplayGuard(requestId: string, allow: boolean): void;

  // Required by NativeEventEmitter.
  addListener(eventName: string): void;
  removeListeners(count: number): void;
}

export default TurboModuleRegistry.get<Spec>('ResponslySdk');
