-- Removes the synthetic fixture data from the catalog: every gig that has a synthetic listing, then
-- the synthetic venues and artists that no remaining gig uses. Real data is not touched. Run with `make purge-fixtures`.
begin;
delete from catalog.gig g
 where exists (select 1 from catalog.gig_source s where s.gig_id = g.id and s.external_id like 'G5vsyn%');
delete from catalog.venue v
 where exists (select 1 from catalog.venue_external_id e where e.venue_id = v.id and e.external_id like 'KovZsyn%')
   and not exists (select 1 from catalog.gig g where g.venue_id = v.id);
delete from catalog.artist a
 where exists (select 1 from catalog.artist_external_id e where e.artist_id = a.id and e.external_id like 'K8vZsyn%')
   and not exists (select 1 from catalog.gig_artist ga where ga.artist_id = a.id);
select (select count(*) from catalog.gig) as gigs_left,
       (select count(*) from catalog.gig_source where external_id like 'G5vsyn%') as synthetic_listings_left;
commit;
