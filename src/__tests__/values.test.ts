import { toISODate, toNativeTraits } from '../values';

describe('toISODate', () => {
  it('formats in UTC with seconds precision', () => {
    expect(toISODate(new Date(Date.UTC(2026, 10, 10, 12, 0, 0)))).toBe(
      '2026-11-10T12:00:00Z'
    );
  });

  it('drops milliseconds without rounding up', () => {
    expect(toISODate(new Date('2026-11-10T12:00:00.999Z'))).toBe(
      '2026-11-10T12:00:00Z'
    );
  });

  it('converts other time zones to UTC', () => {
    expect(toISODate(new Date('2026-11-10T01:15:30-05:00'))).toBe(
      '2026-11-10T06:15:30Z'
    );
  });

  it('gives null for an invalid date', () => {
    expect(toISODate(new Date(NaN))).toBeNull();
  });
});

describe('toNativeTraits', () => {
  let warn: jest.SpyInstance;

  beforeEach(() => {
    warn = jest.spyOn(console, 'warn').mockImplementation(() => {});
  });

  afterEach(() => {
    warn.mockRestore();
  });

  it('keeps strings, numbers and booleans as they are', () => {
    expect(
      toNativeTraits({ plan: 'pro', seats: 5, ratio: 0.5, beta: false, s: '' })
    ).toEqual({
      values: { plan: 'pro', seats: 5, ratio: 0.5, beta: false, s: '' },
      removed: [],
    });
  });

  it('separates removals from values', () => {
    expect(toNativeTraits({ a: null, b: 'x', c: null })).toEqual({
      values: { b: 'x' },
      removed: ['a', 'c'],
    });
  });

  it('gives empty results for a missing or malformed map', () => {
    const empty = { values: {}, removed: [] };
    expect(toNativeTraits(undefined)).toEqual(empty);
    expect(toNativeTraits(null)).toEqual(empty);
    expect(toNativeTraits('traits' as never)).toEqual(empty);
    expect(toNativeTraits(['a'] as never)).toEqual(empty);
  });

  it('does not keep a string that looks like a date as anything but a string', () => {
    expect(toNativeTraits({ at: '2026-11-10T12:00:00Z' }).values).toEqual({
      at: '2026-11-10T12:00:00Z',
    });
  });
});
