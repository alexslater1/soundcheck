// The logo candidates sheet: one card per mark, from the layers make_icons.py wrote.
//   python3 ops/scripts/make_icons.py --candidates /tmp/marks
//   node docs/screens/logo/sheet.mjs /tmp/marks docs/screens/logo
// Uses the app's Playwright and the machine's Chromium (CHROMIUM=/path/to/chrome to choose one).
import { mkdirSync, readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const APP = resolve(dirname(fileURLToPath(import.meta.url)), "../../../app");
const require = createRequire(join(APP, "package.json"));
const { chromium } = require("@playwright/test");

const [, , candDir, outDir] = process.argv;
mkdirSync(outDir, { recursive: true });
const font = (p) =>
  readFileSync(join(APP, "node_modules/@expo-google-fonts", p)).toString(
    "base64",
  );
const img = (p) =>
  `data:image/png;base64,${readFileSync(p).toString("base64")}`;

const candidates = [
  {
    id: "mic-check",
    letter: "A",
    name: "Mic check",
    idea: "A tick whose long arm is a microphone: “mic check”, the soundcheck ritual.",
  },
  {
    id: "ticket",
    letter: "B",
    name: "Ticket",
    idea: "A gig ticket with a play button punched through: the gig, and the clips from it.",
  },
  {
    id: "pin",
    letter: "C",
    name: "Pin",
    idea: "A map pin with three meter bars in its head: gigs near you, and their sound.",
  },
  {
    id: "letter-s",
    letter: "D",
    name: "S",
    idea: "One stroke of two arcs, leaning forward: a lettermark.",
  },
];
const current = {
  id: "current",
  letter: "–",
  name: "Current (five bars)",
  idea: "Today’s mark, for comparison.",
  files: {
    fg: join(APP, "assets/android-icon-foreground.png"),
    mono: join(APP, "assets/android-icon-monochrome.png"),
    splash: join(APP, "assets/splash-icon.png"),
    splashLight: join(APP, "assets/splash-icon.png"),
    mark: join(APP, "assets/splash-icon.png"),
    markLight: join(APP, "assets/splash-icon.png"),
    favicon: join(APP, "assets/favicon.png"),
  },
};
for (const c of candidates) {
  const d = join(candDir, c.id);
  c.files = {
    fg: join(d, "android-icon-foreground.png"),
    mono: join(d, "android-icon-monochrome.png"),
    splash: join(d, "splash-icon.png"),
    splashLight: join(d, "splash-icon-light.png"),
    mark: join(d, "mark.png"),
    markLight: join(d, "mark-light.png"),
    favicon: join(d, "favicon.png"),
  };
}

const css = `
@font-face { font-family: SG; font-weight: 700; src: url(data:font/ttf;base64,${font("space-grotesk/700Bold/SpaceGrotesk_700Bold.ttf")}); }
@font-face { font-family: SG; font-weight: 500; src: url(data:font/ttf;base64,${font("space-grotesk/500Medium/SpaceGrotesk_500Medium.ttf")}); }
@font-face { font-family: Inter; font-weight: 400; src: url(data:font/ttf;base64,${font("inter/400Regular/Inter_400Regular.ttf")}); }
* { box-sizing: border-box; margin: 0; }
body { background: #111114; color: #F4F4F5; font-family: Inter, sans-serif; padding: 24px; }
.row { display: inline-flex; gap: 20px; align-items: flex-start; }
.card { width: 420px; background: #18181B; border: 1px solid #27272A; border-radius: 20px; padding: 20px; display: flex; flex-direction: column; gap: 16px; }
h2 { font-family: SG; font-weight: 700; font-size: 26px; letter-spacing: -0.3px; }
h2 span { color: #A78BFA; margin-right: 8px; }
.idea { color: #A1A1AA; font-size: 14px; line-height: 20px; min-height: 40px; }
.label { color: #71717A; font-size: 11px; letter-spacing: 1px; text-transform: uppercase; font-family: SG; font-weight: 500; margin-bottom: 8px; }
.home { border-radius: 14px; padding: 18px 0 12px; display: flex; justify-content: space-around; background: linear-gradient(160deg, #3b2a6b, #0f3b4a 70%, #1c1c22); }
.app { display: flex; flex-direction: column; align-items: center; gap: 6px; font-size: 12px; color: #fff; }
.adaptive { width: 84px; height: 84px; background: #09090B; overflow: hidden; position: relative; }
.adaptive img { position: absolute; width: 150%; height: 150%; left: -25%; top: -25%; }
.circle { border-radius: 50%; }
.squircle { border-radius: 30%; }
.themed { background: #E8DEF8; }
.themed img { filter: brightness(0) saturate(100%) invert(15%) sepia(40%) saturate(2700%) hue-rotate(250deg); }
.small { display: flex; gap: 18px; align-items: center; }
.small .tile { background: #09090B; border-radius: 8px; padding: 8px; display: flex; gap: 14px; align-items: center; }
.fav48 { width: 48px; height: 48px; image-rendering: auto; }
.fav24 { width: 24px; height: 24px; }
.fav16 { width: 16px; height: 16px; }
.tab { background: #2a2a2e; border-radius: 8px 8px 0 0; padding: 6px 10px; display: flex; gap: 8px; align-items: center; font-size: 12px; color: #d4d4d8; }
.splashes { display: flex; gap: 14px; }
.phone { width: 120px; height: 230px; border-radius: 16px; display: flex; align-items: center; justify-content: center; border: 3px solid #3f3f46; }
.phone img { width: 47px; }
.words { display: flex; flex-direction: column; gap: 10px; }
.word { display: flex; align-items: center; gap: 10px; padding: 12px 14px; border-radius: 12px; font-family: SG; font-weight: 700; font-size: 26px; letter-spacing: -0.5px; }
.word img { width: 30px; height: 30px; }
`;

function card(c) {
  const f = Object.fromEntries(
    Object.entries(c.files).map(([k, v]) => [k, img(v)]),
  );
  return `<div class="card">
  <h2><span>${c.letter}</span>${c.name}</h2>
  <div class="idea">${c.idea}</div>
  <div><div class="label">Home screen (Android masks)</div>
    <div class="home">
      <div class="app"><div class="adaptive circle"><img src="${f.fg}"></div>Soundcheck</div>
      <div class="app"><div class="adaptive squircle"><img src="${f.fg}"></div>Soundcheck</div>
      <div class="app"><div class="adaptive circle themed"><img src="${f.mono}"></div>Themed</div>
    </div></div>
  <div><div class="label">Small: favicon 48 / 24 / 16 px, browser tab</div>
    <div class="small"><div class="tile"><img class="fav48" src="${f.favicon}"><img class="fav24" src="${f.favicon}"><img class="fav16" src="${f.favicon}"></div>
      <div class="tab"><img class="fav16" src="${f.favicon}">Soundcheck</div></div></div>
  <div><div class="label">Splash, dark and light</div>
    <div class="splashes"><div class="phone" style="background:#09090B"><img src="${f.splash}"></div>
      <div class="phone" style="background:#F7F6FB"><img src="${f.splashLight}"></div></div></div>
  <div><div class="label">Wordmark (sign-in)</div>
    <div class="words"><div class="word" style="background:#09090B;color:#F4F4F5"><img src="${f.mark}">Soundcheck</div>
      <div class="word" style="background:#F7F6FB;color:#18181B"><img src="${f.markLight}">Soundcheck</div></div></div>
</div>`;
}

const page = (cards) =>
  `<!doctype html><html><head><meta charset="utf-8"><style>${css}</style></head><body><div class="row">${cards}</div></body></html>`;

const browser = await chromium.launch(
  process.env.CHROMIUM ? { executablePath: process.env.CHROMIUM } : {},
);
const ctx = await browser.newContext({
  deviceScaleFactor: 2,
  viewport: { width: 2400, height: 1200 },
});
const p = await ctx.newPage();
async function shot(html, file) {
  await p.setContent(html);
  await p.evaluate(() => document.fonts.ready);
  const box = await p.locator(".row").boundingBox();
  await p.screenshot({
    path: file,
    clip: {
      x: 0,
      y: 0,
      width: box.x + box.width + 24,
      height: box.y + box.height + 24,
    },
  });
  console.log("wrote", file);
}
for (const c of candidates)
  await shot(
    page(card(c)),
    join(outDir, `${c.letter.toLowerCase()}-${c.id}.png`),
  );
await shot(
  page(candidates.map(card).join("") + card(current)),
  join(outDir, "all-candidates.png"),
);
await browser.close();
