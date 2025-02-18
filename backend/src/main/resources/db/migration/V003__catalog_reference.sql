-- V003 — catalog reference data: genres, genre aliases, UK cities.
-- Genres and aliases are small, fixed reference data, so they are seeded here (ids are stable).
-- Cities are loaded from fixtures/geo/uk-cities.csv at startup by the catalog module (idempotent upsert).

create table catalog.genre (
  id        smallint primary key,
  slug      text not null unique,                   -- 'indie', 'techno', …
  name      text not null,
  parent_id smallint references catalog.genre(id)
);

create table catalog.genre_alias (                   -- maps Last.fm tags / Ticketmaster classifications → genre
  alias     citext primary key,
  genre_id  smallint not null references catalog.genre(id),
  weight    real not null default 1.0
);

create table catalog.city (                          -- UK towns/cities from fixtures/geo/uk-cities.csv (GeoNames, CC BY 4.0)
  id           int primary key,
  name         text not null,
  region       text,
  country_code char(2) not null default 'GB',
  location     geography(Point,4326) not null,
  population   int
);
-- GET /cities?q= — prefix match on the lower-cased name, ordered by population; expected index: city_name_prefix.
create index city_name_prefix on catalog.city (lower(name) text_pattern_ops);

-- Cross-schema FKs now that both sides exist (integrity beats purity in one database).
alter table identity.app_user
  add constraint app_user_home_city_fk foreign key (home_city_id) references catalog.city(id);
alter table identity.user_genre_preference
  add constraint user_genre_preference_genre_fk foreign key (genre_id) references catalog.genre(id);

-- ===== canonical genres =====
insert into catalog.genre (id, slug, name, parent_id) values
  (1,  'rock',          'Rock',           null),
  (2,  'indie',         'Indie',          1),
  (3,  'alternative',   'Alternative',    1),
  (4,  'punk',          'Punk',           1),
  (5,  'metal',         'Metal',          1),
  (6,  'pop',           'Pop',            null),
  (7,  'hip-hop',       'Hip-Hop',        null),
  (8,  'grime',         'Grime',          7),
  (9,  'r-and-b',       'R&B',            null),
  (10, 'soul',          'Soul',           9),
  (11, 'funk',          'Funk',           9),
  (12, 'electronic',    'Electronic',     null),
  (13, 'house',         'House',          12),
  (14, 'techno',        'Techno',         12),
  (15, 'drum-and-bass', 'Drum and Bass',  12),
  (16, 'garage',        'Garage',         12),
  (17, 'jazz',          'Jazz',           null),
  (18, 'blues',         'Blues',          null),
  (19, 'folk',          'Folk',           null),
  (20, 'country',       'Country',        null),
  (21, 'classical',     'Classical',      null),
  (22, 'reggae',        'Reggae',         null),
  (23, 'afrobeats',     'Afrobeats',      null),
  (24, 'latin',         'Latin',          null);

-- ===== aliases: Last.fm-style tags and Ticketmaster classification names =====
insert into catalog.genre_alias (alias, genre_id, weight) values
  -- Ticketmaster Discovery classification/genre names
  ('Rock', 1, 1.0), ('Pop', 6, 1.0), ('Hip-Hop/Rap', 7, 1.0), ('Dance/Electronic', 12, 1.0), ('R&B', 9, 1.0),
  ('Alternative', 3, 1.0), ('Metal', 5, 1.0), ('Jazz', 17, 1.0), ('Blues', 18, 1.0), ('Folk', 19, 1.0),
  ('Country', 20, 1.0), ('Classical', 21, 1.0), ('Reggae', 22, 1.0), ('Latin', 24, 1.0), ('Punk', 4, 1.0),
  ('Indie', 2, 1.0), ('Soul', 10, 1.0), ('Funk', 11, 1.0), ('House', 13, 1.0), ('Techno', 14, 1.0),
  ('Grime', 8, 1.0), ('Afrobeats', 23, 1.0), ('Garage', 16, 1.0),
  -- Last.fm tags (lower case; citext makes the lookup case-insensitive)
  ('indie rock', 2, 1.0), ('indie pop', 2, 0.8), ('britpop', 2, 0.9), ('shoegaze', 2, 0.7), ('post-punk', 4, 0.7),
  ('alternative rock', 3, 1.0), ('alt-rock', 3, 1.0), ('emo', 3, 0.7), ('grunge', 3, 0.8),
  ('classic rock', 1, 1.0), ('hard rock', 1, 1.0), ('psychedelic rock', 1, 0.8), ('progressive rock', 1, 0.8),
  ('heavy metal', 5, 1.0), ('metalcore', 5, 0.9), ('death metal', 5, 0.9), ('black metal', 5, 0.9), ('thrash metal', 5, 0.9),
  ('hardcore', 4, 0.8), ('pop punk', 4, 0.8), ('ska', 4, 0.6),
  ('synthpop', 6, 0.8), ('dance pop', 6, 0.9), ('k-pop', 6, 0.9), ('electropop', 6, 0.8),
  ('hip hop', 7, 1.0), ('hip-hop', 7, 1.0), ('rap', 7, 1.0), ('trap', 7, 0.9), ('uk rap', 7, 1.0), ('drill', 8, 0.8), ('uk drill', 8, 0.9),
  ('rnb', 9, 1.0), ('neo-soul', 10, 0.9), ('northern soul', 10, 0.8), ('motown', 10, 0.8),
  ('electronic', 12, 1.0), ('electronica', 12, 1.0), ('edm', 12, 0.9), ('dance', 12, 0.9), ('ambient', 12, 0.6),
  ('trance', 12, 0.8), ('idm', 12, 0.7), ('synthwave', 12, 0.7), ('breakbeat', 12, 0.7),
  ('deep house', 13, 1.0), ('tech house', 13, 0.9), ('disco', 13, 0.6), ('minimal', 14, 0.8), ('acid', 14, 0.7),
  ('dnb', 15, 1.0), ('drum and bass', 15, 1.0), ('drum & bass', 15, 1.0), ('jungle', 15, 0.9), ('liquid', 15, 0.7),
  ('uk garage', 16, 1.0), ('bassline', 16, 0.8), ('2-step', 16, 0.9), ('dubstep', 12, 0.8),
  ('smooth jazz', 17, 0.8), ('bebop', 17, 0.8), ('swing', 17, 0.7),
  ('delta blues', 18, 0.9), ('blues rock', 18, 0.8),
  ('singer-songwriter', 19, 0.7), ('acoustic', 19, 0.6), ('folk rock', 19, 0.8), ('americana', 20, 0.7),
  ('bluegrass', 20, 0.8), ('country rock', 20, 0.8),
  ('orchestral', 21, 0.9), ('opera', 21, 0.8), ('contemporary classical', 21, 0.9),
  ('dub', 22, 0.8), ('dancehall', 22, 0.8), ('ska punk', 4, 0.6),
  ('afrobeat', 23, 0.9), ('afro-pop', 23, 0.8), ('amapiano', 23, 0.8),
  ('reggaeton', 24, 0.9), ('salsa', 24, 0.8), ('bachata', 24, 0.8);
