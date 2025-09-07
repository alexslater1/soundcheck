-- The console's analytics count posts by the time they were published and uploads by
-- the time their upload completed, over a period of up to 90 days (and the same length before it). The
-- existing indexes don't serve those reads: post_pub (V011) holds only posts that are PUBLISHED now, and
-- media_asset has none on uploaded_at. Like V131 (app_user_created, for the console's user list), these
-- live on the owning modules' tables; only the console's range reads use them.

-- analytics.post_fact filtered on published_at (posts per day, top artists and gigs).
create index post_published_at on content.post (published_at) where published_at is not null;

-- analytics.media_fact filtered on uploaded_at (uploads per day, success rate, time to ready, by hour).
create index media_asset_uploaded_at on media.media_asset (uploaded_at) where uploaded_at is not null;
