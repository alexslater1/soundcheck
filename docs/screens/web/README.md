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
| `08-headers-dark.png` | The headers, dark: sign-in (no header, the mic-check wordmark), sign-up (chevron only), Settings, Privacy at rest and scrolled 400 px (content stops at the header's edge), a gig, Following (chevron only), a post that doesn't exist (its error state) |
| `09-headers-light.png` | The same eight screens in light mode |

`08` and `09` were captured 2025-07-13 14:16–14:18 UTC from `fix/chrome` at `6b4b729`. They used that branch's jar (`bootJar`, fixtures ingested) and Expo's web dev server behind the edge, on its own throwaway database, with the same synthetic data. Each is eight 412 × 915 screens at 1×, side by side.

`10`–`15` were captured 2025-08-18 19:59 BST by `make screens` (`app/screens/web.spec.ts`), 412 × 915 at 2×, dark mode, from `main` at `9a6ab33` running on the local stack (`make dev`). Clips are synthetic only: two invented accounts (Maya posts `make clips` clips, Sam follows her), deleted at the end, their posts then hidden by `prune-test-posts.sh`. The gigs are real Ticketmaster listings near Manchester (attribution in the main README); the artists' photos are masked, because they are Ticketmaster's and stay out of the repo.

| File | Shows |
|---|---|
| `10-feed-following.png` | Following: a two-clip post full width (1/2, carousel dots, next arrow), the scrim under the author and caption, the actions |
| `11-gigs-near-home.png` | Gigs: near home, genres, the calendar with gig counts |
| `12-gigs-cards.png` | Further down: this month's gig cards with date, support acts, venue, time and distance (photos masked) |
| `13-profile-grid.png` | Someone else's profile: counts, Following, the grid (the "2" badge marks a post of two clips) |
| `14-create-progress.png` | Create: a picked clip uploading ("Uploading 0%"), its act and song fields |
| `15-me-processing.png` | Your own grid right after posting: the new post shows Processing… until the worker is done. The "2 Posts" count leaves it out until it is published |

The Android counterparts of `10`–`15` are in `docs/screens/android/` (2025-08-30, a Pixel).
