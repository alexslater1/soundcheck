-- Where are the six seeded gigs that were moved to their correct venues, and is there more than one
-- copy of any of them? Read-only. Run from the repository root with the stack up:
--   docker compose exec -T db psql -U soundcheck -d soundcheck < ops/scripts/check-moved-gigs.sql
-- Expected after the move: one row per night; Liam Gallagher, Pulp and Wolf Alice at "The O2" (London),
-- Fontaines D.C. and Kasabian at "Finsbury Park", Victorious Festival 2025 at "Southsea Common".
-- Two rows for one night: a seed run after the move added the gig again at the old venue (fixed by
-- 42fd928); merge the copy in the console (/console/catalog, Merge tab).
-- One row, still at an O2 Academy: the move itself is gone (a restore from an older backup?).
select a.name as act,
       g.starts_at::date as night,
       v.name as venue,
       v.city,
       g.id as gig_id,
       to_char(g.created_at, 'YYYY-MM-DD HH24:MI') as created,
       to_char(g.updated_at, 'YYYY-MM-DD HH24:MI') as updated,
       (select string_agg(gs.source, ',' order by gs.source)
          from catalog.gig_source gs where gs.gig_id = g.id) as listings,
       (select count(*) from catalog.gig_attendance ga where ga.gig_id = g.id) as marked,
       (select count(*) from content.post p where p.gig_id = g.id and p.status = 'PUBLISHED') as posts
from catalog.gig g
join catalog.venue v on v.id = g.venue_id
join catalog.gig_artist gar on gar.gig_id = g.id and gar.billing_order = 0
join catalog.artist a on a.id = gar.artist_id
where g.starts_at < now()
  and (a.name ilike any (array['Liam Gallagher', 'Pulp', 'Wolf Alice', 'Fontaines D.C%', 'Kasabian'])
       or g.title ilike 'Victorious Festival%')
order by a.name, g.starts_at, g.created_at;
