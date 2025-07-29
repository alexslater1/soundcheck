-- Went is allowed only from a gig's UK day on. Before the rule the API took Went on any
-- gig, so a fan may hold a Went on a gig still to come; the gig page no longer offers Went before the
-- day, so that mark could not be changed with one tap. It becomes Going, the nearest thing the fan can
-- have meant, and theirs (source USER). Cancelled gigs are left alone: no list counts or offers them.
-- Expected index: gig_attendance_gig (gig_id, status) through the join; at this size a scan is fine.
update catalog.gig_attendance a
   set status = 'GOING', source = 'USER', updated_at = now()
  from catalog.gig g
 where g.id = a.gig_id
   and a.status = 'WENT'
   and g.status <> 'CANCELLED'
   and (g.starts_at at time zone 'Europe/London')::date > (now() at time zone 'Europe/London')::date;
