-- V146 — media: the duplicate guard. The app sends a hash of the file it is about to
-- upload ("md5:<hex>" from the phone, "sha256:<hex>" from the browser); POST /media/uploads looks for the
-- same owner's earlier asset with that hash and asks content whether it is in a live post.
-- The hash comes from the client: it catches accidental re-posts, it is not a security control.

alter table media.media_asset add column content_hash text;

-- Serves UploadService.create's lookup: "this owner's assets with this hash that still exist"
-- (owner_id = ? and content_hash = ? and status not in (...)). Partial, so the many assets without a
-- hash (images, older clips) and the ones that are gone cost nothing.
create index media_asset_owner_hash on media.media_asset (owner_id, content_hash)
  where content_hash is not null and status not in ('DELETED', 'FAILED', 'REJECTED');
