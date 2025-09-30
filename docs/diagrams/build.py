"""Diagrams of the system, drawn from data.

    python3 docs/diagrams/build.py            # write the SVGs (light and dark) from the data below
    python3 docs/diagrams/build.py --png      # ... and render each SVG to a PNG with headless Chrome
    python3 docs/diagrams/build.py --check    # fail on > 12 boxes, text < 24 px, a box too narrow for its
                                              # text, a cited source file missing at HEAD, or a stale SVG/PNG

`make diagrams` and `make diagrams-check` call the first two forms. Standard library only.

The figures are data: each box, line and label is written here, and every figure names the files it was
drawn from (`sources`), which the check finds at HEAD.

Every PNG is rendered by one headless Chrome at a time; before each, the script waits until the machine has
at least 3 GB available (the laptop is shared with other work).
"""
from __future__ import annotations

import argparse
import hashlib
import html
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass, field
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
W, H = 1920, 1080
MAX_BOXES = 12
MIN_FONT = 24
MANIFEST = HERE / "manifest.json"
CHROME = os.environ.get("CHROME", "/usr/bin/google-chrome")

# Installed on the laptop (fc-list): Ubuntu Sans and Ubuntu Sans Mono.
SANS = "'Ubuntu Sans', 'DejaVu Sans', sans-serif"
MONO = "'Ubuntu Sans Mono', 'DejaVu Sans Mono', monospace"

# The app's own palette (app/src/theme/tokens.ts), so the diagrams look like the product. On dark, the
# purple and the amber are lightened so text in them keeps 4.5:1.
THEMES = {
    "light": dict(bg="#F7F6FB", surface="#FFFFFF", ink="#14121C", muted="#5E5B70", border="#C9C5DD",
                  line="#4A4760", accent="#6D28D9", accent_soft="#EDE5FD", accent2="#0E7490",
                  warn="#A34A06", warn_soft="#FCEFE2", frame="#E6E3F0"),
    "dark": dict(bg="#141418", surface="#1F1F27", ink="#FAFAFA", muted="#B4B4BE", border="#3D3D4C",
                 line="#C4C4CF", accent="#B69CFB", accent_soft="#2A1F4D", accent2="#5CDDF2",
                 warn="#FBBF24", warn_soft="#3A2A0B", frame="#26262F"),
}

TITLE, BODY, LABEL = 32, 26, 26  # px: box titles, box lines, arrow labels and notes


# ── the drawing vocabulary ─────────────────────────────────────────────────────────────────────────────

@dataclass(frozen=True)
class Box:
    id: str
    x: float
    y: float
    w: float
    h: float
    title: str
    lines: tuple[str, ...] = ()
    style: str = "plain"  # plain | accent (the part the figure is about) | ext (outside service) | warn (rejected)
    mono: tuple[int, ...] = ()  # indexes of `lines` set in the mono face
    title_size: int = TITLE

    def side(self, s: str, t: float = 0.5) -> tuple[float, float]:
        """A point on side l/r/t/b, t of the way along it."""
        return {
            "l": (self.x, self.y + self.h * t),
            "r": (self.x + self.w, self.y + self.h * t),
            "t": (self.x + self.w * t, self.y),
            "b": (self.x + self.w * t, self.y + self.h),
        }[s]


@dataclass(frozen=True)
class Arrow:
    pts: tuple[tuple[float, float], ...]
    label: str = ""
    at: tuple[float, float] | None = None  # the label's anchor point (baseline)
    anchor: str = "start"
    style: str = "call"  # call (solid) | async (dashed: a queue or after a commit) | reply (dashed, thin)
    tone: str = "line"  # line | accent | warn
    rotate: int = 0
    head: bool = True


@dataclass(frozen=True)
class Text:
    x: float
    y: float
    text: str
    size: int = LABEL
    tone: str = "muted"
    anchor: str = "start"
    weight: int = 400
    mono: bool = False
    rotate: int = 0


@dataclass(frozen=True)
class Frame:
    x: float
    y: float
    w: float
    h: float
    label: str = ""


@dataclass(frozen=True)
class Marker:
    """A lettered circle that ties a step to a note (the upload sequence's failure modes)."""

    x: float
    y: float
    letter: str


@dataclass
class Figure:
    id: str
    title: str  # README heading
    claim: str  # aria-label: what the picture shows
    sources: tuple[str, ...]  # repo paths it was drawn from (checked at HEAD)
    boxes: list[Box] = field(default_factory=list)
    arrows: list[Arrow] = field(default_factory=list)
    texts: list[Text] = field(default_factory=list)
    frames: list[Frame] = field(default_factory=list)
    lifelines: list[tuple[float, float, float]] = field(default_factory=list)  # (x, y1, y2)
    markers: list[Marker] = field(default_factory=list)

    def box(self, b: Box) -> Box:
        self.boxes.append(b)
        return b


def width(text: str, size: float, weight: int = 400, mono: bool = False) -> float:
    """A slightly generous estimate of a run's width in px. Measured on the rendered PNGs (2025-10-01): Ubuntu
    Sans averages about 0.52 em a character at weight 600 and 0.49 em at 400; the mono face is 0.5 em."""
    factor = 0.6 if mono else (0.54 if weight >= 600 else 0.51)
    return len(text) * size * factor


# ── figure 1: system context ───────────────────────────────────────────────────────────────────────────

def context() -> Figure:
    f = Figure(
        "01-context",
        "System context",
        "Soundcheck in its surroundings: fans on Android and the web, an operator and the demo seeder call it; "
        "it calls six outside services and sends its telemetry to Grafana Cloud. Nothing outside calls in.",
        (
            "backend/src/main/java/app/soundcheck/ingestion/internal/IngestionSchedule.java",
            "backend/src/main/java/app/soundcheck/ingestion/internal/source/venues/PoliteFetcher.java",
            "backend/src/main/java/app/soundcheck/ingestion/internal/enrich/ArtistEnricher.java",
            "backend/src/main/java/app/soundcheck/ingestion/internal/enrich/VenueGeocoder.java",
            "backend/src/main/java/app/soundcheck/ingestion/internal/pastgigs/SetlistFmClient.java",
            "backend/src/main/java/app/soundcheck/notifications/internal/push/FcmClient.java",
            "backend/src/main/resources/application-aws.yaml",
            "lab/src/soundcheck_lab/seed/api.py",
        ),
    )
    left = [
        ("android", "Fan on Android", "Expo app on a Pixel", "browse, post"),
        ("web", "Fan in a browser", "the same Expo app", "the same, on web"),
        ("console", "Operator", "the console, web only", "run the system"),
        ("seed", "Demo seeder", "Python, via the API", "public API only"),
    ]
    for i, (bid, title, line, label) in enumerate(left):
        cy = 260 + i * 180
        f.box(Box(f"c-{bid}", 60, cy - 65, 380, 130, title, (line,)))
        f.arrows.append(Arrow(((440, cy), (680, cy)), label, at=(452, cy - 14)))
    f.box(Box("c-sys", 680, 96, 480, 876, "Soundcheck", (
        "gig discovery + concert clips",
        "",
        "Expo app: Android and web",
        "Spring Boot modular monolith",
        "Postgres + PostGIS, S3, SQS",
        "",
        "live on AWS, eu-north-1",
    ), style="accent", title_size=44))
    right = [  # the outside services, then telemetry
        ("tm", "Ticketmaster", "UK gig listings", "daily, 05:10"),
        ("venues", "London venue sites", "21 venues' own pages", "robots.txt, ≥ 5 s"),
        ("mb", "MusicBrainz · Last.fm", "artist ids, genre tags", "each new artist"),
        ("postcodes", "postcodes.io", "postcode → coordinates", "pin-less venues"),
        ("setlistfm", "setlist.fm", "past gigs, setlists", "backfill, lookups"),
        ("fcm", "Firebase (FCM)", "push notifications", "per inbox entry"),
        ("grafana", "Grafana Cloud", "traces, metrics, logs", "OTLP"),
    ]
    for i, (bid, title, line, label) in enumerate(right):
        y = 96 + i * 128
        f.box(Box(f"c-{bid}", 1420, y, 460, 108, title, (line,), style="ext"))
        f.arrows.append(Arrow(((1160, y + 54), (1420, y + 54)), label, at=(1174, y + 40)))
    return f


# ── figure 2: containers ───────────────────────────────────────────────────────────────────────────────

def containers() -> Figure:
    f = Figure(
        "02-containers",
        "Containers (as deployed)",
        "One jar, two roles: the API service answers requests through CloudFront and an internal load "
        "balancer; transcode workers take jobs from SQS; video bytes go from the phone straight to S3.",
        (
            "infra/lib/edge-stack.ts",
            "infra/lib/compute-stack.ts",
            "infra/lib/data-stack.ts",
            "backend/src/main/java/app/soundcheck/shared/config/Roles.java",
            "backend/src/main/java/app/soundcheck/media/internal/TranscodeRelay.java",
            "backend/src/main/java/app/soundcheck/media/internal/worker/TranscodeWorker.java",
        ),
    )
    browser = f.box(Box("k-browser", 60, 160, 330, 130, "Browser", ("web build + /console",)))
    phone = f.box(Box("k-phone", 60, 420, 330, 130, "Phone", ("Android release APK",)))
    cf = f.box(Box("k-cf", 490, 150, 380, 190, "CloudFront", ("one HTTPS domain", "/*  /api/*  /media/*"), mono=(1,)))
    web = f.box(Box("k-web", 490, 450, 380, 130, "S3 web bucket", ("the static web app",), style="ext"))
    media = f.box(Box("k-media", 490, 760, 380, 160, "S3 media bucket", ("uploads/: private", "media/: through OAC"), style="ext"))
    alb = f.box(Box("k-alb", 960, 180, 330, 130, "Internal ALB", ("VPC origin only",)))
    api = f.box(Box("k-api", 1390, 120, 400, 190, "API service", ("roles: api, scheduler", "Fargate · 1–2 tasks"), style="accent"))
    rds = f.box(Box("k-rds", 1390, 440, 400, 150, "PostgreSQL 17", ("+ PostGIS · RDS",)))
    sqs = f.box(Box("k-sqs", 960, 760, 330, 160, "SQS transcode", ("+ dead-letter queue",), style="ext"))
    workers = f.box(Box("k-workers", 1390, 730, 400, 190, "Transcode workers", ("role: worker + FFmpeg", "Fargate Spot · 0–2"), style="accent"))
    f.arrows += [
        Arrow((browser.side("r"), cf.side("l", 0.3)), "HTTPS", at=(404, 192)),
        Arrow((phone.side("r", 0.3), (440, 459), (440, 297), cf.side("l", 0.77)), "HTTPS", at=(452, 404)),
        Arrow((cf.side("b", 0.4), web.side("t", 0.4)), "/*", at=(660, 405), style="call"),
        Arrow(((800, 340), (800, 400), (920, 400), (920, 840), media.side("r", 0.5)), "/media/*", at=(932, 620)),
        Arrow((cf.side("r", 0.5), alb.side("l", 0.5)), "/api/*", at=(884, 228)),
        Arrow((alb.side("r"), api.side("l", 125 / 190)), "HTTP", at=(1304, 232)),
        Arrow((phone.side("b", 0.4), (192, 840), media.side("l")), "presigned PUT: bytes skip the API",
              at=(80, 890), tone="accent"),
        Arrow((api.side("b"), rds.side("t")), "SQL (jOOQ)", at=(1604, 384)),
        Arrow(((1390, 290), (1340, 290), (1340, 700), (1125, 700), (1125, 760)), "outbox relay, after commit",
              at=(1328, 686), anchor="end", style="async"),
        Arrow((sqs.side("r", 0.5), workers.side("l", 110 / 190)), "poll", at=(1312, 826), style="async"),
        Arrow((workers.side("t"), rds.side("b")), "claim · finalize", at=(1604, 670)),
        Arrow(((1590, 920), (1590, 990), (680, 990), media.side("b", 0.5)), "original in, HLS ladder out", at=(1060, 980)),
    ]
    f.texts.append(Text(1872, 520, "one jar, two roles (soundcheck.roles)", size=26, tone="accent", anchor="middle",
                        weight=600, rotate=-90))
    f.arrows.append(Arrow(((1800, 215), (1830, 215), (1830, 825), (1800, 825)), head=False, tone="accent", style="reply"))
    return f


# ── figure 3: the upload sequence with its failure modes ──────────────────────────────────────────────

LANES = {"Phone": 116, "API": 296, "Postgres": 476, "SQS": 656, "Worker": 836, "S3": 1016, "CloudFront": 1196}


def upload() -> Figure:
    f = Figure(
        "03-upload-sequence",
        "Upload → ready, and what happens when each step fails",
        "The upload sequence: the phone gets a presigned URL, puts the bytes straight into S3, completes; the API "
        "records UPLOADED and the event in one transaction and relays it to SQS after the commit; a worker claims the "
        "clip with a conditional UPDATE, transcodes, finalizes only if it still holds the claim, then deletes the "
        "message. Lettered notes give the behaviour when each step fails.",
        (
            "backend/src/main/java/app/soundcheck/media/internal/UploadService.java",
            "backend/src/main/java/app/soundcheck/media/internal/TranscodeRelay.java",
            "backend/src/main/java/app/soundcheck/shared/outbox/OutboxResubmitter.java",
            "backend/src/main/java/app/soundcheck/media/internal/worker/TranscodeWorker.java",
            "backend/src/main/java/app/soundcheck/media/internal/worker/TranscodeJobs.java",
            "backend/src/main/java/app/soundcheck/media/internal/worker/WorkerProperties.java",
            "app/src/features/upload/uploadMachine.ts",
        ),
    )
    for name, x in LANES.items():
        f.box(Box(f"u-{name}", x - 84, 40, 168, 72, name, (), title_size=28))
        f.lifelines.append((x, 112, 1010))
    steps = [
        ("Phone", "API", "1  ticket: POST /media/uploads", "call", "line", None),
        ("API", "Postgres", "asset AWAITING_UPLOAD", "call", "line", None),
        ("API", "Phone", "presigned URL: type + length signed", "reply", "line", None),
        ("Phone", "S3", "2  PUT the bytes straight to S3", "call", "accent", "A"),
        ("Phone", "API", "3  POST …/complete", "call", "line", "B"),
        ("API", "S3", "HeadObject: the right size?", "call", "line", None),
        ("API", "Postgres", "UPLOADED + event, one transaction", "call", "line", None),
        ("API", "SQS", "4  relay after the commit (+ traceparent)", "async", "line", "C"),
        ("SQS", "Worker", "5  receive (long poll)", "async", "line", "D"),
        ("Worker", "Postgres", "6  claim: one conditional UPDATE", "call", "accent", None),
        ("Worker", "S3", "7  transcode to an HLS ladder", "call", "line", "E"),
        ("Worker", "Postgres", "8  READY + MediaReady, if still mine", "call", "line", "F"),
        ("Worker", "SQS", "9  delete the message, last", "call", "line", None),
        ("Phone", "CloudFront", "10  play master.m3u8 through CloudFront", "call", "line", None),
    ]
    y = 168
    ends: dict[str, tuple[float, float]] = {}
    for src, dst, label, style, tone, letter in steps:
        x1, x2 = LANES[src], LANES[dst]
        lx = min(x1, x2) + 14
        f.arrows.append(Arrow(((x1, y), (x2, y)), label, at=(lx, y - 12), style=style, tone=tone))
        # a marker sits just past the end of its label (labels render at 0.44–0.5 em a character)
        end = max(lx + len(label) * LABEL * 0.5, max(x1, x2)) + 30
        if letter:
            f.markers.append(Marker(end, y - 20, letter))
        ends[label] = (end, y - 20)
        y += 59
    # the failure notes, in the order of their letters (checked against the code)
    notes = [
        ("A", "Cut off mid-upload: Retry sends", "only the missing parts (no auto-resume)"),
        ("B", "complete twice: one job", "(checked on AWS, 2025-10-01)"),
        ("C", "API dies after the commit:", "the outbox resends the event"),
        ("D", "Duplicate message: the claim gets", "0 rows; a finished clip's is deleted"),
        ("E", "Worker dies or Spot reclaims it:", "released, or its 15-min lease expires"),
        ("F", "Lease lost: finalize writes", "nothing; the new owner finishes"),
        ("G", "Not a video: REJECTED, no retry;", "3 failures: FAILED, DLQ, alarm"),
    ]
    f.texts.append(Text(1360, 172, "If it fails", size=30, tone="warn", weight=700))
    ny = 236
    for letter, a, b in notes:
        f.markers.append(Marker(1378, ny - 9, letter))
        f.texts.append(Text(1414, ny, a, size=BODY, tone="ink"))
        f.texts.append(Text(1414, ny + 33, b, size=BODY, tone="ink"))
        ny += 108
    # G hangs off step 7 too: the worker is where a bad file or a third failure is found
    gx, gy = ends["7  transcode to an HLS ladder"]
    f.markers.append(Marker(gx + 44, gy, "G"))
    return f


# ── figure 4: the feed pipeline and the learning loop ──────────────────────────────────────────────────

def feed() -> Figure:
    f = Figure(
        "04-feed-pipeline",
        "The feed: ranked once per session, logged as served, learned from offline",
        "The For You pipeline: user context, candidates from seven SQL generators, twelve features, a logistic "
        "score, MMR diversity, exploration, a 200-item session and the served-item log; the loop below reads that "
        "log through one label view, trains in Python, publishes a DRAFT config that the playground compares and "
        "activates. The purple steps are one pure function shared with the playground's preview.",
        (
            "backend/src/main/java/app/soundcheck/feed/internal/HomeFeedService.java",
            "backend/src/main/java/app/soundcheck/feed/internal/CandidateRepository.java",
            "backend/src/main/java/app/soundcheck/feed/internal/FeedRanker.java",
            "backend/src/main/java/app/soundcheck/feed/internal/ranking/Scorer.java",
            "backend/src/main/java/app/soundcheck/feed/internal/ranking/MmrReranker.java",
            "backend/src/main/java/app/soundcheck/feed/internal/ranking/ExplorationInjector.java",
            "backend/src/main/resources/db/migration/V130__feed_served_outcome.sql",
            "contracts/ranking/extract.sql",
            "contracts/ranking/parity-cases.json",
            "lab/src/soundcheck_lab/train.py",
            "app/src/features/console/ranking/RankingPage.tsx",
        ),
    )
    xs = (40, 500, 960, 1420)
    bw, bh = 410, 190
    r1, r2, r3 = 80, 400, 740
    row1 = [
        Box("f-ctx", xs[0], r1, bw, bh, "User context", ("follows, genres, home,", "blocks, recently seen")),
        Box("f-cand", xs[1], r1, bw, bh, "Candidates", ("7 generators in one", "SQL statement")),
        Box("f-feat", xs[2], r1, bw, bh, "12 features", ("each in [0, 1]: genre", "similarity, recency…")),
        Box("f-score", xs[3], r1, bw, bh, "Score", ("σ(bias + Σ wᵢ·fᵢ)", "a contribution per feature"), style="accent", mono=(0,)),
    ]
    row2 = [
        Box("f-served", xs[0], r2, bw, bh, "served_item log", ("features as served,", "position, exploration")),
        Box("f-session", xs[1], r2, bw, bh, "Session", ("200 items, cut into", "pages of 10")),
        Box("f-explore", xs[2], r2, bw, bh, "Explore", ("10 % of the slots from", "the 4th: fresh posts"), style="accent"),
        Box("f-mmr", xs[3], r2, bw, bh, "Diversify (MMR)", ("score vs similarity,", "≤ 5 posts per author"), style="accent"),
    ]
    row3 = [
        Box("f-label", xs[0], r3, bw, bh, "served_outcome", ("one definition of", "“engaged” (a view)")),
        Box("f-lab", xs[1], r3, bw, bh, "Python lab", ("logistic regression", "+ a position prior")),
        Box("f-draft", xs[2], r3, bw, bh, "DRAFT config", ("weights + metrics,", "marked simulated")),
        Box("f-play", xs[3], r3, bw, bh, "Playground", ("preview, replay,", "then Activate")),
    ]
    for b in row1 + row2 + row3:
        f.box(b)
    for a, b in zip(row1, row1[1:]):
        f.arrows.append(Arrow((a.side("r"), b.side("l"))))
    for a, b in zip(row2[::-1], row2[::-1][1:]):  # right to left
        f.arrows.append(Arrow((a.side("l"), b.side("r"))))
    for a, b in zip(row3, row3[1:]):
        f.arrows.append(Arrow((a.side("r"), b.side("l"))))
    f.arrows.append(Arrow((row1[3].side("b"), row2[3].side("t")), "scored list", at=(1639, 344)))
    f.arrows.append(Arrow((row2[0].side("b"), row3[0].side("t")), "joined with events", at=(259, 680)))
    f.arrows.append(Arrow((row3[3].side("r"), (1864, 835), (1864, 175), row1[3].side("r")), tone="accent", style="async"))
    f.texts.append(Text(1898, 505, "Activate: every API instance reloads", size=26, tone="accent", anchor="middle",
                        weight=600, rotate=-90))
    f.texts.append(Text(510, 668, "the same σ in Python and Java: 26 parity cases, |Δ| < 1e-6", size=26, tone="ink"))
    f.texts.append(Text(40, 1010, "Purple: FeedRanker.rank, one pure function for the live feed and the playground's preview",
                        size=26, tone="accent", weight=600))
    return f


# ── figure 5: the AWS deployment ───────────────────────────────────────────────────────────────────────

def aws() -> Figure:
    f = Figure(
        "05-aws",
        "AWS deployment (eu-north-1, as run on 2025-10-01)",
        "The AWS deployment: one CloudFront domain in front of two S3 buckets and, through a VPC origin, an "
        "internal load balancer; API tasks and Spot transcode workers in public subnets with no NAT gateway; "
        "PostgreSQL in isolated subnets; SQS with a dead-letter queue; secrets, alarms and telemetry beside them.",
        (
            "infra/lib/network-stack.ts",
            "infra/lib/data-stack.ts",
            "infra/lib/edge-stack.ts",
            "infra/lib/compute-stack.ts",
            "infra/lib/observability-stack.ts",
            "infra/cdk.json",
            "backend/src/main/resources/application-aws.yaml",
        ),
    )
    f.frames.append(Frame(440, 370, 900, 640, ""))
    f.frames.append(Frame(462, 430, 856, 200, ""))
    f.frames.append(Frame(462, 660, 856, 300, ""))
    f.texts += [  # frame labels, placed where no line crosses them
        Text(1320, 404, "VPC · 2 AZs · no NAT · S3 gateway endpoint", size=24, tone="muted", anchor="end", weight=600),
        Text(1300, 458, "isolated subnets", size=24, tone="muted", anchor="end", weight=600),
        Text(482, 694, "public subnets", size=24, tone="muted", weight=600),
    ]
    clients = f.box(Box("a-clients", 60, 90, 330, 130, "Phone, browser", ("the Expo app",)))
    cf = f.box(Box("a-cf", 480, 70, 440, 190, "CloudFront", ("HTTPS, security headers", "/*  /api/*  /media/*"), mono=(1,)))
    media = f.box(Box("a-media", 1420, 70, 440, 130, "S3 media bucket", ("OAC reads media/* only",), style="ext"))
    web = f.box(Box("a-web", 1420, 225, 440, 120, "S3 web bucket", ("the static web app",), style="ext"))
    f.box(Box("a-grafana", 1420, 400, 440, 115, "Grafana Cloud", ("OTLP; traces at 10 %",), style="ext"))
    f.box(Box("a-secrets", 1420, 540, 440, 115, "Secrets Manager", ("one JSON app secret",), style="ext"))
    f.box(Box("a-cw", 1420, 680, 440, 115, "CloudWatch alarms", ("DLQ, ALB 5xx → e-mail",), style="ext"))
    sqs = f.box(Box("a-sqs", 1420, 820, 440, 130, "SQS + DLQ", ("3 receives, then DLQ",), style="ext"))
    alb = f.box(Box("a-alb", 484, 466, 380, 140, "Internal ALB", ("from CloudFront only",)))
    rds = f.box(Box("a-rds", 916, 466, 380, 140, "RDS PostgreSQL 17", ("t4g.micro, single-AZ",)))
    api = f.box(Box("a-api", 484, 712, 380, 190, "API service", ("api + scheduler roles", "0.5 vCPU · 1–2 tasks"), style="accent"))
    wk = f.box(Box("a-workers", 916, 712, 380, 190, "Transcode workers", ("Fargate Spot, 2 vCPU", "0–2 tasks on backlog"), style="accent"))
    f.arrows += [
        Arrow((clients.side("r", 0.5), cf.side("l", 85 / 190)), "HTTPS", at=(398, 141)),
        Arrow((cf.side("r", 0.35), media.side("l", 0.5)), "/media/*", at=(1180, 122)),
        Arrow(((920, 220), (1140, 220), (1140, 285), web.side("l", 0.5)), "/*", at=(1160, 272)),
        Arrow((cf.side("b", (674 - 480) / 440), alb.side("t", 0.5)), "/api/* · VPC origin", at=(690, 330)),
        Arrow((alb.side("b"), api.side("t")), "HTTP", at=(688, 668)),
        Arrow(((864, 760), (890, 760), (890, 560), rds.side("l", 94 / 140)), "SQL", at=(880, 648), anchor="end"),
        Arrow((wk.side("t", 0.5), rds.side("b", 0.5)), "claim", at=(1120, 668)),
        Arrow(((674, 902), (674, 978), (1390, 978), (1390, 920), sqs.side("l", 100 / 130)), "outbox relay",
              at=(1180, 968), style="async"),
        Arrow((sqs.side("l", 30 / 130), wk.side("r", 138 / 190)), "poll", at=(1308, 838), style="async"),
        Arrow(((225, 90), (225, 40), (1640, 40), media.side("t", 0.5)), "presigned PUT: bytes skip the API",
              at=(560, 30), tone="accent"),
    ]
    f.texts += [
        Text(60, 330, "Decisions on this picture", size=28, tone="ink", weight=700),
        Text(60, 380, "No NAT gateway;", size=26),
        Text(60, 412, "tasks get public IPs, but", size=26),
        Text(60, 444, "take traffic from the ALB", size=26),
        Text(60, 492, "The ALB answers", size=26),
        Text(60, 524, "CloudFront only", size=26),
        Text(60, 572, "Spot workers,", size=26),
        Text(60, 604, "scaled from zero", size=26),
        Text(60, 652, "One AZ", size=26),
        Text(60, 684, "for the database", size=26),
    ]
    return f


# ── figure 6: the dual write against the outbox ───────────────────────────────────────────────────────

def outbox() -> Figure:
    f = Figure(
        "06-outbox",
        "Why an outbox: the dual write and the outbox, side by side",
        "On the left, the rejected dual write: the status commits, then the process dies before the send, so the "
        "clip waits forever (or, sending first, a rollback leaves a job for an upload that never completed). On "
        "the right, the outbox: the status and the event row commit together, the relay sends after the commit "
        "and marks the row done, a resubmitter resends what a crash left, and the worker's claim absorbs a duplicate.",
        (
            "backend/src/main/java/app/soundcheck/media/internal/UploadService.java",
            "backend/src/main/java/app/soundcheck/media/internal/TranscodeRelay.java",
            "backend/src/main/java/app/soundcheck/shared/outbox/OutboxResubmitter.java",
            "backend/src/main/java/app/soundcheck/media/internal/worker/TranscodeJobs.java",
        ),
    )
    f.texts.append(Text(80, 110, "Rejected: commit, then send", size=34, tone="warn", weight=700))
    f.texts.append(Text(1000, 110, "Built: the transactional outbox", size=34, tone="accent", weight=700))
    tx1 = f.box(Box("o-tx1", 80, 170, 760, 160, "Transaction", ("UPDATE media_asset SET status", "= 'UPLOADED'; COMMIT"), mono=(0, 1)))
    send1 = f.box(Box("o-send1", 80, 560, 760, 130, "SQS SendMessage", ("never happens",), style="warn"))
    f.arrows.append(Arrow((tx1.side("b"), (460, 412)), tone="warn", head=False))
    f.markers.append(Marker(460, 446, "×"))
    f.arrows.append(Arrow(((460, 480), send1.side("t")), tone="warn", style="async"))
    f.texts.append(Text(494, 455, "the process dies here", size=28, tone="warn", weight=600))
    f.texts += [
        Text(80, 770, "The clip waits in UPLOADED forever:", size=28, tone="ink", weight=600),
        Text(80, 808, "nothing will ever send its job.", size=28, tone="ink"),
        Text(80, 880, "Send first instead? A rollback leaves", size=28, tone="ink", weight=600),
        Text(80, 918, "a job for an upload that never completed.", size=28, tone="ink"),
    ]
    tx2 = f.box(Box("o-tx2", 1000, 170, 840, 160, "One transaction", ("status = 'UPLOADED'", "+ INSERT event_publication"), style="accent", mono=(0, 1)))
    relay = f.box(Box("o-relay", 1000, 470, 380, 160, "TranscodeRelay", ("after the commit:", "send, then mark done")))
    sqs = f.box(Box("o-sqs", 1480, 470, 360, 160, "SQS", ("a duplicate is fine:", "the claim absorbs it"), style="ext"))
    resub = f.box(Box("o-resub", 1000, 770, 380, 170, "OutboxResubmitter", ("every minute: resend", "rows left incomplete")))
    f.arrows += [
        Arrow((tx2.side("b", 190 / 840), relay.side("t", 0.5)), "after commit", at=(1206, 412), style="async"),
        Arrow((relay.side("r"), sqs.side("l")), "send", at=(1394, 536)),
        Arrow(((1100, 770), (1100, 630)), "a crash left it? run it again", at=(1114, 712), anchor="start", tone="accent"),
    ]
    return f


FIGURES = (context, containers, upload, feed, aws, outbox)


# ── SVG ───────────────────────────────────────────────────────────────────────────────────────────────

def esc(s: str) -> str:
    return html.escape(s, quote=True)


def color(t: dict[str, str], tone: str) -> str:
    return {"line": t["line"], "accent": t["accent"], "warn": t["warn"], "ink": t["ink"], "muted": t["muted"],
            "accent2": t["accent2"]}[tone]


def svg(fig: Figure, theme: str) -> str:
    t = THEMES[theme]
    out: list[str] = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}" '
        f'role="img" aria-label="{esc(fig.claim)}" font-family="{esc(SANS)}">',
        f"<title>{esc(fig.title)}</title>",
        "<defs>",
    ]
    for tone in ("line", "accent", "warn"):
        out.append(
            f'<marker id="h-{tone}" viewBox="0 0 12 12" refX="10" refY="6" markerWidth="12" markerHeight="12" '
            f'markerUnits="userSpaceOnUse" orient="auto-start-reverse"><path d="M0,0 L12,6 L0,12 z" fill="{color(t, tone)}"/></marker>'
        )
    out.append("</defs>")
    out.append(f'<rect width="{W}" height="{H}" fill="{t["bg"]}"/>')
    for fr in fig.frames:
        out.append(f'<rect x="{fr.x}" y="{fr.y}" width="{fr.w}" height="{fr.h}" rx="20" fill="{t["frame"]}" '
                   f'fill-opacity="0.55" stroke="{t["border"]}" stroke-width="2" stroke-dasharray="10 8"/>')
        if fr.label:
            out.append(f'<text x="{fr.x + 20}" y="{fr.y + 34}" font-size="24" fill="{t["muted"]}" font-weight="600">{esc(fr.label)}</text>')
    for x, y1, y2 in fig.lifelines:
        out.append(f'<line x1="{x}" y1="{y1}" x2="{x}" y2="{y2}" stroke="{t["border"]}" stroke-width="3" stroke-dasharray="6 8"/>')
    for a in fig.arrows:
        c = color(t, a.tone)
        dash = ' stroke-dasharray="14 10"' if a.style in ("async", "reply") else ""
        sw = 2.5 if a.style == "reply" else 3.5
        pts = " ".join(f"{x},{y}" for x, y in a.pts)
        head = f' marker-end="url(#h-{a.tone})"' if a.head else ""
        out.append(f'<polyline points="{pts}" fill="none" stroke="{c}" stroke-width="{sw}" '
                   f'stroke-linejoin="round"{dash}{head}/>')
    for b in fig.boxes:
        fill, stroke, sw, dash = t["surface"], t["border"], 2.5, ""
        if b.style == "accent":
            fill, stroke, sw = t["accent_soft"], t["accent"], 4
        elif b.style == "ext":
            dash = ' stroke-dasharray="10 7"'
            stroke = t["muted"]
        elif b.style == "warn":
            fill, stroke, sw, dash = t["warn_soft"], t["warn"], 3.5, ' stroke-dasharray="10 7"'
        out.append(f'<rect x="{b.x}" y="{b.y}" width="{b.w}" height="{b.h}" rx="16" fill="{fill}" stroke="{stroke}" '
                   f'stroke-width="{sw}"{dash}/>')
        lines = [ln for ln in b.lines]
        content = b.title_size + len(lines) * (BODY + 12)
        ty = b.y + (b.h - content) / 2 + b.title_size * 0.82
        if not lines:
            out.append(f'<text x="{b.x + b.w / 2}" y="{b.y + b.h / 2 + b.title_size * 0.34}" font-size="{b.title_size}" '
                       f'font-weight="600" fill="{t["ink"]}" text-anchor="middle">{esc(b.title)}</text>')
            continue
        out.append(f'<text x="{b.x + 24}" y="{ty}" font-size="{b.title_size}" font-weight="600" fill="{t["ink"]}">{esc(b.title)}</text>')
        ly = ty + 10
        for i, ln in enumerate(lines):
            ly += BODY + 12
            if not ln:
                continue
            fam = f' font-family="{esc(MONO)}"' if i in b.mono else ""
            out.append(f'<text x="{b.x + 24}" y="{ly}" font-size="{BODY}" fill="{t["muted"]}"{fam}>{esc(ln)}</text>')
    for a in fig.arrows:
        if not a.label:
            continue
        x, y = a.at if a.at else a.pts[0]
        rot = f' transform="rotate({a.rotate} {x} {y})"' if a.rotate else ""
        out.append(f'<text x="{x}" y="{y}" font-size="{LABEL}" font-weight="500" fill="{color(t, a.tone if a.tone != "line" else "ink")}" '
                   f'text-anchor="{a.anchor}" stroke="{t["bg"]}" stroke-width="10" stroke-linejoin="round" '
                   f'paint-order="stroke"{rot}>{esc(a.label)}</text>')
    for tx in fig.texts:
        rot = f' transform="rotate({tx.rotate} {tx.x} {tx.y})"' if tx.rotate else ""
        fam = f' font-family="{esc(MONO)}"' if tx.mono else ""
        out.append(f'<text x="{tx.x}" y="{tx.y}" font-size="{tx.size}" font-weight="{tx.weight}" '
                   f'fill="{color(t, tx.tone)}" text-anchor="{tx.anchor}" stroke="{t["bg"]}" stroke-width="8" '
                   f'stroke-linejoin="round" paint-order="stroke"{fam}{rot}>{esc(tx.text)}</text>')
    for m in fig.markers:
        out.append(f'<circle cx="{m.x}" cy="{m.y}" r="18" fill="{t["warn"]}"/>')
        out.append(f'<text x="{m.x}" y="{m.y + 9}" font-size="25" font-weight="700" fill="{t["bg"]}" '
                   f'text-anchor="middle">{esc(m.letter)}</text>')
    out.append("</svg>")
    return "\n".join(out) + "\n"


# ── checks ────────────────────────────────────────────────────────────────────────────────────────────

def tracked(path: str) -> bool:
    r = subprocess.run(["git", "-C", str(ROOT), "cat-file", "-e", f"HEAD:{path}"], capture_output=True)
    return r.returncode == 0


def problems() -> list[str]:
    found: list[str] = []
    for make in FIGURES:
        fig = make()
        if len(fig.boxes) > MAX_BOXES:
            found.append(f"{fig.id}: {len(fig.boxes)} boxes (at most {MAX_BOXES})")
        sizes = [b.title_size for b in fig.boxes] + [BODY, LABEL] + [tx.size for tx in fig.texts]
        if min(sizes) < MIN_FONT:
            found.append(f"{fig.id}: text at {min(sizes)} px (at least {MIN_FONT})")
        for b in fig.boxes:
            need = max([width(b.title, b.title_size, 600)] + [width(ln, BODY, mono=i in b.mono) for i, ln in enumerate(b.lines)])
            pad = 48 if b.lines else 16
            if need + pad > b.w:
                found.append(f"{fig.id}: box {b.id} is {b.w} px wide, its text needs about {need + pad:.0f}")
        for x, y in [p for a in fig.arrows for p in a.pts] + [(b.x + b.w, b.y + b.h) for b in fig.boxes]:
            if not (0 <= x <= W and 0 <= y <= H):
                found.append(f"{fig.id}: a point at ({x}, {y}) is off the {W}×{H} canvas")
        for src in fig.sources:
            if not tracked(src):
                found.append(f"{fig.id}: source {src} is not at HEAD")
        for theme in THEMES:
            path = HERE / name(fig, theme, "svg")
            if not path.exists():
                found.append(f"{path.name} missing: run make diagrams")
            elif path.read_text(encoding="utf-8") != svg(fig, theme):
                found.append(f"{path.name} is stale against build.py: run make diagrams")
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8")) if MANIFEST.exists() else {}
    for make in FIGURES:
        fig = make()
        for theme in THEMES:
            png = name(fig, theme, "png")
            digest = sha(svg(fig, theme))
            if not (HERE / png).exists():
                found.append(f"{png} missing: run make diagrams")
            elif manifest.get(png) != digest:
                found.append(f"{png} was not rendered from the current SVG: run make diagrams")
    return found


# ── output ────────────────────────────────────────────────────────────────────────────────────────────

def name(fig: Figure, theme: str, ext: str) -> str:
    return f"{fig.id}.{ext}" if theme == "light" else f"{fig.id}-{theme}.{ext}"


def sha(s: str) -> str:
    return hashlib.sha256(s.encode("utf-8")).hexdigest()


def available_gb() -> float:
    for ln in Path("/proc/meminfo").read_text().splitlines():
        if ln.startswith("MemAvailable:"):
            return int(ln.split()[1]) / 1024 / 1024
    return 0.0


def render(svg_path: Path, png_path: Path, profile: Path) -> None:
    """One headless Chrome, one screenshot; returns only after Chrome has exited."""
    while available_gb() < 3.0:
        print(f"  waiting: {available_gb():.1f} GB available, need 3", flush=True)
        time.sleep(30)
    cmd = [CHROME, "--headless", "--disable-gpu", "--no-sandbox", f"--user-data-dir={profile}", "--hide-scrollbars",
           "--force-device-scale-factor=1", f"--window-size={W},{H}", f"--screenshot={png_path}", svg_path.as_uri()]
    subprocess.run(cmd, check=True, timeout=120, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--png", action="store_true", help="also render PNGs with headless Chrome")
    p.add_argument("--check", action="store_true", help="check, write nothing")
    p.add_argument("--only", help="comma-separated figure ids (with --png: render only these)")
    p.add_argument("--themes", help="comma-separated themes to render (with --png; default: all)")
    p.add_argument("--chrome-profile", help="Chrome's --user-data-dir (default: a temporary folder, removed after)")
    args = p.parse_args()
    if args.check:
        found = problems()
        for line in found:
            print(f"diagrams: {line}")
        print("diagrams: ok" if not found else f"diagrams: {len(found)} problem(s)")
        return 1 if found else 0
    only = set(args.only.split(",")) if args.only else None
    themes = set(args.themes.split(",")) if args.themes else set(THEMES)
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8")) if MANIFEST.exists() else {}
    profile = Path(args.chrome_profile) if args.chrome_profile else Path(tempfile.mkdtemp(prefix="diagrams-chrome-"))
    try:
        for make in FIGURES:
            fig = make()
            for theme in THEMES:
                text = svg(fig, theme)
                svg_path = HERE / name(fig, theme, "svg")
                svg_path.write_text(text, encoding="utf-8")
                print(f"wrote {svg_path.relative_to(ROOT)} ({len(fig.boxes)} boxes)")
                if args.png and (only is None or fig.id in only) and theme in themes:
                    png_path = HERE / name(fig, theme, "png")
                    render(svg_path, png_path, profile)
                    manifest[png_path.name] = sha(text)
                    print(f"wrote {png_path.relative_to(ROOT)}")
        MANIFEST.write_text(json.dumps(dict(sorted(manifest.items())), indent=2) + "\n", encoding="utf-8")
    finally:
        if not args.chrome_profile:
            shutil.rmtree(profile, ignore_errors=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
