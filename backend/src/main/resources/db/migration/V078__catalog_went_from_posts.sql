-- Posts made before the rule count as they would have from now on. For every live post
-- (PUBLISHED, or HIDDEN by moderation: it still exists) with a gig whose day has come on the UK
-- calendar, its author is marked WENT with source POST, unless already WENT (their own mark stays as
-- it is); an INTERESTED or GOING mark becomes WENT. A post of a gig still to come (posting one was
-- possible until this change) marks nothing.
insert into catalog.gig_attendance (user_id, gig_id, status, source, created_at, updated_at)
select distinct p.author_id, p.gig_id, 'WENT', 'POST', now(), now()
  from content.post p
  join catalog.gig g on g.id = p.gig_id
 where p.status in ('PUBLISHED', 'HIDDEN')
   and (g.starts_at at time zone 'Europe/London')::date <= (now() at time zone 'Europe/London')::date
on conflict (user_id, gig_id) do update
   set status = 'WENT', source = 'POST', updated_at = excluded.updated_at
 where catalog.gig_attendance.status <> 'WENT';
