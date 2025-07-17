-- H45: people search matches display names as well as handles. The handle has had a trigram
-- index since V002 (app_user_handle_trgm); this one serves LIKE 'word%' / '% word%' and word similarity
-- (<%) on the lower-cased display name, the expression UserRepository.search uses.
create index app_user_display_name_trgm on identity.app_user using gin (lower(display_name) gin_trgm_ops);
