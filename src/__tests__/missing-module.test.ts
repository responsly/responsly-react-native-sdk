import Responsly from '../index';

jest.mock('../NativeResponslySdk', () => ({ __esModule: true, default: null }));

describe('without the native module (Expo Go, or an app that was not rebuilt)', () => {
  it('importing the package does not throw', () => {
    expect(typeof Responsly.initialize).toBe('function');
  });

  it('calls throw an error that says what to do', () => {
    expect(() => Responsly.initialize('rsk_test')).toThrow(
      /native module could not be found/
    );
    expect(() => Responsly.track('purchase_completed')).toThrow(/Expo Go/);
    expect(() => Responsly.addListener({})).toThrow(/Rebuild the app/);
    expect(() => Responsly.setDisplayGuard(() => true)).toThrow(
      /development build/
    );
  });
});
