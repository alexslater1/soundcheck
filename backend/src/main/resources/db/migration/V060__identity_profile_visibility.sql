-- One profile-level privacy switch instead of per-item settings.
-- PUBLIC: posts, gig stats and gig history are visible to every signed-in user (blocks still apply).
-- PRIVATE: they are visible only to the people this user follows (no follow requests), and the user's
-- posts stay out of everyone else's feeds. Every existing user starts PUBLIC: nobody has chosen a private
-- profile yet, and the switch replaces the stats opt-in of V040, whose column V062 drops.
alter table identity.app_user
    add column profile_visibility text not null default 'PUBLIC'
        constraint app_user_profile_visibility check (profile_visibility in ('PUBLIC', 'PRIVATE'));
