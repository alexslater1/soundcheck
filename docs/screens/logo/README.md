# Logo candidates

Four original marks for the new app logo, each shown the way it would ship: on the Android home screen (circle and squircle masks, and the themed monochrome icon), as the favicon at 48, 24 and 16 px, on the dark and light splash, and beside the name as on the sign-in page. `all-candidates.png` puts them side by side with the current five-bar mark.

| | Mark | Idea |
|---|---|---|
| A | Mic check (`a-mic-check.png`) | A tick whose long arm is a microphone: "mic check", the soundcheck ritual |
| B | Ticket (`b-ticket.png`) | A gig ticket with a play button punched through: the gig, and the clips from it |
| C | Pin (`c-pin.png`) | A map pin with three meter bars in its head: gigs near you, and their sound |
| D | S (`d-letter-s.png`) | One stroke of two arcs, leaning forward: a lettermark |

Every pixel of the marks comes from `ops/scripts/make_icons.py` (plain Python, signed distances; no artwork, fonts or images from anywhere else). The cards are an HTML page screenshotted with Chromium, using the app's Space Grotesk and Inter:

```bash
python3 ops/scripts/make_icons.py --candidates /tmp/marks
CHROMIUM=/opt/pw-browsers/chromium node docs/screens/logo/sheet.mjs /tmp/marks docs/screens/logo
```
