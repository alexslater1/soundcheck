-- The new-gig alert pass (social.internal.GigAlertService).
-- When alerts started: gigs created before then never alert, so switching alerts on does not announce
-- the whole catalog. Each pass looks at gigs created in the last two days (and not before this), so a
-- gig whose transaction committed late is still seen by the next pass.
create table social.gig_alert_state (
  id          smallint primary key default 1 check (id = 1),
  started_at  timestamptz not null
);
-- Which gigs each user has been alerted about: a gig never alerts the same person twice, however many
-- passes see it. Rows for gigs that have started are deleted by the pass.
create table social.gig_alert_sent (
  user_id    uuid not null references identity.app_user(id) on delete cascade,
  gig_id     uuid not null references catalog.gig(id) on delete cascade,
  starts_at  timestamptz not null,
  sent_at    timestamptz not null default now(),
  primary key (user_id, gig_id)
);
create index gig_alert_sent_starts on social.gig_alert_sent (starts_at);
