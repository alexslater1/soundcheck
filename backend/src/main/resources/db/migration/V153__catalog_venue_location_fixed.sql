-- An operator placed the venue on the map by hand. It then leaves the console's list of suspect
-- locations (fan-added venues, venues at a city's centre), and nothing overwrites it: completeVenue only
-- fills a missing location.
alter table catalog.venue add column location_fixed_at timestamptz;
alter table catalog.venue add column location_fixed_by uuid references identity.app_user(id) on delete set null;
