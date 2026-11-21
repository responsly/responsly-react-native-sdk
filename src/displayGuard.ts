import { toSurvey } from './events';
import { eventEmitter, native, type Subscription } from './native';
import type { ResponslyDisplayGuard } from './types';

const DISPLAY_GUARD_EVENT = 'ResponslyDisplayGuard';

interface DisplayGuardRequest {
  requestId?: unknown;
  survey?: unknown;
}

let displayGuard: ResponslyDisplayGuard | null = null;
let displayGuardSubscription: Subscription | null = null;

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

  if (
    result !== null &&
    (typeof result === 'object' || typeof result === 'function')
  ) {
    // Promise assimilation catches a throwing `then` getter and accepts only the first
    // settlement, including when a third-party promise implementation misbehaves.
    Promise.resolve(result).then(answer, (error: unknown) => {
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

export function setDisplayGuard(guard: ResponslyDisplayGuard | null): void {
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
