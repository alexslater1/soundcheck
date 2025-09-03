# Android screenshots

Real screens from the phone, not mock-ups: the counterparts of `docs/screens/web/10`–`15`, screen for screen. Captured 2025-08-30 19:56–19:58 BST (`14` and `15` at 19:53–19:55) on a Google Pixel (1080 × 2424, Android 16, dark mode) over USB with `adb`: driven with `adb shell input`, elements found with `uiautomator dump`, saved with `adb exec-out screencap -p`. `14` is a frame from `adb shell screenrecord` (1080 × 2424), because the upload passed through "Uploading" faster than a screencap could catch it. The app was the Android dev build (installed 2025-08-27) running the JS of `main` at `eff0cd4` from Metro, against the local stack (`make dev`). Expo's floating dev-tools button was switched off in the dev menu first, so it is not in the shots.

Data is synthetic only. Two invented accounts with @example.com addresses: "Maya Lane" (signed up on the phone) and "Sam Okoro" (through the API), both deleted afterwards, their posts then hidden by `prune-test-posts.sh`. Every clip is a `make clips` test pattern (`clip-01`, `-02`, `-05`, `-08`, `-09`, `-20`). The gigs are real Ticketmaster listings near Manchester (attribution in the main README). The artists' photos on the gig cards were blacked out with Pillow before saving (plain `#3B2A63` rectangles over each image's bounds from the UI dump), because they are Ticketmaster's and stay out of the repo. The app's location permission was revoked for these shots, so distances are from Manchester's centre (the home city), as on the web, not from the phone.

| File | Shows |
|---|---|
| `10-feed-following.png` | Following (as Sam, who follows Maya): a two-clip post full width (1/2, carousel dots), the scrim under the author and caption, the actions, the sound button |
| `11-gigs-near-home.png` | Gigs: within 25 km of Manchester, "Distances from Manchester's centre · Use my location", genres, the calendar with gig counts (the first card's photo masked) |
| `12-gigs-cards.png` | Further down: this month's gig cards with date, support acts, venue, time and distance (photos masked) |
| `13-profile-grid.png` | Someone else's profile (Maya, seen by Sam): counts, Following, the grid (the "2" badge marks a post of two clips) |
| `14-create-progress.png` | Create: a gig picked, a clip picked from the gallery uploading ("Uploading 72%"), its act and song fields |
| `15-me-processing.png` | Your own grid right after posting: the new post shows Processing… until the worker is done; "4 Posts" leaves it out until it is published |

`15`'s post was created through the API straight after its upload completed: a clip picked in the app finished transcoding while the caption was typed, so its post was published at once and never showed the badge.

`16`, `18` and `feed.gif` were captured 2025-09-03 19:05–19:11 BST on the same phone with the Android **dev build** (installed 18:56, JS from a checkout of `main` at `08c2d67` through Metro), with two invented accounts (deleted afterwards). The location permission was revoked and Expo's Tools button switched off for the captures, then restored. The gig with a badge is real (Ticketmaster's status), as in web `16`; photos masked the same way.

| File | Shows |
|---|---|
| `16-gigs-status-badge.png` | Sat 10 Oct 2026 picked in the calendar: the day's gigs, one CANCELLED (Rockin'1000, AO Arena) |
| `18-offline-banner.png` | Airplane mode switched on for a few seconds (`cmd connectivity airplane-mode enable`): "No connection. You are seeing what was already loaded." It went again when the mode was switched off |
| `feed.gif` | 13 s of the Following feed (as the second account): a post of two synthetic clips, swipe to the second (it has no audio track, so "No sound"), up to the next post, like. `adb shell screenrecord --size 540x1212`, then ffmpeg: 10 fps, 300 px wide, a 96-colour palette (710 KB) |

There is no Android `17`: web `17` shows keyboard focus rings, which a touch screen does not have.

