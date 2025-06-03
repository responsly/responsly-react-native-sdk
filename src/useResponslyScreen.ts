import { useEffect } from 'react';
import Responsly from './Responsly';

/**
 * Reports a screen from inside a component: calls `Responsly.screen(name)` when the component
 * mounts and whenever `name` changes.
 *
 * ```tsx
 * function CheckoutScreen() {
 *   useResponslyScreen('Checkout');
 *   return <CheckoutForm />;
 * }
 * ```
 */
export function useResponslyScreen(name: string): void {
  useEffect(() => {
    if (typeof name === 'string' && name !== '') {
      Responsly.screen(name);
    }
  }, [name]);
}
