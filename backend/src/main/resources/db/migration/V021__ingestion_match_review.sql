-- V021 — ingestion: listings that almost matched an existing gig.
-- The resolver never merges on weak evidence: a pair that is close but not convincing (same venue and night,
-- similar names, no shared act) becomes two gigs and one row here, for a person to decide in the console.
-- The decision is kept so the same pair is never asked about twice.

create table ingestion.match_review (
  id                 uuid primary key,
  created_at         timestamptz not null default now(),
  source             text not null,                 -- the incoming listing
  external_id        text not null,
  gig_id             uuid not null,                 -- the gig the listing became
  candidate_gig_id   uuid not null,                 -- the existing gig it almost matched
  title              text not null,
  candidate_title    text not null,
  title_similarity   real not null,
  act_similarity     real not null,
  reason             text not null,
  status             text not null default 'OPEN' check (status in ('OPEN','MERGED','KEPT_APART')),
  decided_at         timestamptz,
  unique (source, external_id, candidate_gig_id)    -- one question per pair, however many runs see it
);
create index match_review_open on ingestion.match_review (created_at) where status = 'OPEN';  -- the review queue
