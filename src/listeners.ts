import { convertNativeEvent, type NativeEvent } from './events';
import { eventEmitter, type Subscription } from './native';
import type { ResponslyListener } from './types';

const EVENT = 'ResponslyEvent';

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

export function addListener(listener: ResponslyListener): () => void {
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
