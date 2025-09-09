package com.responsly.sdk

/**
 * Options for [Responsly.showSurvey].
 *
 * @property force skip targeting checks (audience, schedule, frequency) and the [DisplayGuard].
 * @property hiddenFields hidden-field values for this survey only; merged over [Responsly.setHiddenFields].
 * @property locale language tag for this survey only, e.g. `pl` or `pt-BR`.
 * @property presentation overrides the presentation style configured for the survey.
 */
public class ShowOptions @JvmOverloads constructor(
    public val force: Boolean = false,
    hiddenFields: Map<String, String> = emptyMap(),
    public val locale: String? = null,
    public val presentation: PresentationStyle? = null,
) {
    public val hiddenFields: Map<String, String> = LinkedHashMap(hiddenFields)

    override fun toString(): String =
        "ShowOptions(force=$force, hiddenFields=$hiddenFields, locale=$locale, presentation=$presentation)"
}

/** How the survey container is laid out. [value] is the protocol string. */
public enum class PresentationStyle(public val value: String) {
    BOTTOM_SHEET("bottom_sheet"),
    DIALOG("dialog"),
    FULLSCREEN("fullscreen");

    public companion object {
        /** Returns the style for a protocol string, or `null` when it is unknown. */
        @JvmStatic
        public fun fromValue(value: String): PresentationStyle? = values().firstOrNull { it.value == value }
    }
}
