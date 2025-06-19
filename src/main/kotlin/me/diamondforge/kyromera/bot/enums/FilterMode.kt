package me.diamondforge.kyromera.bot.enums

/**
 * Represents the different modes for channel and role filtering in the leveling system.
 *
 * This enum corresponds to the `filter_mode` column in the database,
 * which determines how channels and roles are filtered for XP gain.
 */
enum class FilterMode(
    val value: String,
) {
    /**
     * Denylist mode - XP is awarded in all channels/roles EXCEPT those in the filter list.
     * This is the default mode.
     */
    DENYLIST("denylist"),

    /**
     * Allowlist mode - XP is ONLY awarded in channels/roles that are in the filter list.
     */
    ALLOWLIST("allowlist"),
    ;

    companion object {
        /**
         * Converts a string value to the corresponding enum value.
         *
         * @param value The string value to convert
         * @return The corresponding enum value, or DENYLIST if the value is not recognized
         */
        fun fromString(value: String): FilterMode = values().find { it.value == value.lowercase() } ?: DENYLIST
    }

    /**
     * Checks if the mode is denylist.
     *
     * @return True if the mode is denylist, false otherwise
     */
    fun isDenylist(): Boolean = this == DENYLIST

    /**
     * Checks if the mode is allowlist.
     *
     * @return True if the mode is allowlist, false otherwise
     */
    fun isAllowlist(): Boolean = this == ALLOWLIST
}
