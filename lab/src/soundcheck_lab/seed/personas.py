"""The 30 demo personas and the demo fan. Fixed data, so every run seeds the same people.

The names are invented. Most personas lean indie/alternative/rock (my real clips are those
gigs); the rest cover other genres so two personas' For You feeds differ visibly in a demo."""

from __future__ import annotations

import os
from dataclasses import dataclass
from urllib.parse import urlparse

EMAIL_DOMAIN = "seed.soundcheck.test"
LOCAL_PASSWORD = "seed-demo-persona-2026"  # local stack only; set SEED_PASSWORD anywhere else
INDIE_GENRES = frozenset({"Indie", "Alternative", "Rock", "Punk"})


@dataclass(frozen=True)
class Persona:
    handle: str
    display_name: str
    city: str
    genres: tuple[str, ...]  # canonical genre names (GET /genres), strongest first
    bio: str
    # Synthetic accounts are told apart by their email domain (synthetic-email-domains in
    # application.yaml): the seed's personas here, the simulator's under sim.soundcheck.test.
    domain: str = EMAIL_DOMAIN

    @property
    def email(self) -> str:
        return f"{self.handle}@{self.domain}"

    @property
    def indie(self) -> bool:
        """Goes to the kind of gigs I filmed: indie, alternative, rock or punk."""
        return bool(INDIE_GENRES & set(self.genres))


def password() -> str:
    return os.environ.get("SEED_PASSWORD", LOCAL_PASSWORD)


def check_password_for(api_url: str) -> None:
    """The committed default password is for the local stack only: anywhere else, every persona
    would have a password anyone can read on GitHub. Raises unless SEED_PASSWORD is set there."""
    host = urlparse(api_url).hostname or ""
    if host not in {"localhost", "127.0.0.1", "::1"} and password() == LOCAL_PASSWORD:
        raise ValueError(
            f"set SEED_PASSWORD to seed {host}: the default is for the local stack only"
        )


def _p(handle: str, name: str, city: str, genres: str, bio: str) -> Persona:
    return Persona(handle, name, city, tuple(g.strip() for g in genres.split(",")), bio)


PERSONAS: tuple[Persona, ...] = (
    _p(
        "maya_frontrow",
        "Maya Okafor",
        "London",
        "Indie, Alternative, Punk",
        "Barrier or nothing. Camden most weeks.",
    ),
    _p("tomgigs", "Tom Hallett", "London", "Indie, Rock", "Ticket stubs in a shoebox since 2019."),
    _p(
        "priya_sounds",
        "Priya Raman",
        "London",
        "Alternative, Indie, Electronic",
        "Small rooms, loud bands.",
    ),
    _p(
        "jonno_live",
        "Jonno Price",
        "London",
        "Rock, Punk, Alternative",
        "If it's sweaty, I'm there.",
    ),
    _p(
        "ellie_in_the_pit",
        "Ellie Marsh",
        "London",
        "Punk, Indie",
        "Pit-adjacent. Earplugs in, always.",
    ),
    _p(
        "sam_setlist",
        "Sam Whitaker",
        "London",
        "Indie, Alternative, Folk",
        "I write the setlist down on my phone.",
    ),
    _p(
        "noor_nights",
        "Noor Haddad",
        "London",
        "Alternative, Indie, Soul",
        "Tuesday night gigs are the best gigs.",
    ),
    _p(
        "fin_fuzz",
        "Finley Burke",
        "London",
        "Rock, Alternative",
        "Pedalboard nerd, support-act defender.",
    ),
    _p(
        "grace_bassline",
        "Grace Adeyemi",
        "London",
        "Indie, Pop, Alternative",
        "Always early for the first band.",
    ),
    _p(
        "harry_headliner", "Harry Doyle", "London", "Rock, Indie", "Arenas and pubs, no in between."
    ),
    _p(
        "lola_loud",
        "Lola Brennan",
        "London",
        "Punk, Alternative, Rock",
        "Hackney, Dalston, repeat.",
    ),
    _p("kit_and_kaboodle", "Kit Morrow", "London", "Indie, Folk", "Quiet songs, loud crowds."),
    _p(
        "ben_on_the_list",
        "Ben Carver",
        "London",
        "Alternative, Rock",
        "Not actually on the guestlist.",
    ),
    _p(
        "ruby_riff", "Ruby Lane", "Brighton", "Indie, Punk", "Brighton seafront to London and back."
    ),
    _p("ollie_amps", "Ollie Grant", "Brighton", "Rock, Alternative, Metal", "Turn it up."),
    _p(
        "zara_backrow",
        "Zara Collins",
        "Manchester",
        "Indie, Alternative",
        "Manchester born, London regular.",
    ),
    _p("dan_doors", "Dan Fletcher", "Manchester", "Rock, Indie, Punk", "Doors 7, me 6:45."),
    _p("isla_encore", "Isla Fraser", "Glasgow", "Indie, Alternative, Folk", "One more song."),
    _p("callum_crowdsurf", "Callum Reid", "Bristol", "Punk, Rock", "Crowdsurfing is cardio."),
    _p("amara_afters", "Amara Nwosu", "London", "Alternative, Indie, R&B", "Gig, then the afters."),
    _p(
        "leon_techno",
        "Leon Varga",
        "London",
        "Techno, House, Electronic",
        "Warehouses after midnight.",
    ),
    _p(
        "jess_dnb", "Jess Ofori", "Bristol", "Drum and Bass, Electronic, Garage", "174 bpm forever."
    ),
    _p(
        "marcus_bars", "Marcus Bell", "London", "Hip-Hop, Grime, R&B", "Front row for every cypher."
    ),
    _p("chloe_charts", "Chloe Turner", "Birmingham", "Pop, R&B, Soul", "Pop, unapologetically."),
    _p("ivy_folk", "Ivy Holloway", "Leeds", "Folk, Country, Blues", "Fiddles and foot-stomps."),
    _p("raf_jazz", "Rafael Duarte", "London", "Jazz, Soul, Funk", "Late sets at small clubs."),
    _p("tariq_metal", "Tariq Aziz", "Liverpool", "Metal, Rock, Punk", "Heavier is better."),
    _p(
        "nina_afrobeats",
        "Nina Mensah",
        "London",
        "Afrobeats, R&B, Hip-Hop",
        "Here for the dancing.",
    ),
    _p(
        "will_classical",
        "Will Ashby",
        "Oxford",
        "Classical, Jazz, Folk",
        "Proms season is my season.",
    ),
    _p(
        "sol_reggae", "Sol Baptiste", "Bristol", "Reggae, Soul, Funk", "Sound systems and sunshine."
    ),
)

# The demo account: my real gig history (fixtures/clips/mine/gigography.csv).
DEMO = _p(
    "demofan",
    "Demo Fan",
    "London",
    "Indie, Alternative, Rock, Punk",
    "Gig-goer since June 2024. Mostly London, mostly small rooms.",
)

# My whole history on a private account (its posts reach no feed); my real
# accounts follow it to see it (the seed accepts their follow requests: --archive-followers).
ARCHIVE = _p(
    "alex_archive",
    "Alex's archive",
    "London",
    "Indie, Alternative, Rock, Punk",
    "Every gig since June 2024, with all my clips. Private test account.",
)
