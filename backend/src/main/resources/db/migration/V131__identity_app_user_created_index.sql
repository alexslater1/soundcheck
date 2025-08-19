-- The console's user list walks live accounts newest first, with a (created_at, id) keyset cursor.
-- Without this index every page sorts the whole table.
create index app_user_created on identity.app_user (created_at desc, id desc) where deleted_at is null;
