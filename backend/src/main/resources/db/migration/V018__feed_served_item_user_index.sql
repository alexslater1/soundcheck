-- V018 — feed: find a user's served items (the account purge anonymises them; the console's feed
-- inspector reads them). Created on the partitioned table, so every partition gets it.
create index served_item_user on feed.served_item (user_id, served_at);
