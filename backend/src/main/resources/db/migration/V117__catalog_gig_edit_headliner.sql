-- A fan who went (or is going) says which act headlined. The change, and the title
-- that follows it, are logged like the other fan edits of a gig, so a wrong one can be found and undone.
alter table catalog.gig_edit drop constraint gig_edit_field_check;
alter table catalog.gig_edit add constraint gig_edit_field_check
  check (field in ('ACT_ADDED', 'STARTS_AT', 'DATE', 'VENUE', 'HEADLINER', 'TITLE'));
