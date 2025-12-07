import { createNavigationTracker } from '../index';
import NativeModule from '../NativeResponslySdk';

jest.mock('../NativeResponslySdk');

const native = NativeModule as unknown as Record<string, jest.Mock>;

/** Stands in for React Navigation's container ref. */
function fakeNavigationRef(initial?: string) {
  let route: { name: string; key?: string } | undefined = initial
    ? { name: initial }
    : undefined;
  return {
    getCurrentRoute: jest.fn(() => route),
    navigate(name: string) {
      route = { name, key: `${name}-${Math.random()}` };
    },
  };
}

describe('createNavigationTracker', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('returns the two handlers of NavigationContainer', () => {
    const tracker = createNavigationTracker(fakeNavigationRef('Home'));
    expect(Object.keys(tracker).sort()).toEqual(['onReady', 'onStateChange']);
    expect(typeof tracker.onReady).toBe('function');
    expect(typeof tracker.onStateChange).toBe('function');
    expect(native.screen).not.toHaveBeenCalled();
  });

  it('reports the first route when navigation is ready', () => {
    const tracker = createNavigationTracker(fakeNavigationRef('Home'));
    tracker.onReady();
    expect(native.screen).toHaveBeenCalledTimes(1);
    expect(native.screen).toHaveBeenCalledWith('Home');
  });

  it('reports the active route whenever it changes', () => {
    const navigationRef = fakeNavigationRef('Home');
    const tracker = createNavigationTracker(navigationRef);
    tracker.onReady();

    navigationRef.navigate('Checkout');
    tracker.onStateChange();
    navigationRef.navigate('Home');
    tracker.onStateChange();

    expect(native.screen!.mock.calls).toEqual([
      ['Home'],
      ['Checkout'],
      ['Home'],
    ]);
  });

  it('does not report again while the route name stays the same', () => {
    const navigationRef = fakeNavigationRef('Home');
    const tracker = createNavigationTracker(navigationRef);
    tracker.onReady();
    tracker.onStateChange(); // params changed, a modal state changed, …
    navigationRef.navigate('Home'); // the same screen pushed again
    tracker.onStateChange();
    expect(native.screen).toHaveBeenCalledTimes(1);
  });

  it('accepts the state argument React Navigation passes to onStateChange', () => {
    const navigationRef = fakeNavigationRef('Home');
    const tracker = createNavigationTracker(navigationRef);
    const onStateChange: (state: unknown) => void = tracker.onStateChange;
    navigationRef.navigate('Settings');
    onStateChange({ index: 0, routes: [] });
    expect(native.screen).toHaveBeenCalledWith('Settings');
  });

  it('waits when there is no route yet', () => {
    const navigationRef = fakeNavigationRef();
    const tracker = createNavigationTracker(navigationRef);
    tracker.onReady();
    expect(native.screen).not.toHaveBeenCalled();

    navigationRef.navigate('Home');
    tracker.onStateChange();
    expect(native.screen).toHaveBeenCalledWith('Home');
  });

  it('accepts a plain React ref that holds the container', () => {
    const container = fakeNavigationRef('Home');
    const ref: { current: typeof container | null } = { current: null };
    const tracker = createNavigationTracker(ref);

    tracker.onReady(); // not attached yet
    expect(native.screen).not.toHaveBeenCalled();

    ref.current = container;
    tracker.onReady();
    expect(native.screen).toHaveBeenCalledWith('Home');
  });

  it('keeps separate trackers independent', () => {
    const a = createNavigationTracker(fakeNavigationRef('Home'));
    const b = createNavigationTracker(fakeNavigationRef('Home'));
    a.onReady();
    b.onReady();
    expect(native.screen).toHaveBeenCalledTimes(2);
  });
});
