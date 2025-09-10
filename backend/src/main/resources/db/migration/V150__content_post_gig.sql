-- Every post of a gig, whatever its status. A merge moves them all, and deleting a
-- gig checks content.post's foreign key (no cascade, V011); both scanned the whole table without this.
-- post_gig_pub (V011) covers only published posts.
create index post_gig on content.post (gig_id) where gig_id is not null;
