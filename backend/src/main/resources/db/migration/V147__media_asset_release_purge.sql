-- V147 — media: the end of a clip's life. Deleting a post releases its media
-- (status DELETED, deleted_at set, in the post delete's transaction); MediaObjectPurge removes the
-- objects from storage 7 days later and records when in objects_purged_at. The rows stay, because
-- content.post_clip still references them.

alter table media.media_asset
  add column deleted_at timestamptz,
  add column objects_purged_at timestamptz;

-- Uploads aborted before V147 were already DELETED: date them by their last change so the purge
-- takes them too.
update media.media_asset set deleted_at = updated_at where status = 'DELETED' and deleted_at is null;

-- Serves MediaObjectPurge: "DELETED, not yet purged, deleted before the cutoff, oldest first"
-- (status = 'DELETED' and objects_purged_at is null and deleted_at < ? order by deleted_at limit 100).
-- Purged rows leave the index, so it stays the size of one week's deletions.
create index media_asset_deleted on media.media_asset (deleted_at)
  where status = 'DELETED' and objects_purged_at is null;
