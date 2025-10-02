-- post_hydrate: a For You page's 10 posts by id (PostRepository.findAll).
-- Source: the statement's text as pg_stat_statements and auto_explain record it, with the binds auto_explain logged
-- ("Query Parameters") for one request as perf_u6 (01904b8f-23fc-7e81-99a6-eb4644d73a85) on the small profile (seed 42, loaded
-- by seed-scale at 2025-10-02 18:55Z), captured 2025-10-02T19:16Z from the perf API built at 0acebf9. The ids exist only in
-- that load: after another seed-scale, recapture them.
-- check: select exists (select 1 from content.post where id = '01a0d97b-cb05-70b4-878c-7f642b322f2f')
prepare q as
select "content"."post"."id", "content"."post"."author_id", "content"."post"."media_id", "content"."post"."gig_id", "content"."post"."caption", "content"."post"."status", "content"."post"."like_count", "content"."post"."comment_count", "content"."post"."search_tsv", "content"."post"."created_at", "content"."post"."updated_at", "content"."post"."published_at", "content"."post"."day" from "content"."post" where "content"."post"."id" in (cast($1 as uuid), cast($2 as uuid), cast($3 as uuid), cast($4 as uuid), cast($5 as uuid), cast($6 as uuid), cast($7 as uuid), cast($8 as uuid), cast($9 as uuid), cast($10 as uuid));
explain (analyze, buffers, format text) execute q('01a0d97b-cb05-70b4-878c-7f642b322f2f', '0199692b-4621-73b6-ad48-7645c1790f33', '01996ba7-f7ed-7aca-a9a4-dde4c783ee9d', '01987dfd-c99a-768f-9358-57a5f091acbb', '019fc051-2cf0-7526-b97b-9a8833ee6311', '01a10cd5-9c8c-7016-b372-a580fa4d3ae3', '019bc718-e03e-7f21-9c95-81c15446739e', '01a10e7a-14a0-7b2a-9785-2f2aa81d0a71', '01996de0-ad3c-7a0d-8bf9-7f91ffa48197', '01996968-6392-7ebc-ae1d-60b9cce59054');
