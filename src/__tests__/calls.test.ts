import Responsly from '../index';
import NativeModule from '../NativeResponslySdk';

jest.mock('../NativeResponslySdk');

const native = NativeModule as unknown as Record<string, jest.Mock>;

describe('calls are forwarded to the native module', () => {
  let warn: jest.SpyInstance;

  beforeEach(() => {
    jest.clearAllMocks();
    warn = jest.spyOn(console, 'warn').mockImplementation(() => {});
  });

  afterEach(() => {
    warn.mockRestore();
  });

  describe('initialize', () => {
    it('passes the key and the options', () => {
      Responsly.initialize('rsk_test', {
        apiHost: 'https://api.responsly.com',
        logLevel: 'debug',
      });
      expect(native.initialize).toHaveBeenCalledTimes(1);
      expect(native.initialize).toHaveBeenCalledWith('rsk_test', {
        apiHost: 'https://api.responsly.com',
        logLevel: 'debug',
      });
    });

    it('sends an empty options object when there are none, so the native defaults apply', () => {
      Responsly.initialize('rsk_test');
      expect(native.initialize).toHaveBeenCalledWith('rsk_test', {});
    });

    it('leaves out fields that are not set', () => {
      Responsly.initialize('rsk_test', { logLevel: 'error' });
      expect(native.initialize).toHaveBeenCalledWith('rsk_test', {
        logLevel: 'error',
      });

      Responsly.initialize('rsk_test', {
        apiHost: undefined,
        logLevel: undefined,
      });
      expect(native.initialize).toHaveBeenLastCalledWith('rsk_test', {});
    });

    it('drops an unknown log level with a warning', () => {
      Responsly.initialize('rsk_test', { logLevel: 'verbose' as never });
      expect(native.initialize).toHaveBeenCalledWith('rsk_test', {});
      expect(warn).toHaveBeenCalledTimes(1);
    });
  });

  describe('identify', () => {
    it('passes the user id, the trait values and no removals', () => {
      Responsly.identify('user-123', { plan: 'pro', seats: 5, beta: true });
      expect(native.identify).toHaveBeenCalledWith(
        'user-123',
        { plan: 'pro', seats: 5, beta: true },
        []
      );
    });

    it('works without traits', () => {
      Responsly.identify('user-123');
      expect(native.identify).toHaveBeenCalledWith('user-123', {}, []);
    });

    it('sends a Date as an ISO 8601 UTC string with seconds precision', () => {
      Responsly.identify('user-123', {
        signedUp: new Date('2026-11-10T12:00:00.789Z'),
        // 14:30:15 at +02:00 is 12:30:15 UTC
        renewed: new Date('2026-11-10T14:30:15+02:00'),
      });
      expect(native.identify).toHaveBeenCalledWith(
        'user-123',
        { signedUp: '2026-11-10T12:00:00Z', renewed: '2026-11-10T12:30:15Z' },
        []
      );
    });

    it('turns null values into removals', () => {
      Responsly.identify('user-123', { plan: 'pro', beta: null });
      expect(native.identify).toHaveBeenCalledWith(
        'user-123',
        { plan: 'pro' },
        ['beta']
      );
    });
  });

  describe('setAttributes', () => {
    it('passes values and lists null values as removed', () => {
      Responsly.setAttributes({ plan: 'team', beta: null });
      expect(native.setAttributes).toHaveBeenCalledTimes(1);
      expect(native.setAttributes).toHaveBeenCalledWith({ plan: 'team' }, [
        'beta',
      ]);
    });

    it('leaves undefined values out without removing the trait', () => {
      Responsly.setAttributes({ plan: undefined, seats: 3 });
      expect(native.setAttributes).toHaveBeenCalledWith({ seats: 3 }, []);
    });

    it('skips values that are not strings, finite numbers, booleans or valid dates', () => {
      Responsly.setAttributes({
        ok: 'yes',
        nan: NaN,
        infinite: Infinity,
        invalidDate: new Date('not a date'),
        list: ['a'] as never,
        object: { a: 1 } as never,
        fn: (() => 1) as never,
      });
      expect(native.setAttributes).toHaveBeenCalledWith({ ok: 'yes' }, []);
      expect(warn).toHaveBeenCalledTimes(6);
    });
  });

  describe('track', () => {
    it('passes the event name and the properties', () => {
      Responsly.track('purchase_completed', { value: 49.9, category: 'books' });
      expect(native.track).toHaveBeenCalledWith('purchase_completed', {
        value: 49.9,
        category: 'books',
      });
    });

    it('works without properties', () => {
      Responsly.track('app_opened');
      expect(native.track).toHaveBeenCalledWith('app_opened', {});
    });

    it('converts dates and leaves null and undefined properties out', () => {
      Responsly.track('trial_started', {
        at: new Date('2026-01-02T03:04:05.006Z'),
        coupon: null,
        campaign: undefined,
        paid: false,
        count: 0,
        note: '',
      });
      expect(native.track).toHaveBeenCalledWith('trial_started', {
        at: '2026-01-02T03:04:05Z',
        paid: false,
        count: 0,
        note: '',
      });
    });
  });

  it('screen passes the name', () => {
    Responsly.screen('Checkout');
    expect(native.screen).toHaveBeenCalledWith('Checkout');
  });

  describe('showSurvey', () => {
    it('passes every option', () => {
      Responsly.showSurvey('AbCd1234', {
        force: false,
        hiddenFields: { order_id: 'A-77' },
        locale: 'pl',
        presentation: 'dialog',
      });
      expect(native.showSurvey).toHaveBeenCalledWith('AbCd1234', {
        force: false,
        hiddenFields: { order_id: 'A-77' },
        locale: 'pl',
        presentation: 'dialog',
      });
    });

    it('fills in force and hiddenFields when options are missing or empty', () => {
      Responsly.showSurvey('AbCd1234');
      expect(native.showSurvey).toHaveBeenLastCalledWith('AbCd1234', {
        force: false,
        hiddenFields: {},
      });

      Responsly.showSurvey('AbCd1234', {});
      expect(native.showSurvey).toHaveBeenLastCalledWith('AbCd1234', {
        force: false,
        hiddenFields: {},
      });
    });

    it('passes force', () => {
      Responsly.showSurvey('AbCd1234', { force: true });
      expect(native.showSurvey).toHaveBeenCalledWith('AbCd1234', {
        force: true,
        hiddenFields: {},
      });
    });

    it('accepts each presentation style and drops an unknown one', () => {
      for (const presentation of [
        'bottom_sheet',
        'dialog',
        'fullscreen',
      ] as const) {
        Responsly.showSurvey('AbCd1234', { presentation });
        expect(native.showSurvey).toHaveBeenLastCalledWith('AbCd1234', {
          force: false,
          hiddenFields: {},
          presentation,
        });
      }

      Responsly.showSurvey('AbCd1234', { presentation: 'popup' as never });
      expect(native.showSurvey).toHaveBeenLastCalledWith('AbCd1234', {
        force: false,
        hiddenFields: {},
      });
      expect(warn).toHaveBeenCalledTimes(1);
    });
  });

  it('closeSurvey, pause, resume and reset take no arguments', () => {
    Responsly.closeSurvey();
    Responsly.pause();
    Responsly.resume();
    Responsly.reset();
    expect(native.closeSurvey).toHaveBeenCalledWith();
    expect(native.pause).toHaveBeenCalledWith();
    expect(native.resume).toHaveBeenCalledWith();
    expect(native.reset).toHaveBeenCalledWith();
    for (const name of ['closeSurvey', 'pause', 'resume', 'reset']) {
      expect(native[name]).toHaveBeenCalledTimes(1);
    }
  });

  it('setLocale passes the tag, and null for the device language', () => {
    Responsly.setLocale('pl');
    expect(native.setLocale).toHaveBeenLastCalledWith('pl');
    Responsly.setLocale(null);
    expect(native.setLocale).toHaveBeenLastCalledWith(null);
  });

  describe('setHiddenFields', () => {
    it('passes the map', () => {
      Responsly.setHiddenFields({ order_id: 'A-77' });
      expect(native.setHiddenFields).toHaveBeenCalledWith({ order_id: 'A-77' });
    });

    it('writes numbers and booleans as text and leaves out the rest', () => {
      Responsly.setHiddenFields({
        order_id: 77,
        vip: true,
        missing: null,
        nothing: undefined,
        nested: { a: 1 },
      } as never);
      expect(native.setHiddenFields).toHaveBeenCalledWith({
        order_id: '77',
        vip: 'true',
      });
    });
  });

  it('setTrackingEnabled passes the flag', () => {
    Responsly.setTrackingEnabled(false);
    expect(native.setTrackingEnabled).toHaveBeenLastCalledWith(false);
    Responsly.setTrackingEnabled(true);
    expect(native.setTrackingEnabled).toHaveBeenLastCalledWith(true);
  });

  it('methods can be used without the object', () => {
    const { track, screen } = Responsly;
    track('purchase_completed');
    screen('Checkout');
    expect(native.track).toHaveBeenCalledWith('purchase_completed', {});
    expect(native.screen).toHaveBeenCalledWith('Checkout');
  });

  it('calls made before initialize are forwarded right away, in order', () => {
    const order: string[] = [];
    for (const name of ['setTrackingEnabled', 'identify', 'initialize']) {
      native[name]!.mockImplementation(() => {
        order.push(name);
      });
    }
    Responsly.setTrackingEnabled(true);
    Responsly.identify('user-123');
    Responsly.initialize('rsk_test');
    expect(order).toEqual(['setTrackingEnabled', 'identify', 'initialize']);
  });
});
