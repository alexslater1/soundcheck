# Web screenshots

Real renders, not mock-ups. Captured 2025-06-15 15:07–15:09 BST with headless Chromium (Playwright), 412 × 915 at 2× (a phone-sized window; `05` uses a 2000-pixel-tall window so the whole section fits), dark mode, from the code of `build/integration-preview` at `a9fae55` (venues + seed + stats merged): its jar and `expo export -p web`, on a throwaway PostGIS database, never the shared one.

Data is synthetic only: the Ticketmaster-shaped fixtures (`make ingest INGESTION_MODE=fixtures`) and invented acts and rooms added through `POST /gigs` for a made-up fan ("Maya"). No setlist is shown (setlist.fm's terms forbid keeping its data), and no real person's clips.

| File | Shows |
|---|---|
| `01-sign-in.png` | Sign in, the wordmark and the glow |
| `02-gigs-calendar.png` | Gigs tab: near home, genres, the calendar with gig counts, the month's gigs |
| `03-gig-act-bar-whole-night.png` | A three-act gig: the act bar on "Whole night" |
| `04-gig-act-bar-support.png` | The same gig with a support act picked: its billing, follow row and clips |
| `05-me-stats.png` | Me → YOUR STATS: totals, this year against last, BY YEAR, MOST SEEN, MOST VISITED |
| `06-stats-gigs-behind-a-line.png` | The gigs behind the most-seen act |
| `07-settings-privacy.png` | Settings → Privacy: who sees the stats, and the privacy notice |

Android screenshots are still to be taken on the phone.
