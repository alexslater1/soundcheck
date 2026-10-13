-- post_hydrate: a For You page's 10 posts by id (PostRepository.findAll).
-- Source: the statement's text as pg_stat_statements and auto_explain record it, with the binds auto_explain logged
-- ("Query Parameters"), for one request as perf_u205 (01940483-6188-731a-a99b-54ec14467635) on medium (seed 42, loaded
-- by seed-scale at 2026-10-13 02:21Z), logged at 2026-10-13 02:32:34.075 UTC (idle) by the perf API built at 8db165b.
-- The ids exist only in that load: after another seed-scale, recapture them.
-- check: select exists (select 1 from content.post where id = '01a132f7-4038-70c9-a18b-810ed8bc75dc')
prepare q as
select "content"."post"."id", "content"."post"."author_id", "content"."post"."media_id", "content"."post"."gig_id", "content"."post"."caption", "content"."post"."status", "content"."post"."like_count", "content"."post"."comment_count", "content"."post"."search_tsv", "content"."post"."created_at", "content"."post"."updated_at", "content"."post"."published_at", "content"."post"."day" from "content"."post" where "content"."post"."id" in (cast($1 as uuid), cast($2 as uuid), cast($3 as uuid), cast($4 as uuid), cast($5 as uuid), cast($6 as uuid), cast($7 as uuid), cast($8 as uuid), cast($9 as uuid), cast($10 as uuid));
explain (analyze, buffers, format text) execute q('01a132f7-4038-70c9-a18b-810ed8bc75dc', '01a0fe22-f096-7e80-9c27-607b76ef99c9', '01a12d56-c1fe-7c64-a81b-80d54bdd4630', '01a08f72-22f2-77ca-91fc-1bf389813794', '01a132f6-2a9d-7fe7-bcbd-a8b9f9f914ab', '01a132f3-d9ea-7e91-b262-8c36940af764', '01a12544-e210-7b0a-9399-54fbaedccd52', '01a100f6-eae6-7233-af11-e6cf8217c078', '01a132f3-fa72-7a4c-b64e-1d02c2849491', '01a0905b-094f-7840-8777-c5bc40356dca');
