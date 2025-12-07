import { convertNativeEvent } from '../events';

describe('native event maps (snake_case) become camelCase payloads', () => {
  it('surveyShown', () => {
    expect(
      convertNativeEvent({
        type: 'surveyShown',
        payload: {
          survey: { id: 'AbCd1234', name: 'NPS after purchase' },
          trigger: { type: 'event', name: 'purchase_completed' },
        },
      })
    ).toEqual({
      callback: 'onSurveyShown',
      payload: {
        survey: { id: 'AbCd1234', name: 'NPS after purchase' },
        trigger: { type: 'event', name: 'purchase_completed' },
      },
    });
  });

  it('surveyShown: a trigger without a name gets name null (iOS omits it, Android sends null)', () => {
    const ios = convertNativeEvent({
      type: 'surveyShown',
      payload: {
        survey: { id: 'AbCd1234', name: 'NPS' },
        trigger: { type: 'manual' },
      },
    });
    const android = convertNativeEvent({
      type: 'surveyShown',
      payload: {
        survey: { id: 'AbCd1234', name: 'NPS' },
        trigger: { type: 'app_launch', name: null },
      },
    });
    expect(ios?.payload).toEqual({
      survey: { id: 'AbCd1234', name: 'NPS' },
      trigger: { type: 'manual', name: null },
    });
    expect(android?.payload).toEqual({
      survey: { id: 'AbCd1234', name: 'NPS' },
      trigger: { type: 'app_launch', name: null },
    });
  });

  it('surveyNotShown', () => {
    expect(
      convertNativeEvent({
        type: 'surveyNotShown',
        payload: { survey_id: 'AbCd1234', reason: 'frequency_limit' },
      })
    ).toEqual({
      callback: 'onSurveyNotShown',
      payload: { surveyId: 'AbCd1234', reason: 'frequency_limit' },
    });
  });

  it('questionAnswered', () => {
    expect(
      convertNativeEvent({
        type: 'questionAnswered',
        payload: {
          survey_id: 'AbCd1234',
          question_id: 'q_1',
          type: 'nps',
          title: 'How likely are you to recommend us?',
          value: 9,
        },
      })
    ).toEqual({
      callback: 'onQuestionAnswered',
      payload: {
        surveyId: 'AbCd1234',
        questionId: 'q_1',
        type: 'nps',
        title: 'How likely are you to recommend us?',
        value: 9,
      },
    });
  });

  it('surveyCompleted, with every answer converted', () => {
    expect(
      convertNativeEvent({
        type: 'surveyCompleted',
        payload: {
          survey_id: 'AbCd1234',
          response_id: 'r_42',
          answers: [
            { question_id: 'q_1', type: 'nps', title: 'Score', value: 10 },
            { question_id: 'q_2', type: 'text', title: 'Why?', value: 'Fast' },
          ],
        },
      })
    ).toEqual({
      callback: 'onSurveyCompleted',
      payload: {
        surveyId: 'AbCd1234',
        responseId: 'r_42',
        answers: [
          { questionId: 'q_1', type: 'nps', title: 'Score', value: 10 },
          { questionId: 'q_2', type: 'text', title: 'Why?', value: 'Fast' },
        ],
      },
    });
  });

  it('surveyDismissed', () => {
    expect(
      convertNativeEvent({
        type: 'surveyDismissed',
        payload: { survey_id: 'AbCd1234' },
      })
    ).toEqual({
      callback: 'onSurveyDismissed',
      payload: { surveyId: 'AbCd1234' },
    });
  });

  it('surveyError', () => {
    expect(
      convertNativeEvent({
        type: 'surveyError',
        payload: {
          survey_id: 'AbCd1234',
          code: 'network',
          message: 'The next page could not be loaded',
        },
      })
    ).toEqual({
      callback: 'onSurveyError',
      payload: {
        surveyId: 'AbCd1234',
        code: 'network',
        message: 'The next page could not be loaded',
      },
    });
  });

  it('leaves an answer value untouched, whatever its keys look like', () => {
    const value = {
      first_choice: ['option_a', 'option_b'],
      other_text: null,
      nested_map: { inner_key: true, count: 2 },
    };
    const answered = convertNativeEvent({
      type: 'questionAnswered',
      payload: {
        survey_id: 's',
        question_id: 'q',
        type: 'matrix',
        title: 't',
        value,
      },
    });
    expect((answered?.payload as { value: unknown }).value).toBe(value);

    const completed = convertNativeEvent({
      type: 'surveyCompleted',
      payload: {
        survey_id: 's',
        response_id: 'r',
        answers: [{ question_id: 'q', type: 'matrix', title: 't', value }],
      },
    });
    expect(
      (completed?.payload as { answers: { value: unknown }[] }).answers[0]
        ?.value
    ).toBe(value);
  });

  it('keeps every kind of answer value: string, number, boolean, list, null', () => {
    for (const value of ['text', 7, 0, false, ['a', 'b'], null]) {
      const converted = convertNativeEvent({
        type: 'questionAnswered',
        payload: {
          survey_id: 's',
          question_id: 'q',
          type: 't',
          title: '',
          value,
        },
      });
      expect((converted?.payload as { value: unknown }).value).toEqual(value);
    }
  });

  it('a missing answer value becomes null', () => {
    const converted = convertNativeEvent({
      type: 'questionAnswered',
      payload: { survey_id: 's', question_id: 'q', type: 't', title: '' },
    });
    expect((converted?.payload as { value: unknown }).value).toBeNull();
  });

  it('has no snake_case keys left in any payload', () => {
    const payloads = [
      convertNativeEvent({
        type: 'surveyNotShown',
        payload: { survey_id: 's', reason: 'paused' },
      }),
      convertNativeEvent({
        type: 'questionAnswered',
        payload: {
          survey_id: 's',
          question_id: 'q',
          type: 't',
          title: '',
          value: 1,
        },
      }),
      convertNativeEvent({
        type: 'surveyCompleted',
        payload: {
          survey_id: 's',
          response_id: 'r',
          answers: [{ question_id: 'q', type: 't', title: '', value: 1 }],
        },
      }),
      convertNativeEvent({
        type: 'surveyDismissed',
        payload: { survey_id: 's' },
      }),
      convertNativeEvent({
        type: 'surveyError',
        payload: { survey_id: 's', code: 'c', message: 'm' },
      }),
    ];
    expect(JSON.stringify(payloads)).not.toMatch(/_id"/);
  });

  it('ignores event types it does not know, and malformed events', () => {
    expect(
      convertNativeEvent({ type: 'surveySnoozed', payload: {} })
    ).toBeNull();
    expect(convertNativeEvent({ type: 'toString', payload: {} })).toBeNull();
    expect(convertNativeEvent(null)).toBeNull();
    expect(convertNativeEvent(undefined)).toBeNull();
    expect(convertNativeEvent({} as never)).toBeNull();
  });

  it('survives a payload with missing fields', () => {
    expect(convertNativeEvent({ type: 'surveyCompleted' })).toEqual({
      callback: 'onSurveyCompleted',
      payload: { surveyId: '', responseId: '', answers: [] },
    });
  });
});
