-- Who made a config ACTIVE (the console's activate and "Re-activate previous"), next to when.
-- Null for the seeded config and for any activated before this column existed.
alter table feed.ranking_config add column activated_by uuid;
