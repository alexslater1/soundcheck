-- The song in a clip, as the user typed it: plain text, no lookup. Optional; trimmed
-- by the API, so an empty one is stored as null.
alter table content.post_clip
    add column song text check (song is null or char_length(song) between 1 and 120);
