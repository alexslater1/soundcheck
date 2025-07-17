-- People search matches handles and display names. One trigram index over both, as
-- one lower-cased key "handle display name", serves the whole match in one or two scans of it:
-- LIKE '%text%' and word similarity (<%). Measured on a throwaway database with 50,000 users
-- (2025-07-17): about 0.1 ms with this index, 0.25-0.57 s with separate handle and display-name indexes,
-- which the planner passed over for a sequential scan. UserRepository.search uses this exact expression.
create index app_user_search_key_trgm on identity.app_user
    using gin ((lower(handle::text || ' ' || display_name)) gin_trgm_ops);
