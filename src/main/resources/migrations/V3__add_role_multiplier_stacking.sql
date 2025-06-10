-- Add a new column to leveling_settings for role multiplier stacking preference
ALTER TABLE leveling_settings 
    ADD COLUMN stack_role_multipliers BOOLEAN NOT NULL DEFAULT FALSE;

-- Set the initial value to FALSE (use highest multiplier) for backward compatibility
UPDATE leveling_settings 
SET stack_role_multipliers = FALSE;