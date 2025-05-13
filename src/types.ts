/** How much the SDK writes to the native log (Xcode console, Logcat). */
export type ResponslyLogLevel = 'none' | 'error' | 'info' | 'debug';

/** The container a survey is presented in. */
export type ResponslyPresentationStyle =
  'bottom_sheet' | 'dialog' | 'fullscreen';

/** Options of `Responsly.initialize`. */
export interface ResponslyOptions {
  /** Base address of the Responsly API. Defaults to `https://api.responsly.com`. */
  apiHost?: string;
  /** Defaults to `'error'`. */
  logLevel?: ResponslyLogLevel;
}

/** Options of `Responsly.showSurvey`. */
export interface ResponslyShowOptions {
  /** Skip audience, schedule, frequency and the display guard. Defaults to `false`. */
  force?: boolean;
  /** Hidden-field values for this survey only; they win over `setHiddenFields` for the same key. */
  hiddenFields?: Record<string, string>;
  /** Language for this survey only, for example `pl` or `pt-BR`. */
  locale?: string;
  /** Overrides the style set for the survey in Responsly. */
  presentation?: ResponslyPresentationStyle;
}

/**
 * A trait or event-property value. A `Date` is sent as an ISO 8601 UTC string with seconds
 * precision, for example `2026-11-10T12:00:00Z`.
 */
export type ResponslyValue = string | number | boolean | Date;

/** Traits of `identify` and `setAttributes`. A `null` value removes the trait. */
export type ResponslyTraits = Record<string, ResponslyValue | null | undefined>;

/** Properties of `track`. */
export type ResponslyProperties = Record<
  string,
  ResponslyValue | null | undefined
>;

/** A survey, as passed to the display guard and included in `onSurveyShown`. */
export interface ResponslySurvey {
  /** The Survey ID. */
  id: string;
  /** The form's name in Responsly. */
  name: string;
}

export type ResponslyTriggerType = 'event' | 'screen' | 'app_launch' | 'manual';

/** What caused a survey to be shown. */
export interface ResponslyTrigger {
  /** `manual` for `showSurvey`. */
  type: ResponslyTriggerType;
  /** The event or screen name; `null` for `app_launch` and `manual`. */
  name: string | null;
}

/** Why a survey was not shown. */
export type ResponslyNotShownReason =
  | 'disabled'
  | 'not_initialized'
  | 'config_unavailable'
  | 'survey_not_found'
  | 'another_survey_visible'
  | 'another_survey_selected'
  | 'paused'
  | 'outside_schedule'
  | 'platform_mismatch'
  | 'app_mismatch'
  | 'locale_mismatch'
  | 'audience_mismatch'
  | 'already_completed'
  | 'frequency_limit'
  | 'recontact_wait'
  | 'global_cooldown'
  | 'sampled_out'
  | 'screen_changed'
  | 'vetoed'
  | 'load_failed'
  | 'survey_unavailable';

/**
 * An answer as the survey stores it: a string, a number, a boolean, a list or a map, depending
 * on the question type. Number scales such as NPS give a number.
 */
export type ResponslyAnswerValue =
  | string
  | number
  | boolean
  | null
  | ResponslyAnswerValue[]
  | { [key: string]: ResponslyAnswerValue };

/** One answer of a completed survey. */
export interface ResponslyAnswer {
  questionId: string;
  /** The question's type, for example `nps`, `rating`, `multiple_choice`, `text`. */
  type: string;
  /** The question text as shown to the respondent. */
  title: string;
  value: ResponslyAnswerValue;
}

export interface ResponslySurveyShownEvent {
  survey: ResponslySurvey;
  trigger: ResponslyTrigger;
}

export interface ResponslySurveyNotShownEvent {
  surveyId: string;
  reason: ResponslyNotShownReason;
}

export interface ResponslyQuestionAnsweredEvent extends ResponslyAnswer {
  surveyId: string;
}

export interface ResponslySurveyCompletedEvent {
  surveyId: string;
  /** The ID of the saved response. */
  responseId: string;
  answers: ResponslyAnswer[];
}

export interface ResponslySurveyDismissedEvent {
  surveyId: string;
}

export interface ResponslySurveyErrorEvent {
  surveyId: string;
  /** A short error code. */
  code: string;
  /** A description for logs. Do not show it to users. */
  message: string;
}

/** Survey callbacks. Implement only the ones you need. */
export interface ResponslyListener {
  /** The survey became visible. */
  onSurveyShown?: (event: ResponslySurveyShownEvent) => void;
  /** A survey matched a trigger, or was requested with `showSurvey`, but was not shown. */
  onSurveyNotShown?: (event: ResponslySurveyNotShownEvent) => void;
  /** The respondent answered a question. */
  onQuestionAnswered?: (event: ResponslyQuestionAnsweredEvent) => void;
  /** The respondent submitted the survey. */
  onSurveyCompleted?: (event: ResponslySurveyCompletedEvent) => void;
  /** The survey was closed before it was completed. */
  onSurveyDismissed?: (event: ResponslySurveyDismissedEvent) => void;
  /** The survey reported an error while it was visible. */
  onSurveyError?: (event: ResponslySurveyErrorEvent) => void;
}

/**
 * Asked right before a survey becomes visible. Return `false` (or a promise of `false`) to veto
 * it. No answer within 2 seconds, or a guard that throws or rejects, means the survey is shown.
 */
export type ResponslyDisplayGuard = (
  survey: ResponslySurvey
) => boolean | Promise<boolean>;
