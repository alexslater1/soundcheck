-- profile_post_count: a profile's post count, own posts plus posts the user is tagged in (PostRepository, onProfileOf count).
-- Source: the statement's text as pg_stat_statements and auto_explain record it, with the binds auto_explain logged
-- ("Query Parameters") for the median-duration call of its most common text (25 of 25 logged calls over 50 ms)
-- during the mixed baseline on medium (seed 42), logged at 2025-10-07 14:39:38.571 UTC (under load) by the perf API built
-- at 22f80db. The ids exist only in that load: after another seed-scale, recapture them.
-- check: select exists (select 1 from identity.app_user where id = '01901b4e-9262-725f-9b4f-85c23a819ba9') or exists (select 1 from content.post where id = '01901b4e-9262-725f-9b4f-85c23a819ba9')
prepare q as
select count(*) from "content"."post" where ("content"."post"."status" = $1 and ("content"."post"."author_id" in (cast($2 as uuid)) or "content"."post"."id" in (select "content"."post_person"."post_id" from "content"."post_person" where ("content"."post_person"."user_id" in (cast($3 as uuid)) and "content"."post_person"."status" = $4))));
explain (analyze, buffers, format text) execute q('PUBLISHED', '01901b4e-9262-725f-9b4f-85c23a819ba9', '01901b4e-9262-725f-9b4f-85c23a819ba9', 'ON_PROFILE');
