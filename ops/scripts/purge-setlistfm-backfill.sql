-- Removes what the setlist.fm backfill stored, for when setlist.fm's terms must be met in full
-- (before any public or commercial use). Run with `make purge-past-gigs`.
--
-- Removed: gigs that only setlist.fm lists and that no one has touched (no post, no Went/Going mark), then
-- the venues only setlist.fm knows that no gig uses and no one follows, the backfill's bookkeeping and its stored pages.
-- Kept: gigs with a post or a mark (a fan's own gig), and setlist.fm links on gigs other
-- sources list. Acts a setlist added to another source's gig cannot be told apart from other fills and stay.
begin;
delete from catalog.gig g
 where not exists (select 1 from catalog.gig_source s where s.gig_id = g.id and s.source <> 'SETLISTFM')
   and exists (select 1 from catalog.gig_source s where s.gig_id = g.id and s.source = 'SETLISTFM')
   and not exists (select 1 from content.post p where p.gig_id = g.id)
   and not exists (select 1 from catalog.gig_attendance a where a.gig_id = g.id);
delete from catalog.venue v
 where exists (select 1 from catalog.venue_external_id e where e.venue_id = v.id and e.source = 'SETLISTFM')
   and not exists (select 1 from catalog.venue_external_id e where e.venue_id = v.id and e.source <> 'SETLISTFM')
   and not exists (select 1 from catalog.gig g where g.venue_id = v.id)
   and not exists (select 1 from social.venue_follow f where f.venue_id = v.id);
delete from ingestion.setlistfm_artist_sync;
delete from ingestion.raw_payload where source = 'SETLISTFM';
select (select count(*) from catalog.gig g
          where not exists (select 1 from catalog.gig_source s where s.gig_id = g.id and s.source <> 'SETLISTFM')) as setlistfm_only_gigs_kept,
       (select count(*) from catalog.gig_source where source = 'SETLISTFM') as setlistfm_links_left;
commit;
