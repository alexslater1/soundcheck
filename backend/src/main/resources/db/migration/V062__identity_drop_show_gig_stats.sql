-- The contract step after V060 (expand) and the code that stopped reading it: the
-- profile-level switch replaces the per-stats opt-in, so its column goes. On a rolling deploy this
-- would ship one release after V060; nothing is deployed yet, so both arrive together.
alter table identity.app_user drop column show_gig_stats;
