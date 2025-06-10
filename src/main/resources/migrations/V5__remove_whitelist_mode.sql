-- Remove the deprecated whitelist_mode column from leveling_settings table
ALTER TABLE leveling_settings
    DROP COLUMN IF EXISTS whitelist_mode;