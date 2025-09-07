-- Reports on gig comments, and what the moderator did about a report.
--
-- A report names exactly one target: a post, a post comment, or (new) a gig comment. V011 wrote that
-- rule as an unnamed check, which Postgres called report_check (checked in pg_constraint); it is
-- replaced by a named one that counts the new column too.
alter table content.report
  add column gig_comment_id uuid references content.gig_comment(id) on delete cascade;

alter table content.report drop constraint report_check;
alter table content.report
  add constraint report_one_target check (num_nonnulls(post_id, comment_id, gig_comment_id) = 1);

-- One report per user and target, as for posts and post comments: a repeat changes nothing.
create unique index report_once_gig_comment on content.report (reporter_id, gig_comment_id)
  where gig_comment_id is not null;

-- What resolved the report. Null while it is OPEN; the statuses stay as they were ("resolved" means
-- not OPEN): HIDE_POST and DELETE_COMMENT leave it ACTIONED, DISMISS leaves it DISMISSED. Nothing
-- resolved a report before this migration (no code set ACTIONED or DISMISSED), so every existing row
-- is OPEN and passes the second check.
alter table content.report
  add column action text check (action in ('HIDE_POST', 'DELETE_COMMENT', 'DISMISS'));
alter table content.report
  add constraint report_action_when_resolved check ((status = 'OPEN') = (action is null));

-- The console's Resolved tab: newest decisions first.
create index report_resolved on content.report (resolved_at desc) where status <> 'OPEN';

-- Reports are looked up by target when they are resolved (every OPEN report on the target at once).
-- report_once_* lead with the reporter, so they cannot serve that; these can.
create index report_post on content.report (post_id) where post_id is not null;
create index report_comment on content.report (comment_id) where comment_id is not null;
create index report_gig_comment on content.report (gig_comment_id) where gig_comment_id is not null;
