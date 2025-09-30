-- V155 — catalog: the Edit history tab lists every gig's edits, newest first.
--
-- gig_edit_created serves the keyset page (created_at, id); gig_edit_undoes finds the undo of each edit
-- on a page, which may sit on another page. Partial: most edits undo nothing.

create index gig_edit_created on catalog.gig_edit (created_at, id);
create index gig_edit_undoes on catalog.gig_edit (undoes_edit_id) where undoes_edit_id is not null;
