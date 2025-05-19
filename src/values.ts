import type {
  ResponslyOptions,
  ResponslyProperties,
  ResponslyShowOptions,
  ResponslyTraits,
} from './types';

/** A value as it crosses to the native side. */
export type NativeValue = string | number | boolean;

export interface NativeTraits {
  values: Record<string, NativeValue>;
  /** Keys whose value was `null`: the traits to remove. */
  removed: string[];
}

const LOG_LEVELS = ['none', 'error', 'info', 'debug'];
const PRESENTATION_STYLES = ['bottom_sheet', 'dialog', 'fullscreen'];

function warn(message: string): void {
  if (__DEV__) {
    console.warn(`[Responsly] ${message}`);
  }
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/**
 * ISO 8601 in UTC with seconds precision, for example `2026-11-10T12:00:00Z`: the format the
 * native SDKs store and compare dates in. `null` for an invalid date.
 */
export function toISODate(date: Date): string | null {
  const time = date.getTime();
  if (Number.isNaN(time)) {
    return null;
  }
  return new Date(time).toISOString().replace(/\.\d{3}Z$/, 'Z');
}

/** A string, finite number, boolean or valid date; `undefined` for anything else. */
function toNativeValue(value: unknown): NativeValue | undefined {
  switch (typeof value) {
    case 'string':
    case 'boolean':
      return value;
    case 'number':
      return Number.isFinite(value) ? value : undefined;
    case 'object':
      if (value instanceof Date) {
        return toISODate(value) ?? undefined;
      }
      return undefined;
    default:
      return undefined;
  }
}

/**
 * Traits for `identify` and `setAttributes`: dates become ISO strings, `null` values become
 * removals, `undefined` values are left out and unsupported values are skipped.
 */
export function toNativeTraits(
  traits: ResponslyTraits | null | undefined
): NativeTraits {
  const values: Record<string, NativeValue> = {};
  const removed: string[] = [];
  if (!isPlainObject(traits)) {
    return { values, removed };
  }
  for (const key of Object.keys(traits)) {
    const raw = traits[key];
    if (raw === undefined) {
      continue;
    }
    if (raw === null) {
      removed.push(key);
      continue;
    }
    const value = toNativeValue(raw);
    if (value === undefined) {
      warn(
        `The trait "${key}" was skipped: values are strings, finite numbers, booleans or valid dates.`
      );
      continue;
    }
    values[key] = value;
  }
  return { values, removed };
}

/** Properties for `track`: like traits, but `null` values are simply left out. */
export function toNativeProperties(
  properties: ResponslyProperties | null | undefined
): Record<string, NativeValue> {
  const values: Record<string, NativeValue> = {};
  if (!isPlainObject(properties)) {
    return values;
  }
  for (const key of Object.keys(properties)) {
    const raw = properties[key];
    if (raw === undefined || raw === null) {
      continue;
    }
    const value = toNativeValue(raw);
    if (value === undefined) {
      warn(
        `The property "${key}" was skipped: values are strings, finite numbers, booleans or valid dates.`
      );
      continue;
    }
    values[key] = value;
  }
  return values;
}

/** Hidden fields are text: numbers and booleans are written out, everything else is skipped. */
export function toNativeHiddenFields(
  fields: Record<string, unknown> | null | undefined
): Record<string, string> {
  const result: Record<string, string> = {};
  if (!isPlainObject(fields)) {
    return result;
  }
  for (const key of Object.keys(fields)) {
    const raw = fields[key];
    if (typeof raw === 'string') {
      result[key] = raw;
    } else if (typeof raw === 'boolean') {
      result[key] = String(raw);
    } else if (typeof raw === 'number' && Number.isFinite(raw)) {
      result[key] = String(raw);
    } else if (raw !== undefined && raw !== null) {
      warn(`The hidden field "${key}" was skipped: values are strings.`);
    }
  }
  return result;
}

export interface NativeOptions {
  apiHost?: string;
  logLevel?: string;
}

/** Only the fields that were set, so the native defaults apply to the rest. */
export function toNativeOptions(
  options: ResponslyOptions | null | undefined
): NativeOptions {
  const result: NativeOptions = {};
  if (!isPlainObject(options)) {
    return result;
  }
  if (typeof options.apiHost === 'string' && options.apiHost !== '') {
    result.apiHost = options.apiHost;
  }
  if (typeof options.logLevel === 'string') {
    if (LOG_LEVELS.includes(options.logLevel)) {
      result.logLevel = options.logLevel;
    } else {
      warn(
        `Unknown logLevel "${options.logLevel}": use 'none', 'error', 'info' or 'debug'.`
      );
    }
  }
  return result;
}

export interface NativeShowOptions {
  force: boolean;
  hiddenFields: Record<string, string>;
  locale?: string;
  presentation?: string;
}

export function toNativeShowOptions(
  options: ResponslyShowOptions | null | undefined
): NativeShowOptions {
  const source: Record<string, unknown> = isPlainObject(options) ? options : {};
  const result: NativeShowOptions = {
    force: source.force === true,
    hiddenFields: toNativeHiddenFields(
      isPlainObject(source.hiddenFields) ? source.hiddenFields : undefined
    ),
  };
  if (typeof source.locale === 'string' && source.locale !== '') {
    result.locale = source.locale;
  }
  if (typeof source.presentation === 'string') {
    if (PRESENTATION_STYLES.includes(source.presentation)) {
      result.presentation = source.presentation;
    } else {
      warn(
        `Unknown presentation "${source.presentation}": use 'bottom_sheet', 'dialog' or 'fullscreen'.`
      );
    }
  }
  return result;
}
