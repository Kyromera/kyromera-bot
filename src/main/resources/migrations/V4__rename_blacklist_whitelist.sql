-- Rename blacklist and whitelist to denylist and allowlist in filter_mode column
ALTER TABLE leveling_settings
    ALTER COLUMN filter_mode SET DEFAULT 'denylist';

-- Update the check constraint for filter_mode
ALTER TABLE leveling_settings
    DROP CONSTRAINT IF EXISTS leveling_settings_filter_mode_check;

-- Update existing values
UPDATE leveling_settings
SET filter_mode = CASE
    WHEN filter_mode = 'whitelist' THEN 'allowlist'
    WHEN filter_mode = 'blacklist' THEN 'denylist'
    ELSE filter_mode
END;

-- Add the new constraint after updating values
ALTER TABLE leveling_settings
    ADD CONSTRAINT leveling_settings_filter_mode_check 
    CHECK (filter_mode IN ('denylist', 'allowlist'));
