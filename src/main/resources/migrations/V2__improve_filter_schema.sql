-- Rename tables to be more generic (filter instead of whitelist)
ALTER TABLE leveling_whitelisted_channels RENAME TO leveling_filtered_channels;
ALTER TABLE leveling_whitelisted_roles RENAME TO leveling_filtered_roles;

-- Add a new column to leveling_settings for filter_mode (enum type)
ALTER TABLE leveling_settings 
    ADD COLUMN filter_mode VARCHAR(16) NOT NULL DEFAULT 'blacklist' 
    CHECK (filter_mode IN ('blacklist', 'whitelist'));

-- Set the initial filter_mode based on the existing whitelist_mode
UPDATE leveling_settings 
SET filter_mode = CASE 
    WHEN whitelist_mode = TRUE THEN 'whitelist' 
    ELSE 'blacklist' 
END;

-- We'll keep the whitelist_mode column for backward compatibility
-- but it will be deprecated in favor of filter_mode