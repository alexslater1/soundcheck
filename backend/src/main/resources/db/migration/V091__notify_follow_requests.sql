-- V091 — notify: follow requests in Activity. FOLLOW_REQUEST goes to the private
-- profile's owner (with Accept and Decline); FOLLOW_ACCEPTED goes to the person whose request was accepted.
alter table notify.notification drop constraint notification_type_check;
alter table notify.notification add constraint notification_type_check
  check (type in ('NEW_FOLLOWER','POST_LIKED','POST_COMMENTED','POST_READY','POST_FAILED','NEW_GIGS',
                  'FOLLOW_REQUEST','FOLLOW_ACCEPTED'));

-- One entry per pending request: the outbox may deliver the event twice. The entry is removed when the
-- request is declined or cancelled, and becomes NEW_FOLLOWER when it is accepted.
create unique index notification_follow_request on notify.notification (user_id, actor_id)
  where type = 'FOLLOW_REQUEST';
-- Collapsed like NEW_FOLLOWER: one unread acceptance per (user, owner).
create unique index notification_unread_follow_accepted on notify.notification (user_id, actor_id)
  where type = 'FOLLOW_ACCEPTED' and read_at is null;
