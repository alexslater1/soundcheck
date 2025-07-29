-- A clip's act is what places it on an artist's page, and a post's acts are its clips'
-- acts. Before this a post was tagged with its gig's whole bill, and V096 gave those posts' clips no
-- act, so a clip of one act showed on every act's page.

-- 1. A clip with no act from a gig with exactly one act: that act.
update content.post_clip c
   set artist_id = sole.artist_id
  from content.post p,
       (select gig_id, min(artist_id::text)::uuid as artist_id
          from catalog.gig_artist
         group by gig_id
        having count(*) = 1) sole
 where p.id = c.post_id
   and p.gig_id = sole.gig_id
   and c.artist_id is null;

-- 2. A gig post's tags become its clips' acts, where any clip has one. A post whose clips all lack an
--    act keeps its tags until its author picks the acts ("Which act is this?" in Edit).
delete from content.post_artist pa
 using content.post p
 where p.id = pa.post_id
   and p.gig_id is not null
   and exists (select 1 from content.post_clip c where c.post_id = p.id and c.artist_id is not null)
   and not exists (select 1 from content.post_clip c where c.post_id = p.id and c.artist_id = pa.artist_id);
insert into content.post_artist (post_id, artist_id)
select distinct c.post_id, c.artist_id
  from content.post_clip c
  join content.post p on p.id = c.post_id
 where p.gig_id is not null
   and c.artist_id is not null
on conflict do nothing;

-- An artist's clips, by the act on stage (the artist page and a gig's per-act view).
create index if not exists post_clip_artist on content.post_clip (artist_id, post_id) where artist_id is not null;
