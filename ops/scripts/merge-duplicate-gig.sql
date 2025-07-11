-- H3: merge a duplicate gig into the one to keep, e.g. a second Liam Gallagher at O2 Academy2 Islington
-- into the one that was moved to The O2. Takes the two ids from ops/scripts/check-moved-gigs.sql:
--   docker compose exec -T db psql -U soundcheck -d soundcheck -v ON_ERROR_STOP=1 \
--     -v keep=<gig_id to keep> -v dup=<gig_id to remove> < ops/scripts/merge-duplicate-gig.sql
-- Refuses unless both are the same night with the same headliner. Marks (Went, Going…) move to the
-- kept gig unless the person already has one there; posts move; then the copy is deleted with its
-- line-up, listings and alert rows (on delete cascade). One transaction. `make backup` first if unsure.
select count(*) = 2
       and count(distinct (g.starts_at at time zone 'Europe/London')::date) = 1
       and count(distinct gar.artist_id) = 1 as same_gig
from catalog.gig g
join catalog.gig_artist gar on gar.gig_id = g.id and gar.billing_order = 0
where g.id in (:'keep', :'dup') \gset
\if :same_gig
begin;
insert into catalog.gig_attendance (user_id, gig_id, status, created_at, updated_at)
  select user_id, :'keep', status, created_at, now() from catalog.gig_attendance where gig_id = :'dup'
  on conflict (user_id, gig_id) do nothing;
update content.post set gig_id = :'keep', updated_at = now() where gig_id = :'dup';
delete from catalog.gig where id = :'dup';
commit;
\echo 'merged'
\else
\echo 'refused: the two ids are not one night with one headliner (or one of them does not exist)'
\endif
