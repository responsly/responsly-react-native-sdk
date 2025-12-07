import { DeviceEventEmitter } from 'react-native';
import Responsly from '../index';
import NativeModule from '../NativeResponslySdk';

jest.mock('../NativeResponslySdk');

const native = NativeModule as unknown as Record<string, jest.Mock>;

/** What the native module does when the SDK raises a callback. */
function emitNative(type: string, payload: unknown): void {
  DeviceEventEmitter.emit('ResponslyEvent', { type, payload });
}

const NATIVE_EVENTS = {
  surveyShown: {
    survey: { id: 'AbCd1234', name: 'NPS after purchase' },
    trigger: { type: 'screen', name: 'Checkout' },
  },
  surveyNotShown: { survey_id: 'AbCd1234', reason: 'paused' },
  questionAnswered: {
    survey_id: 'AbCd1234',
    question_id: 'q_1',
    type: 'nps',
    title: 'Score',
    value: 9,
  },
  surveyCompleted: {
    survey_id: 'AbCd1234',
    response_id: 'r_42',
    answers: [{ question_id: 'q_1', type: 'nps', title: 'Score', value: 9 }],
  },
  surveyDismissed: { survey_id: 'AbCd1234' },
  surveyError: { survey_id: 'AbCd1234', code: 'network', message: 'Offline' },
};

describe('addListener', () => {
  const cleanups: Array<() => void> = [];

  function listen(listener: Parameters<typeof Responsly.addListener>[0]) {
    const unsubscribe = Responsly.addListener(listener);
    cleanups.push(unsubscribe);
    return unsubscribe;
  }

  beforeEach(() => {
    jest.clearAllMocks();
  });

  afterEach(() => {
    cleanups.splice(0).forEach((unsubscribe) => unsubscribe());
  });

  it('delivers every callback with a camelCase payload', () => {
    const listener = {
      onSurveyShown: jest.fn(),
      onSurveyNotShown: jest.fn(),
      onQuestionAnswered: jest.fn(),
      onSurveyCompleted: jest.fn(),
      onSurveyDismissed: jest.fn(),
      onSurveyError: jest.fn(),
    };
    listen(listener);

    for (const [type, payload] of Object.entries(NATIVE_EVENTS)) {
      emitNative(type, payload);
    }

    expect(listener.onSurveyShown).toHaveBeenCalledWith({
      survey: { id: 'AbCd1234', name: 'NPS after purchase' },
      trigger: { type: 'screen', name: 'Checkout' },
    });
    expect(listener.onSurveyNotShown).toHaveBeenCalledWith({
      surveyId: 'AbCd1234',
      reason: 'paused',
    });
    expect(listener.onQuestionAnswered).toHaveBeenCalledWith({
      surveyId: 'AbCd1234',
      questionId: 'q_1',
      type: 'nps',
      title: 'Score',
      value: 9,
    });
    expect(listener.onSurveyCompleted).toHaveBeenCalledWith({
      surveyId: 'AbCd1234',
      responseId: 'r_42',
      answers: [{ questionId: 'q_1', type: 'nps', title: 'Score', value: 9 }],
    });
    expect(listener.onSurveyDismissed).toHaveBeenCalledWith({
      surveyId: 'AbCd1234',
    });
    expect(listener.onSurveyError).toHaveBeenCalledWith({
      surveyId: 'AbCd1234',
      code: 'network',
      message: 'Offline',
    });
    for (const callback of Object.values(listener)) {
      expect(callback).toHaveBeenCalledTimes(1);
    }
  });

  it('accepts a partial listener', () => {
    const onSurveyCompleted = jest.fn();
    listen({ onSurveyCompleted });

    expect(() => {
      emitNative('surveyShown', NATIVE_EVENTS.surveyShown);
      emitNative('surveyDismissed', NATIVE_EVENTS.surveyDismissed);
    }).not.toThrow();
    expect(onSurveyCompleted).not.toHaveBeenCalled();

    emitNative('surveyCompleted', NATIVE_EVENTS.surveyCompleted);
    expect(onSurveyCompleted).toHaveBeenCalledTimes(1);
  });

  it('works when the listener is added before initialize', () => {
    const onSurveyNotShown = jest.fn();
    listen({ onSurveyNotShown });
    expect(native.addListener).toHaveBeenCalledWith('ResponslyEvent');
    expect(native.initialize).not.toHaveBeenCalled();

    Responsly.initialize('rsk_test');
    emitNative('surveyNotShown', NATIVE_EVENTS.surveyNotShown);
    expect(onSurveyNotShown).toHaveBeenCalledTimes(1);
  });

  it('works when the listener is added after initialize', () => {
    Responsly.initialize('rsk_test');
    Responsly.showSurvey('AbCd1234');
    // Nobody listens yet: nothing to deliver, nothing breaks.
    emitNative('surveyNotShown', NATIVE_EVENTS.surveyNotShown);

    const onSurveyShown = jest.fn();
    listen({ onSurveyShown });
    emitNative('surveyShown', NATIVE_EVENTS.surveyShown);
    expect(onSurveyShown).toHaveBeenCalledTimes(1);
  });

  it('returns a function that removes the listener', () => {
    const onSurveyDismissed = jest.fn();
    const unsubscribe = Responsly.addListener({ onSurveyDismissed });

    emitNative('surveyDismissed', NATIVE_EVENTS.surveyDismissed);
    expect(onSurveyDismissed).toHaveBeenCalledTimes(1);

    expect(unsubscribe()).toBeUndefined();
    emitNative('surveyDismissed', NATIVE_EVENTS.surveyDismissed);
    expect(onSurveyDismissed).toHaveBeenCalledTimes(1);

    // Calling it again is harmless.
    expect(() => unsubscribe()).not.toThrow();
  });

  it('subscribes to the native module once and unsubscribes with the last listener', () => {
    const first = Responsly.addListener({});
    const second = Responsly.addListener({});
    expect(native.addListener).toHaveBeenCalledTimes(1);
    expect(native.addListener).toHaveBeenCalledWith('ResponslyEvent');

    first();
    expect(native.removeListeners).not.toHaveBeenCalled();
    second();
    expect(native.removeListeners).toHaveBeenCalledTimes(1);
    expect(native.removeListeners).toHaveBeenCalledWith(1);

    // A later listener subscribes again.
    const third = Responsly.addListener({});
    expect(native.addListener).toHaveBeenCalledTimes(2);
    third();
  });

  it('delivers to every listener, and removing one leaves the others', () => {
    const a = jest.fn();
    const b = jest.fn();
    const removeA = listen({ onSurveyShown: a });
    listen({ onSurveyShown: b });

    emitNative('surveyShown', NATIVE_EVENTS.surveyShown);
    removeA();
    emitNative('surveyShown', NATIVE_EVENTS.surveyShown);

    expect(a).toHaveBeenCalledTimes(1);
    expect(b).toHaveBeenCalledTimes(2);
  });

  it('treats the same object added twice as two registrations', () => {
    const listener = { onSurveyShown: jest.fn() };
    const first = listen(listener);
    listen(listener);

    emitNative('surveyShown', NATIVE_EVENTS.surveyShown);
    expect(listener.onSurveyShown).toHaveBeenCalledTimes(2);

    first();
    emitNative('surveyShown', NATIVE_EVENTS.surveyShown);
    expect(listener.onSurveyShown).toHaveBeenCalledTimes(3);
  });

  it('calls methods of a class instance with the instance as this', () => {
    class Recorder {
      shown: string[] = [];
      onSurveyShown(event: { survey: { id: string } }) {
        this.shown.push(event.survey.id);
      }
    }
    const recorder = new Recorder();
    listen(recorder);

    emitNative('surveyShown', NATIVE_EVENTS.surveyShown);
    expect(recorder.shown).toEqual(['AbCd1234']);
  });

  it('keeps delivering when a listener throws', () => {
    const error = jest.spyOn(console, 'error').mockImplementation(() => {});
    const after = jest.fn();
    listen({
      onSurveyShown: () => {
        throw new Error('boom');
      },
    });
    listen({ onSurveyShown: after });

    expect(() =>
      emitNative('surveyShown', NATIVE_EVENTS.surveyShown)
    ).not.toThrow();
    expect(after).toHaveBeenCalledTimes(1);
    expect(error).toHaveBeenCalledTimes(1);
    error.mockRestore();
  });

  it('lets a callback remove its own listener', () => {
    const calls: string[] = [];
    const unsubscribe = listen({
      onSurveyShown: () => {
        calls.push('first');
        unsubscribe();
      },
    });
    listen({ onSurveyShown: () => calls.push('second') });

    emitNative('surveyShown', NATIVE_EVENTS.surveyShown);
    emitNative('surveyShown', NATIVE_EVENTS.surveyShown);
    expect(calls).toEqual(['first', 'second', 'second']);
  });

  it('ignores native events of an unknown type', () => {
    const listener = { onSurveyShown: jest.fn(), onSurveyError: jest.fn() };
    listen(listener);
    expect(() => emitNative('surveySnoozed', {})).not.toThrow();
    expect(listener.onSurveyShown).not.toHaveBeenCalled();
    expect(listener.onSurveyError).not.toHaveBeenCalled();
  });
});
