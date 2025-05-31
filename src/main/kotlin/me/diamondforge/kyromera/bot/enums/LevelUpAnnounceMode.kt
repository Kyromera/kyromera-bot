package me.diamondforge.kyromera.bot.enums

/**
 * Represents the different modes for announcing level-up messages.
 *
 * This enum corresponds to the `levelup_announce_mode` column in the database,
 * which determines how and where level-up messages are announced.
 */
enum class LevelUpAnnounceMode(val value: String) {
    /**
     * Level-up messages are disabled and will not be sent.
     */
    DISABLED("disabled"),

    /**
     * Level-up messages are sent to the channel where the user was last active.
     */
    CURRENT("current"),

    /**
     * Level-up messages are sent as a direct message to the user.
     */
    DM("dm"),

    /**
     * Level-up messages are sent to a custom channel configured for the guild.
     */
    CUSTOM("custom");

    companion object {
        /**
         * Converts a string value to the corresponding enum value.
         *
         * @param value The string value to convert
         * @return The corresponding enum value, or CURRENT if the value is not recognized
         */
        fun fromString(value: String): LevelUpAnnounceMode {
            return values().find { it.value == value.lowercase() } ?: CURRENT
        }
    }

    /**
     * Checks if level-up announcements are enabled.
     *
     * @return True if announcements are enabled (not DISABLED), false otherwise
     */
    fun isEnabled(): Boolean {
        return this != DISABLED
    }

    /**
     * Checks if level-up announcements should be sent to the current channel.
     *
     * @return True if announcements should be sent to the current channel, false otherwise
     */
    fun isCurrentChannel(): Boolean {
        return this == CURRENT
    }

    /**
     * Checks if level-up announcements should be sent as direct messages.
     *
     * @return True if announcements should be sent as direct messages, false otherwise
     */
    fun isDM(): Boolean {
        return this == DM
    }

    /**
     * Checks if level-up announcements should be sent to a custom channel.
     *
     * @return True if announcements should be sent to a custom channel, false otherwise
     */
    fun isCustomChannel(): Boolean {
        return this == CUSTOM
    }
}