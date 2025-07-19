-- A POST_TAGGED notification tells someone they were tagged in a post (actor: its author).
-- The list keeps every type before it, including V091's follow requests.
alter table notify.notification drop constraint notification_type_check;
alter table notify.notification add constraint notification_type_check
  check (type in ('NEW_FOLLOWER','POST_LIKED','POST_COMMENTED','POST_READY','POST_FAILED','NEW_GIGS',
                  'FOLLOW_REQUEST','FOLLOW_ACCEPTED','POST_TAGGED'));
