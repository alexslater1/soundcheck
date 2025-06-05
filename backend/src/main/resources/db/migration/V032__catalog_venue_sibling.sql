-- V032 — catalog: venues that are the same building.
-- Ticketmaster lists the Dome in Tufnell Park as "The Dome", "Downstairs at The Dome" and "The Dome London";
-- the Dome's own website lists all of them on one page. The rows stay separate venues (a room is worth
-- knowing), but when the resolver looks for "the same show on the same night" it looks at the venue and its
-- siblings. Pairs are stored both ways (a→b and b→a) so the lookup is one indexed read.
-- Siblings come from the venue registry (venues/london.json, otherTicketmasterVenueIds), never from guessing.

create table catalog.venue_sibling (
  venue_id    uuid not null references catalog.venue (id) on delete cascade,
  sibling_id  uuid not null references catalog.venue (id) on delete cascade,
  primary key (venue_id, sibling_id),             -- "siblings of venue X": a prefix read of this index
  check (venue_id <> sibling_id)
);
