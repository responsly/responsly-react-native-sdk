import { DeviceEventEmitter } from 'react-native';
import Responsly from '../index';
import NativeModule from '../NativeResponslySdk';

jest.mock('../NativeResponslySdk');

const native = NativeModule as unknown as Record<string, jest.Mock>;

const SURVEY = { id: 'AbCd1234', name: 'NPS after purchase' };

/** What the native guard does when a survey is about to appear. */
function askGuard(requestId: string, survey: unknown = SURVEY): void {
  DeviceEventEmitter.emit('ResponslyDisplayGuard', { requestId, survey });
}

/** Lets promise callbacks run. */
async function flush(): Promise<void> {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve();
  }
}

describe('setDisplayGuard', () => {
  let error: jest.SpyInstance;

  beforeEach(() => {
    jest.clearAllMocks();
    error = jest.spyOn(console, 'error').mockImplementation(() => {});
  });

  afterEach(() => {
    Responsly.setDisplayGuard(null);
    error.mockRestore();
  });

  it('installs the native guard after subscribing to its questions', () => {
    const order: string[] = [];
    native.addListener!.mockImplementation((name: string) => {
      order.push(`addListener:${name}`);
    });
    native.setDisplayGuardEnabled!.mockImplementation((enabled: boolean) => {
      order.push(`setDisplayGuardEnabled:${enabled}`);
    });

    Responsly.setDisplayGuard(() => true);

    expect(order).toEqual([
      'addListener:ResponslyDisplayGuard',
      'setDisplayGuardEnabled:true',
    ]);
  });

  it('null removes the native guard and the subscription', () => {
    Responsly.setDisplayGuard(() => true);
    jest.clearAllMocks();

    Responsly.setDisplayGuard(null);

    expect(native.setDisplayGuardEnabled).toHaveBeenCalledTimes(1);
    expect(native.setDisplayGuardEnabled).toHaveBeenCalledWith(false);
    expect(native.removeListeners).toHaveBeenCalledWith(1);

    askGuard('late');
    expect(native.resolveDisplayGuard).not.toHaveBeenCalled();
  });

  it('passes the survey to the guard', () => {
    const guard = jest.fn(() => true);
    Responsly.setDisplayGuard(guard);

    askGuard('req-1');

    expect(guard).toHaveBeenCalledTimes(1);
    expect(guard).toHaveBeenCalledWith({
      id: 'AbCd1234',
      name: 'NPS after purchase',
    });
  });

  it('answers a synchronous true with allow', () => {
    Responsly.setDisplayGuard(() => true);
    askGuard('req-1');
    expect(native.resolveDisplayGuard).toHaveBeenCalledTimes(1);
    expect(native.resolveDisplayGuard).toHaveBeenCalledWith('req-1', true);
  });

  it('answers a synchronous false with veto', () => {
    Responsly.setDisplayGuard(() => false);
    askGuard('req-2');
    expect(native.resolveDisplayGuard).toHaveBeenCalledTimes(1);
    expect(native.resolveDisplayGuard).toHaveBeenCalledWith('req-2', false);
  });

  it('can decide per survey', () => {
    Responsly.setDisplayGuard((survey) => survey.id === 'AbCd1234');
    askGuard('req-a', { id: 'AbCd1234', name: 'A' });
    askGuard('req-b', { id: 'ZzZz9999', name: 'B' });
    expect(native.resolveDisplayGuard).toHaveBeenNthCalledWith(
      1,
      'req-a',
      true
    );
    expect(native.resolveDisplayGuard).toHaveBeenNthCalledWith(
      2,
      'req-b',
      false
    );
  });

  it('waits for a promise and answers with its value', async () => {
    let decide: (allow: boolean) => void = () => {};
    Responsly.setDisplayGuard(
      () =>
        new Promise<boolean>((resolve) => {
          decide = resolve;
        })
    );

    askGuard('req-3');
    await flush();
    expect(native.resolveDisplayGuard).not.toHaveBeenCalled();

    decide(false);
    await flush();
    expect(native.resolveDisplayGuard).toHaveBeenCalledTimes(1);
    expect(native.resolveDisplayGuard).toHaveBeenCalledWith('req-3', false);
  });

  it('answers an async guard that resolves to true with allow', async () => {
    Responsly.setDisplayGuard(async () => true);
    askGuard('req-4');
    await flush();
    expect(native.resolveDisplayGuard).toHaveBeenCalledWith('req-4', true);
  });

  it('allows when the guard throws', () => {
    Responsly.setDisplayGuard(() => {
      throw new Error('boom');
    });
    expect(() => askGuard('req-5')).not.toThrow();
    expect(native.resolveDisplayGuard).toHaveBeenCalledTimes(1);
    expect(native.resolveDisplayGuard).toHaveBeenCalledWith('req-5', true);
    expect(error).toHaveBeenCalledTimes(1);
  });

  it('allows when the guard rejects', async () => {
    Responsly.setDisplayGuard(() => Promise.reject(new Error('boom')));
    askGuard('req-6');
    await flush();
    expect(native.resolveDisplayGuard).toHaveBeenCalledTimes(1);
    expect(native.resolveDisplayGuard).toHaveBeenCalledWith('req-6', true);
    expect(error).toHaveBeenCalledTimes(1);
  });

  it('allows when reading the returned promise throws', async () => {
    Responsly.setDisplayGuard((() => ({
      get then() {
        throw new Error('broken promise');
      },
    })) as never);

    expect(() => askGuard('broken-promise')).not.toThrow();
    await flush();

    expect(native.resolveDisplayGuard).toHaveBeenCalledTimes(1);
    expect(native.resolveDisplayGuard).toHaveBeenCalledWith(
      'broken-promise',
      true
    );
  });

  it('answers once when a returned thenable settles more than once', async () => {
    Responsly.setDisplayGuard((() => ({
      then(resolve: (allow: boolean) => void, reject: (error: Error) => void) {
        resolve(false);
        resolve(true);
        reject(new Error('already settled'));
      },
    })) as never);

    askGuard('multiple-answers');
    await flush();

    expect(native.resolveDisplayGuard).toHaveBeenCalledTimes(1);
    expect(native.resolveDisplayGuard).toHaveBeenCalledWith(
      'multiple-answers',
      false
    );
  });

  it('allows unless the answer is exactly false', async () => {
    Responsly.setDisplayGuard((() => undefined) as never);
    askGuard('req-7');
    expect(native.resolveDisplayGuard).toHaveBeenLastCalledWith('req-7', true);

    Responsly.setDisplayGuard((async () => 0) as never);
    askGuard('req-8');
    await flush();
    expect(native.resolveDisplayGuard).toHaveBeenLastCalledWith('req-8', true);
  });

  it('uses the latest guard and subscribes only once', () => {
    const first = jest.fn(() => true);
    const second = jest.fn(() => false);
    Responsly.setDisplayGuard(first);
    Responsly.setDisplayGuard(second);

    expect(native.addListener).toHaveBeenCalledTimes(1);

    askGuard('req-9');
    expect(first).not.toHaveBeenCalled();
    expect(second).toHaveBeenCalledTimes(1);
    expect(native.resolveDisplayGuard).toHaveBeenCalledTimes(1);
    expect(native.resolveDisplayGuard).toHaveBeenCalledWith('req-9', false);
  });

  it('answers each question once, with its own request id', async () => {
    Responsly.setDisplayGuard(async (survey) => survey.id !== 'blocked');
    askGuard('req-10', { id: 'blocked', name: '' });
    askGuard('req-11', { id: 'open', name: '' });
    await flush();
    expect(native.resolveDisplayGuard).toHaveBeenCalledTimes(2);
    expect(native.resolveDisplayGuard).toHaveBeenCalledWith('req-10', false);
    expect(native.resolveDisplayGuard).toHaveBeenCalledWith('req-11', true);
  });

  it('ignores a question without a request id', () => {
    const guard = jest.fn(() => false);
    Responsly.setDisplayGuard(guard);
    DeviceEventEmitter.emit('ResponslyDisplayGuard', { survey: SURVEY });
    DeviceEventEmitter.emit('ResponslyDisplayGuard', null);
    expect(guard).not.toHaveBeenCalled();
    expect(native.resolveDisplayGuard).not.toHaveBeenCalled();
  });

  it('does not interfere with listeners', () => {
    const onSurveyNotShown = jest.fn();
    const unsubscribe = Responsly.addListener({ onSurveyNotShown });
    Responsly.setDisplayGuard(() => false);

    askGuard('req-12');
    DeviceEventEmitter.emit('ResponslyEvent', {
      type: 'surveyNotShown',
      payload: { survey_id: 'AbCd1234', reason: 'vetoed' },
    });

    expect(onSurveyNotShown).toHaveBeenCalledWith({
      surveyId: 'AbCd1234',
      reason: 'vetoed',
    });
    unsubscribe();
  });
});
