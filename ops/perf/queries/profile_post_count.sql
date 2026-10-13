-- profile_post_count: a profile's post count, own posts plus posts the user is tagged in (PostRepository, onProfileOf count).
-- Source: the statement's text as pg_stat_statements and auto_explain record it, with the binds auto_explain logged
-- ("Query Parameters"), for one request as perf_u205 (01940483-6188-731a-a99b-54ec14467635) on medium (seed 42, loaded
-- by seed-scale at 2026-10-13 02:21Z), logged at 2026-10-13 02:32:42.886 UTC (idle) by the perf API built at 8db165b.
-- The ids exist only in that load: after another seed-scale, recapture them. The request viewed perf_u1's profile.
-- check: select exists (select 1 from identity.app_user where id = '01929bbc-5ad7-72f6-8f5f-97f2b66634e2') or exists (select 1 from content.post where id = '01929bbc-5ad7-72f6-8f5f-97f2b66634e2')
prepare q as
select count(*) from "content"."post" where ("content"."post"."status" = $1 and ("content"."post"."author_id" in (cast($2 as uuid)) or "content"."post"."id" in (select "content"."post_person"."post_id" from "content"."post_person" where ("content"."post_person"."user_id" in (cast($3 as uuid)) and "content"."post_person"."status" = $4))));
explain (analyze, buffers, format text) execute q('PUBLISHED', '01929bbc-5ad7-72f6-8f5f-97f2b66634e2', '01929bbc-5ad7-72f6-8f5f-97f2b66634e2', 'ON_PROFILE');
