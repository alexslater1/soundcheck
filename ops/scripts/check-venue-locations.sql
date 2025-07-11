-- Which venues still have no coordinates (so their gigs show no distance), and why. Read-only.
-- Run from the repository root with the stack up:
--   docker compose exec -T db psql -U soundcheck -d soundcheck < ops/scripts/check-venue-locations.sql
\echo 'Venues with upcoming gigs but no coordinates, and those gigs:'
select count(distinct v.id) as venues, count(*) as upcoming_gigs
from catalog.venue v
join catalog.gig g on g.venue_id = v.id
where v.location is null and g.starts_at > now();

\echo 'The first 20 of them (postcode: what the geocoder can use; sources: who listed the gigs):'
select v.name, v.city, v.postcode,
       to_char(v.created_at, 'YYYY-MM-DD HH24:MI') as created,
       (select string_agg(distinct gs.source, ',')
          from catalog.gig g2 join catalog.gig_source gs on gs.gig_id = g2.id
          where g2.venue_id = v.id) as sources,
       count(*) as upcoming_gigs
from catalog.venue v
join catalog.gig g on g.venue_id = v.id
where v.location is null and g.starts_at > now()
group by v.id
order by count(*) desc, v.name
limit 20;

\echo 'Tramshed:'
select v.name, v.city, v.postcode, v.location is not null as located,
       (select count(*) from catalog.gig g where g.venue_id = v.id and g.location is null) as gigs_without_location
from catalog.venue v
where v.name ilike '%tramshed%';

\echo 'Gigs without a location at a venue that has one (should be 0):'
select count(*) as gigs
from catalog.gig g
join catalog.venue v on v.id = g.venue_id
where g.location is null and v.location is not null;

\echo 'The last runs and what their geocoding pass did (empty before the geocoding pass existed):'
select source, trigger, status, to_char(started_at, 'YYYY-MM-DD HH24:MI') as started,
       stats -> 'geocoding' as geocoding
from ingestion.ingestion_run
order by started_at desc
limit 5;
