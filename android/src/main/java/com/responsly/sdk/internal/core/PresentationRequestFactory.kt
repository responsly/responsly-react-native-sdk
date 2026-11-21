package com.responsly.sdk.internal.core

import com.responsly.sdk.ShowOptions
import com.responsly.sdk.SurveyTrigger
import com.responsly.sdk.internal.bridge.SurveyContext
import com.responsly.sdk.internal.bridge.SurveyUrl
import com.responsly.sdk.internal.config.SurveyConfig
import com.responsly.sdk.internal.net.TransportSecurity
import com.responsly.sdk.internal.state.SdkState

/** Resolves per-show overrides and builds the context passed to the survey page. */
internal object PresentationRequestFactory {
    fun build(
        survey: SurveyConfig,
        trigger: SurveyTrigger,
        options: ShowOptions?,
        environment: Environment,
        state: SdkState,
        platform: String,
        sdkVersion: String,
    ): PresentationRequest? {
        // Only https, or plain http on a local development host.
        if (!TransportSecurity.isAllowed(survey.url)) return null
        val origin = SurveyUrl.origin(survey.url) ?: return null
        // Only a locale that was set explicitly goes into the URL.
        val explicitLocale = options?.locale?.trim()?.takeIf { it.isNotEmpty() } ?: state.locale
        val style = options?.presentation ?: survey.presentation.style
        val hidden = LinkedHashMap(state.hiddenFields)
        options?.hiddenFields?.let(hidden::putAll)
        val context = SurveyContext.build(
            platform = platform,
            sdkVersion = sdkVersion,
            appId = environment.appId,
            appVersion = environment.appVersion,
            osVersion = environment.osVersion,
            locale = explicitLocale ?: environment.deviceLocale(),
            surveyId = survey.id,
            trigger = trigger,
            style = style,
            dismissible = survey.presentation.dismissible,
            anonymous = survey.anonymous,
            userId = state.userId,
            traits = LinkedHashMap(state.traits),
            hidden = hidden,
        )
        return PresentationRequest(
            token = 0L,
            surveyId = survey.id,
            url = SurveyUrl.build(survey.url, explicitLocale),
            surveyUrl = survey.url,
            origin = origin,
            contextScript = SurveyContext.script(context),
            style = style,
            size = survey.presentation.size,
            dismissible = survey.presentation.dismissible,
            userAgentSuffix = " ResponslySDK/$sdkVersion ($platform)",
        )
    }
}
