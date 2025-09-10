-- A merge is logged in the surviving gig's edit history like a fan's edit (V104):
-- field MERGE, old_value the merged gig's id, new_value the survivor's, user_id the operator.
alter table catalog.gig_edit drop constraint gig_edit_field_check;
alter table catalog.gig_edit add constraint gig_edit_field_check
  check (field in ('ACT_ADDED', 'STARTS_AT', 'DATE', 'VENUE', 'HEADLINER', 'TITLE', 'MERGE'));
