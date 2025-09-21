-- The operator undoes a fan's edit of a gig (catalog.gig_edit, V104). The undo is itself an edit
-- of the same field, pointing at the edit it undoes, so "a later edit touched this field" (which refuses
-- an undo) needs no special case, and undoing an undo puts the edit back.
alter table catalog.gig_edit add column undoes_edit_id uuid references catalog.gig_edit(id) on delete set null;

-- An undo of ACT_ADDED takes the act off the bill: old_value is the act, and there is no new value.
-- Every other edit still has one (new_value was not null since V104).
alter table catalog.gig_edit alter column new_value drop not null;
