-- V007 — catalog: each gig has exactly one *primary* listing, the only one allowed to rewrite its fields.
-- (Without it, "who owns this gig" had to be inferred from
-- ids, could not prefer a plain listing over a "VIP Package" one, and could not move when a listing vanished.)

alter table catalog.gig_source add column primary_listing boolean not null default false;

-- Existing rows: the listing the previous rule treated as the owner (most trusted source, then smallest id).
update catalog.gig_source s
   set primary_listing = true
 where (s.source, s.external_id) = (
         select o.source, o.external_id
           from catalog.gig_source o
          where o.gig_id = s.gig_id
          order by case o.source when 'TICKETMASTER' then 0 when 'SKIDDLE' then 1 else 2 end, o.external_id
          limit 1);

create unique index gig_source_one_primary on catalog.gig_source (gig_id) where primary_listing;
