import type { Spec } from '../NativeResponslySdk';

/** The native module, mocked for the Jest tests of the JavaScript layer. */
const NativeResponslySdk: jest.Mocked<Omit<Spec, 'getConstants'>> = {
  initialize: jest.fn(),
  identify: jest.fn(),
  setAttributes: jest.fn(),
  track: jest.fn(),
  screen: jest.fn(),
  showSurvey: jest.fn(),
  closeSurvey: jest.fn(),
  pause: jest.fn(),
  resume: jest.fn(),
  setLocale: jest.fn(),
  setHiddenFields: jest.fn(),
  setTrackingEnabled: jest.fn(),
  reset: jest.fn(),
  setDisplayGuardEnabled: jest.fn(),
  resolveDisplayGuard: jest.fn(),
  addListener: jest.fn(),
  removeListeners: jest.fn(),
};

export default NativeResponslySdk;
