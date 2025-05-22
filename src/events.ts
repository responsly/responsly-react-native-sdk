import type {
  ResponslyAnswer,
  ResponslyAnswerValue,
  ResponslyListener,
  ResponslyNotShownReason,
  ResponslyQuestionAnsweredEvent,
  ResponslySurvey,
  ResponslySurveyCompletedEvent,
  ResponslySurveyDismissedEvent,
  ResponslySurveyErrorEvent,
  ResponslySurveyNotShownEvent,
  ResponslySurveyShownEvent,
  ResponslyTrigger,
  ResponslyTriggerType,
} from './types';

/**
 * The native SDKs describe an event as a map with snake_case keys (`toDictionary()` on iOS,
 * `toMap()` on Android). The functions below turn those maps into the camelCase payloads of
 * the JavaScript API. Each event is converted field by field on purpose: an answer's `value`
 * is the respondent's data and must reach the app untouched, whatever keys it contains.
 */
type NativeMap = Record<string, unknown>;

function asMap(value: unknown): NativeMap {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    ? (value as NativeMap)
    : {};
}

function asString(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

function asAnswerValue(value: unknown): ResponslyAnswerValue {
  return value === undefined ? null : (value as ResponslyAnswerValue);
}

export function toSurvey(native: unknown): ResponslySurvey {
  const map = asMap(native);
  return { id: asString(map.id), name: asString(map.name) };
}

function toTrigger(native: unknown): ResponslyTrigger {
  const map = asMap(native);
  return {
    type: asString(map.type) as ResponslyTriggerType,
    // iOS leaves the name out and Android sends null when there is none.
    name: typeof map.name === 'string' ? map.name : null,
  };
}

function toAnswer(native: unknown): ResponslyAnswer {
  const map = asMap(native);
  return {
    questionId: asString(map.question_id),
    type: asString(map.type),
    title: asString(map.title),
    value: asAnswerValue(map.value),
  };
}

function toSurveyShown(native: unknown): ResponslySurveyShownEvent {
  const map = asMap(native);
  return { survey: toSurvey(map.survey), trigger: toTrigger(map.trigger) };
}

function toSurveyNotShown(native: unknown): ResponslySurveyNotShownEvent {
  const map = asMap(native);
  return {
    surveyId: asString(map.survey_id),
    reason: asString(map.reason) as ResponslyNotShownReason,
  };
}

function toQuestionAnswered(native: unknown): ResponslyQuestionAnsweredEvent {
  const map = asMap(native);
  return { surveyId: asString(map.survey_id), ...toAnswer(map) };
}

function toSurveyCompleted(native: unknown): ResponslySurveyCompletedEvent {
  const map = asMap(native);
  return {
    surveyId: asString(map.survey_id),
    responseId: asString(map.response_id),
    answers: Array.isArray(map.answers) ? map.answers.map(toAnswer) : [],
  };
}

function toSurveyDismissed(native: unknown): ResponslySurveyDismissedEvent {
  return { surveyId: asString(asMap(native).survey_id) };
}

function toSurveyError(native: unknown): ResponslySurveyErrorEvent {
  const map = asMap(native);
  return {
    surveyId: asString(map.survey_id),
    code: asString(map.code),
    message: asString(map.message),
  };
}

/** The `type` of a native `ResponslyEvent`, mapped to the listener callback and its payload. */
const EVENTS = {
  surveyShown: { callback: 'onSurveyShown', convert: toSurveyShown },
  surveyNotShown: { callback: 'onSurveyNotShown', convert: toSurveyNotShown },
  questionAnswered: {
    callback: 'onQuestionAnswered',
    convert: toQuestionAnswered,
  },
  surveyCompleted: {
    callback: 'onSurveyCompleted',
    convert: toSurveyCompleted,
  },
  surveyDismissed: {
    callback: 'onSurveyDismissed',
    convert: toSurveyDismissed,
  },
  surveyError: { callback: 'onSurveyError', convert: toSurveyError },
} as const;

export type NativeEventType = keyof typeof EVENTS;

/** What the native module emits as `ResponslyEvent`. */
export interface NativeEvent {
  type: string;
  payload?: unknown;
}

export interface ConvertedEvent {
  callback: keyof ResponslyListener;
  payload: unknown;
}

/** Converts a native event; `null` for a type this version does not know. */
export function convertNativeEvent(
  event: NativeEvent | null | undefined
): ConvertedEvent | null {
  const type = event?.type;
  if (
    typeof type !== 'string' ||
    !Object.prototype.hasOwnProperty.call(EVENTS, type)
  ) {
    return null;
  }
  const entry = EVENTS[type as NativeEventType];
  return { callback: entry.callback, payload: entry.convert(event?.payload) };
}
