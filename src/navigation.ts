import Responsly from './Responsly';

/**
 * The part of a React Navigation container ref the tracker needs. The package does not depend
 * on `@react-navigation/native`: anything with `getCurrentRoute()` works.
 */
export interface ResponslyNavigationRef {
  getCurrentRoute(): { name: string } | null | undefined;
}

/** Handlers for `NavigationContainer`. */
export interface ResponslyNavigationTracker {
  onReady: () => void;
  onStateChange: () => void;
}

type NavigationRefInput =
  | ResponslyNavigationRef
  | { readonly current: ResponslyNavigationRef | null | undefined };

function currentRouteName(navigationRef: NavigationRefInput): string | null {
  // createNavigationContainerRef() and useNavigationContainerRef() carry the methods themselves;
  // a plain React ref (useRef) carries them on `current`.
  const container =
    typeof (navigationRef as ResponslyNavigationRef)?.getCurrentRoute ===
    'function'
      ? (navigationRef as ResponslyNavigationRef)
      : (navigationRef as { current?: ResponslyNavigationRef | null })?.current;
  if (container == null || typeof container.getCurrentRoute !== 'function') {
    return null;
  }
  const name = container.getCurrentRoute()?.name;
  return typeof name === 'string' && name !== '' ? name : null;
}

/**
 * Reports the active React Navigation route as the current screen: calls `Responsly.screen()`
 * with the route name when navigation is ready and every time the active route changes.
 *
 * ```tsx
 * const navigationRef = createNavigationContainerRef();
 * const tracker = createNavigationTracker(navigationRef);
 *
 * <NavigationContainer
 *   ref={navigationRef}
 *   onReady={tracker.onReady}
 *   onStateChange={tracker.onStateChange}
 * />
 * ```
 */
export function createNavigationTracker(
  navigationRef: NavigationRefInput
): ResponslyNavigationTracker {
  let reported: string | null = null;

  const report = () => {
    const name = currentRouteName(navigationRef);
    if (name == null || name === reported) {
      return;
    }
    reported = name;
    Responsly.screen(name);
  };

  return { onReady: report, onStateChange: report };
}
