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
