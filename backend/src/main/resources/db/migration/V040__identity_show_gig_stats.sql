-- Whether other signed-in users may see this user's gig stats on their profile (counted from
-- the gigs they marked WENT: where they were, on which nights). Off until the user turns it on; the owner
-- always sees their own. (V040: another branch has V031-V039; V032 is its catalog.venue_sibling.)
alter table identity.app_user add column show_gig_stats boolean not null default false;
