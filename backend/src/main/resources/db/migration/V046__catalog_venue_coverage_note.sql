-- Venues whose own listings Soundcheck does not read (a ticketing widget,
-- a list copied from elsewhere, no readable page) carry a note the app shows wherever the venue appears,
-- so a thin list there reads as "incomplete", not "nothing on". Set from the venue registry by the
-- ingestion module through catalog.CatalogIngest.applyCoverageNotes; null for every other venue.
alter table catalog.venue add column coverage_note text;
