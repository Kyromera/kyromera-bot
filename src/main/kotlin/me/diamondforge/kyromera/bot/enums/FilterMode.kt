package me.diamondforge.kyromera.bot.enums

/**
 * Represents the different modes for channel and role filtering in the leveling system.
 *
 * This enum corresponds to the `filter_mode` column in the database,
 * which determines how channels and roles are filtered for XP gain.
 */
enum class FilterMode(val value: String) {
    /**
     * Blacklist mode - XP is awarded in all channels/roles EXCEPT those in the filter list.
     * This is the default mode.
     */
    BLACKLIST("blacklist"),

    /**
     * Whitelist mode - XP is ONLY awarded in channels/roles that are in the filter list.
     */
    WHITELIST("whitelist");

    companion object {
        /**
         * Converts a string value to the corresponding enum value.
         *
         * @param value The string value to convert
         * @return The corresponding enum value, or BLACKLIST if the value is not recognized
         */
        fun fromString(value: String): FilterMode {
            return values().find { it.value == value.lowercase() } ?: BLACKLIST
        }
    }

    /**
     * Checks if the mode is blacklist.
     *
     * @return True if the mode is blacklist, false otherwise
     */
    fun isBlacklist(): Boolean {
        return this == BLACKLIST
    }

    /**
     * Checks if the mode is whitelist.
     *
     * @return True if the mode is whitelist, false otherwise
     */
    fun isWhitelist(): Boolean {
        return this == WHITELIST
    }
}