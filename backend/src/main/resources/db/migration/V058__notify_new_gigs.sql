-- A NEW_GIGS notification lists the new gigs that match what the user follows or searched for.
-- The list is a snapshot (title, date, venue, why) taken when the alert was made: notifications cannot
-- read the catalog, and the gig page shows the live details.
alter table notify.notification drop constraint notification_type_check;
alter table notify.notification add constraint notification_type_check
  check (type in ('NEW_FOLLOWER','POST_LIKED','POST_COMMENTED','POST_READY','POST_FAILED','NEW_GIGS'));
alter table notify.notification add column gigs jsonb;
alter table notify.notification add check ((type = 'NEW_GIGS') = (gigs is not null));
