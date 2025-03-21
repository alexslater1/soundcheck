#!/usr/bin/env python3
"""Generate the committed, SYNTHETIC ingestion fixtures.

Real API responses are not committed: Ticketmaster only allows Event Content to be stored "for
reasonable periods in order to provide the service", and Last.fm data may not be sub-licensed. So the
fixtures that tests, CI and offline runs use are invented here, in the exact response shapes of

  - Ticketmaster Discovery API v2  GET /discovery/v2/events.json   -> fixtures/ticketmaster/page-NNN.json
  - Last.fm                        artist.getTopTags               -> fixtures/lastfm/top-tags.json
  - MusicBrainz                    GET /ws/2/artist?query=         -> fixtures/musicbrainz/artist-search.json

Every artist, venue, event, id and URL below is made up; only the city names and their centroid
coordinates are real (GeoNames, CC BY 4.0). The output is deterministic (fixed seed, fixed dates):
running the script twice gives byte-identical files.

Usage:  python3 ops/scripts/gen_fixtures.py        (from the repository root)
"""

from __future__ import annotations

import json
import random
import uuid
from datetime import date, datetime, time, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "fixtures"
SEED = 20261003
FIRST_DAY = date(2026, 10, 3)  # a Saturday; the window is seven days
DAYS = 7
PAGE_SIZE = 200
LONDON = ZoneInfo("Europe/London")

# (city, lat, lng, Ticketmaster market id, market name, outward postcode, venues, share of events)
CITIES = [
    ("London", 51.50853, -0.12574, "202", "London", "N1", 12, 26),
    ("Manchester", 53.48095, -2.23743, "205", "Wales and North West", "M1", 5, 12),
    ("Glasgow", 55.86515, -4.25763, "207", "Scotland", "G2", 4, 9),
    ("Leeds", 53.79648, -1.54785, "206", "North and North East", "LS1", 3, 7),
    ("Birmingham", 52.48142, -1.89983, "204", "Midlands and Central", "B5", 3, 7),
    ("Bristol", 51.45523, -2.59665, "203", "South", "BS1", 3, 6),
    ("Liverpool", 53.41058, -2.97794, "205", "Wales and North West", "L1", 2, 5),
    ("Newcastle upon Tyne", 54.97328, -1.61396, "206", "North and North East", "NE1", 2, 5),
    ("Sheffield", 53.38297, -1.4659, "206", "North and North East", "S1", 2, 4),
    ("Edinburgh", 55.95206, -3.19648, "207", "Scotland", "EH1", 2, 4),
    ("Cardiff", 51.48, -3.18, "205", "Wales and North West", "CF10", 2, 4),
    ("Brighton", 50.82838, -0.13947, "203", "South", "BN1", 2, 4),
    ("Nottingham", 52.9536, -1.15047, "204", "Midlands and Central", "NG1", 2, 4),
    ("Belfast", 54.59682, -5.92541, "209", "Northern Ireland", "BT1", 2, 3),
]

VENUE_FIRST = [
    "Brass", "Lantern", "Foundry", "Velvet", "Tin", "Copper", "Night", "Signal", "Harbour", "Paper",
    "Glass", "Iron", "Marble", "Cinder", "Willow", "Echo", "Anchor", "Static", "Ember", "Orchard",
]
VENUE_SECOND = [
    "Rooms", "Hall", "Arches", "Works", "Tavern", "Social", "Institute", "Ballroom", "Cellar", "Exchange",
    "Yard", "Academy", "Club", "Depot", "Assembly",
]

ARTIST_A = [
    "Velvet", "Paper", "Midnight", "Glass", "Neon", "Hollow", "Silver", "Copper", "Salt", "Winter",
    "Static", "Golden", "Lunar", "Violet", "Quiet", "Feral", "Northern", "Electric", "Crooked", "Pale",
    "Sudden", "Distant", "Broken", "Gentle", "Wild", "Slow", "Bright", "Lost", "Second", "Low",
]
ARTIST_B = [
    "Static", "Harbour", "Lanterns", "Tides", "Engines", "Foxes", "Rivers", "Signals", "Gardens", "Machines",
    "Wolves", "Atlas", "Parade", "Orchard", "Motel", "Choir", "Arcade", "Weather", "Kites", "Sirens",
    "Hours", "Cinema", "Avenue", "Bloom", "Colony", "Embers", "Radio", "Season", "Mirrors", "Circuit",
]
# Names that exercise the normaliser — all invented.
TRICKY = [
    "The 1989s",
    "Florrie + The Engine",
    "Sigrún Rós",
    "Marlow & The Tide",
    "Beyoncia",
    "MØ-ON",
    "Björn Again Tomorrow",
    "D.J. Kettle",
    "The The Thes",
    "Café del Marlow",
]

# Ticketmaster classification (genre, subGenre) -> Last.fm-style tags for the same act.
GENRES = [
    (("Rock", "Indie Rock"), ["indie rock", "indie", "britpop", "seen live"]),
    (("Rock", "Alternative Rock"), ["alternative rock", "alternative", "emo", "seen live"]),
    (("Rock", "Hard Rock"), ["hard rock", "classic rock", "rock"]),
    (("Pop", "Pop Rock"), ["pop", "synthpop", "dance pop"]),
    (("Dance/Electronic", "House"), ["house", "deep house", "electronic", "dance"]),
    (("Dance/Electronic", "Techno"), ["techno", "minimal", "electronic"]),
    (("Dance/Electronic", "Drum & Bass"), ["drum and bass", "dnb", "jungle"]),
    (("Hip-Hop/Rap", "Urban"), ["hip-hop", "rap", "uk rap", "grime"]),
    (("R&B", "Soul"), ["soul", "rnb", "neo-soul"]),
    (("Metal", "Heavy Metal"), ["heavy metal", "metalcore", "metal"]),
    (("Folk", "Folk Rock"), ["folk", "singer-songwriter", "acoustic"]),
    (("Jazz", "Jazz"), ["jazz", "bebop", "smooth jazz"]),
    (("Country", "Country"), ["country", "americana", "bluegrass"]),
    (("Reggae", "Reggae"), ["reggae", "dub", "dancehall"]),
    (("Blues", "Blues"), ["blues", "blues rock"]),
    (("Latin", "Latin"), ["latin", "reggaeton", "salsa"]),
    (("Classical", "Classical"), ["classical", "orchestral", "contemporary classical"]),
    (("World", "Afrobeats"), ["afrobeats", "afrobeat", "amapiano"]),
]
GENRE_WEIGHTS = [16, 12, 6, 12, 8, 6, 4, 8, 5, 5, 5, 3, 2, 2, 2, 1, 1, 2]
UNMAPPED_TAGS = ["female vocalists", "seen live", "british", "00s", "favourites", "beautiful", "uk"]


def norm(name: str) -> str:
    """Roughly the backend's NameNormaliser, enough to keep invented names from colliding."""
    words = "".join(c if c.isalnum() or c == " " else " " for c in name.lower().replace("&", " and ")).split()
    return " ".join(words[1:] if words and words[0] == "the" else words)


def stable_uuid(rng: random.Random) -> str:
    return str(uuid.UUID(int=rng.getrandbits(128), version=4))


def tm_id(prefix: str, n: int) -> str:
    return f"{prefix}syn{n:05d}"


def classification(genre: tuple[str, str] | None) -> list[dict]:
    name, sub = genre if genre else ("Undefined", "Undefined")
    return [
        {
            "primary": True,
            "segment": {"id": "KZFzniwnSyZfZ7v7nJ", "name": "Music"},
            "genre": {"id": "syn-" + name.lower().replace("/", "-"), "name": name},
            "subGenre": {"id": "syn-" + sub.lower().replace(" ", "-"), "name": sub},
        }
    ]


def build_artists(rng: random.Random) -> list[dict]:
    names = list(TRICKY)
    seen = {norm(n) for n in names}
    while len(names) < 150:
        style = rng.random()
        a, b = rng.choice(ARTIST_A), rng.choice(ARTIST_B)
        name = f"The {a} {b}" if style < 0.3 else (f"{a} {b}" if style < 0.85 else f"{a} & The {b}")
        # No two invented acts may normalise to the same name ("Midnight Hours" / "The Midnight Hours").
        if norm(name) not in seen:
            seen.add(norm(name))
            names.append(name)
    artists = []
    for i, name in enumerate(names):
        genre_index = rng.choices(range(len(GENRES)), weights=GENRE_WEIGHTS)[0]
        (tm_genre, tags) = GENRES[genre_index]
        roll = rng.random()
        artists.append(
            {
                "id": tm_id("K8vZ", i),
                "name": name,
                # ~12 % carry Ticketmaster's "Undefined" placeholder instead of a genre.
                "tm_genre": None if roll < 0.12 else tm_genre,
                "true_tags": tags,
                # ~45 % have a MusicBrainz link on the Ticketmaster attraction.
                "tm_mbid": stable_uuid(rng) if rng.random() < 0.45 else None,
                # Of the rest: found on MusicBrainz with a high score / a low score / not at all.
                "mb_search": rng.choices(["high", "low", "none"], weights=[50, 15, 35])[0],
                "mb_id": stable_uuid(rng),
                # Last.fm: most acts have useful tags, some only noise, some are unknown.
                "lastfm": rng.choices(["tags", "noise", "unknown"], weights=[78, 10, 12])[0],
                "popularity": rng.random(),
            }
        )
    return artists


def build_venues(rng: random.Random) -> list[dict]:
    venues = []
    names = set()
    n = 0
    for city, lat, lng, market_id, market_name, outward, count, _share in CITIES:
        for _ in range(count):
            while True:
                name = f"The {rng.choice(VENUE_FIRST)} {rng.choice(VENUE_SECOND)}"
                if (name, city) not in names:
                    names.add((name, city))
                    break
            # Within roughly 3 km of the centroid.
            v_lat = round(lat + rng.uniform(-0.025, 0.025), 6)
            v_lng = round(lng + rng.uniform(-0.04, 0.04), 6)
            venue = {
                "name": name,
                "type": "venue",
                "id": tm_id("KovZ", n),
                "test": False,
                "url": f"https://tickets.example.com/venue/{n}",
                "locale": "en-us",
                "postalCode": f"{outward} {rng.randint(1, 9)}{rng.choice('ABDEFGHJ')}{rng.choice('ABDEFGHJ')}",
                "timezone": "Europe/London",
                "city": {"name": city},
                "country": {"name": "Great Britain", "countryCode": "GB"},
                "address": {"line1": f"{rng.randint(1, 180)} {rng.choice(VENUE_FIRST)} Street"},
                "location": {"longitude": f"{v_lng:.6f}", "latitude": f"{v_lat:.6f}"},
                "markets": [
                    {"name": "All of United Kingdom", "id": "201"},
                    {"name": market_name, "id": market_id},
                ],
                "_links": {"self": {"href": f"/discovery/v2/venues/{tm_id('KovZ', n)}?locale=en-us"}},
            }
            venues.append(venue)
            n += 1
    # Two venues without coordinates (the "missing coordinates" data-quality issue).
    for v in (venues[7], venues[23]):
        del v["location"]
    return venues


def attraction(artist: dict) -> dict:
    node = {
        "name": artist["name"],
        "type": "attraction",
        "id": artist["id"],
        "test": False,
        "url": f"https://tickets.example.com/artist/{artist['id']}",
        "locale": "en-us",
        "images": [],
        "classifications": classification(artist["tm_genre"]),
        "_links": {"self": {"href": f"/discovery/v2/attractions/{artist['id']}?locale=en-us"}},
    }
    if artist["tm_mbid"]:
        node["externalLinks"] = {
            "musicbrainz": [{"id": artist["tm_mbid"], "url": f"https://musicbrainz.org/artist/{artist['tm_mbid']}"}]
        }
    return node


def build_events(rng: random.Random, artists: list[dict], venues: list[dict]) -> list[dict]:
    by_city: dict[str, list[dict]] = {}
    for v in venues:
        by_city.setdefault(v["city"]["name"], []).append(v)
    city_names = [c[0] for c in CITIES]
    city_weights = [c[7] for c in CITIES]
    popular = sorted(artists, key=lambda a: -a["popularity"])

    events = []
    taken: set[tuple[str, int]] = set()  # (venue id, day): a venue hosts one show a day
    for n in range(240):
        while True:
            offset = rng.choices(range(DAYS), weights=[22, 12, 8, 10, 12, 16, 20])[0]
            city = rng.choices(city_names, weights=city_weights)[0]
            venue = rng.choice(by_city[city])
            if (venue["id"], offset) not in taken:
                taken.add((venue["id"], offset))
                break
        day = FIRST_DAY + timedelta(days=offset)
        # Headliners skew popular; supports are drawn from everyone.
        headliner = popular[min(int(rng.expovariate(1 / 28)), len(popular) - 1)]
        lineup = [headliner]
        for _ in range(rng.choices([0, 1, 2, 3], weights=[45, 35, 15, 5])[0]):
            support = rng.choice(artists)
            if support not in lineup:
                lineup.append(support)
        start_local = time(rng.choice([18, 19, 19, 19, 20, 20, 21, 23]), rng.choice([0, 0, 30, 30, 15]))
        start_utc = datetime.combine(day, start_local, LONDON).astimezone(ZoneInfo("UTC"))
        title = headliner["name"] if rng.random() < 0.8 else f"{headliner['name']}: {rng.choice(['UK Tour', 'Album Launch', 'Live', 'Autumn Tour'])}"
        event_id = tm_id("G5v", n)
        event = {
            "name": title,
            "type": "event",
            "id": event_id,
            "test": False,
            "url": f"https://tickets.example.com/event/{event_id}",
            "locale": "en-us",
            "images": [],
            "sales": {"public": {"startDateTime": "2026-06-01T09:00:00Z", "startTBD": False}},
            "dates": {
                "start": {
                    "localDate": day.isoformat(),
                    "localTime": start_local.strftime("%H:%M:%S"),
                    "dateTime": start_utc.strftime("%Y-%m-%dT%H:%M:%SZ"),
                    "dateTBD": False,
                    "dateTBA": False,
                    "timeTBA": False,
                    "noSpecificTime": False,
                },
                "timezone": "Europe/London",
                "status": {"code": "onsale"},
                "spanMultipleDays": False,
            },
            "classifications": classification(headliner["tm_genre"]),
            "_links": {"self": {"href": f"/discovery/v2/events/{event_id}?locale=en-us"}},
            "_embedded": {"venues": [venue], "attractions": [attraction(a) for a in lineup]},
        }
        if rng.random() < 0.7:
            low = rng.choice([8, 10, 12.5, 15, 18.5, 22, 27.5, 35, 45])
            event["priceRanges"] = [
                {"type": "standard", "currency": "GBP", "min": low, "max": low + rng.choice([0, 5, 10, 22.5])}
            ]
        events.append(event)

    # ── deliberate edge cases (indices are fixed so tests can name them) ──
    # The same show listed twice (a "VIP Package" listing): entity resolution merges it into the original.
    for original, duplicate in ((30, 205), (31, 206)):
        copy = json.loads(json.dumps(events[original]))
        copy["id"] = events[duplicate]["id"]
        copy["url"] = events[duplicate]["url"]
        copy["name"] = events[original]["name"] + " - VIP Package"
        copy["_links"] = events[duplicate]["_links"]
        events[duplicate] = copy
    for i in (5, 61, 117, 173, 201, 233):  # no attraction attached -> skipped as NO_ARTISTS
        del events[i]["_embedded"]["attractions"]
        events[i]["name"] = rng.choice(["Indie Disco Night", "90s Rave Reunion", "Open Mic Showcase"])
    for i in (12, 88, 140, 199):  # time to be announced -> 19:30 local
        start = events[i]["dates"]["start"]
        start["timeTBA"] = True
        del start["localTime"]
        del start["dateTime"]
    for i, code in ((20, "cancelled"), (97, "cancelled"), (150, "cancelled"), (33, "rescheduled"),
                    (120, "rescheduled"), (210, "rescheduled"), (44, "postponed"), (180, "postponed"),
                    (60, "offsale"), (130, "offsale")):
        events[i]["dates"]["status"]["code"] = code
    # No date at all -> skipped as NO_DATE.
    events[75]["dates"]["start"] = {"dateTBD": True, "dateTBA": True, "timeTBA": True, "noSpecificTime": False}
    # The same attraction listed twice on one bill -> de-duplicated.
    events[9]["_embedded"]["attractions"].append(events[9]["_embedded"]["attractions"][0])
    # A doors time, 45 minutes before the start.
    start9 = datetime.strptime(events[9]["dates"]["start"]["dateTime"], "%Y-%m-%dT%H:%M:%SZ")
    events[9]["doorsTimes"] = {"dateTime": (start9 - timedelta(minutes=45)).strftime("%Y-%m-%dT%H:%M:%SZ")}
    # Two price ranges -> lowest minimum, highest maximum.
    events[3]["priceRanges"] = [
        {"type": "standard", "currency": "GBP", "min": 22.5, "max": 30.0},
        {"type": "vip", "currency": "GBP", "min": 60.0, "max": 95.0},
    ]

    events.sort(key=lambda e: (e["dates"]["start"].get("localDate", "9999"), e["dates"]["start"].get("localTime", "19:30:00"), e["id"]))
    return events


def write_ticketmaster(events: list[dict]) -> int:
    directory = OUT / "ticketmaster"
    directory.mkdir(parents=True, exist_ok=True)
    for old in directory.glob("page-*.json"):
        old.unlink()
    pages = [events[i : i + PAGE_SIZE] for i in range(0, len(events), PAGE_SIZE)]
    for number, chunk in enumerate(pages):
        body = {
            # Not a Ticketmaster field: marks the page as invented, so the application can refuse to mix it
            # with real listings in one database.
            "synthetic": True,
            "_embedded": {"events": chunk},
            "_links": {"self": {"href": f"/discovery/v2/events.json?size={PAGE_SIZE}&page={number}&countryCode=GB"}},
            "page": {"size": PAGE_SIZE, "totalElements": len(events), "totalPages": len(pages), "number": number},
        }
        (directory / f"page-{number:03d}.json").write_text(
            json.dumps(body, ensure_ascii=False, indent=1) + "\n", encoding="utf-8"
        )
    return len(pages)


def write_lastfm(rng: random.Random, artists: list[dict]) -> int:
    """Map of lower-cased artist name -> an artist.getTopTags response body."""
    responses = {}
    for artist in artists:
        if artist["lastfm"] == "unknown":
            continue  # the live API answers {"error": 6, "message": "The artist you supplied could not be found"}
        tags = []
        if artist["lastfm"] == "tags":
            count = 100
            for tag in artist["true_tags"]:
                tags.append({"count": count, "name": tag, "url": f"https://www.last.fm/tag/{tag.replace(' ', '+')}"})
                count = max(4, int(count * rng.uniform(0.35, 0.8)))
        for tag in rng.sample(UNMAPPED_TAGS, 3):
            tags.append({"count": rng.choice([3, 5, 8, 14, 30]), "name": tag, "url": f"https://www.last.fm/tag/{tag.replace(' ', '+')}"})
        tags.sort(key=lambda t: -t["count"])
        responses[artist["name"].lower()] = {"toptags": {"tag": tags, "@attr": {"artist": artist["name"]}}}
    path = OUT / "lastfm" / "top-tags.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(responses, ensure_ascii=False, indent=1, sort_keys=True) + "\n", encoding="utf-8")
    return len(responses)


def write_musicbrainz(artists: list[dict]) -> int:
    """Map of lower-cased artist name -> an artist search response body (only acts without a Ticketmaster MBID)."""
    responses = {}
    for artist in artists:
        if artist["tm_mbid"] or artist["mb_search"] == "none":
            continue
        score = 100 if artist["mb_search"] == "high" else 71
        responses[artist["name"].lower()] = {
            "created": "2026-10-02T00:00:00.000Z",
            "count": 1,
            "offset": 0,
            "artists": [{"id": artist["mb_id"], "type": "Group", "score": score, "name": artist["name"], "country": "GB"}],
        }
    path = OUT / "musicbrainz" / "artist-search.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(responses, ensure_ascii=False, indent=1, sort_keys=True) + "\n", encoding="utf-8")
    return len(responses)


def main() -> None:
    rng = random.Random(SEED)
    artists = build_artists(rng)
    venues = build_venues(rng)
    events = build_events(rng, artists, venues)
    pages = write_ticketmaster(events)
    tagged = write_lastfm(rng, artists)
    searchable = write_musicbrainz(artists)
    print(
        f"wrote {len(events)} events on {pages} pages, {len(venues)} venues, {len(artists)} artists; "
        f"{tagged} Last.fm responses, {searchable} MusicBrainz responses"
    )


if __name__ == "__main__":
    main()
