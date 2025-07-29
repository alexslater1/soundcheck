-- A comment thread under every gig ("Who's going?" before it, "How was it?" after).
-- A table of its own rather than a nullable content.comment.post_id, which would weaken every query
-- that reads a post's comments. Same limits as a post comment (V011): 1-500 characters, soft delete.
create table content.gig_comment (
  id         uuid primary key,
  gig_id     uuid not null references catalog.gig(id) on delete cascade,
  author_id  uuid not null references identity.app_user(id) on delete cascade,   -- account purge removes them
  body       text not null check (char_length(body) between 1 and 500),
  created_at timestamptz not null default now(),
  deleted_at timestamptz
);
create index gig_comment_gig on content.gig_comment (gig_id, created_at, id);   -- a gig's thread, oldest first (keyset)
create index gig_comment_author on content.gig_comment (author_id);            -- the purge's cascade
