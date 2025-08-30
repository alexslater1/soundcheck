# Setup — accounts, keys and toolchain

Do Part A (accounts) on day 1: some keys take time to arrive, and AWS setup is easier before you need it. Part B is the Ubuntu toolchain. Part C is the Android device. Store every key in `.env` (copied from `.env.example`), never in Git.

## Part A — Accounts and keys

| # | Service | Needed by | Steps | Env var |
|---|---|---|---|---|
| 1 | **GitHub repo** `soundcheck` | CI | Create a public repo (free Actions minutes + CodeQL). Enable Dependabot alerts. | — |
| 2 | **Ticketmaster Developer** | Ingestion | developer.ticketmaster.com → create account → "My Apps" → copy the Consumer Key. Read the terms (attribution, linking). | `TICKETMASTER_API_KEY` |
| 3 | **Last.fm API** | Ingestion | last.fm/api/account/create → API key (secret not needed). | `LASTFM_API_KEY` |
| 4 | **MusicBrainz** | Ingestion | No key. Set a descriptive User-Agent with contact email. | `MUSICBRAINZ_USER_AGENT="Soundcheck/0.1 (<your-contact-email>)"` |
| 5 | **Skiddle** (optional) | Ingestion | Apply for a free key via the link in github.com/Skiddle/web-api. If it arrives, set the key and `INGESTION_SKIDDLE_ENABLED=true`. | `SKIDDLE_API_KEY` |
| 6 | **AWS** | AWS deploy (create early) | See below. | via SSO profile |
| 7 | **Expo account** (optional) | EAS builds | Only needed for EAS cloud builds; local builds don't need it. | — |
| 8 | **Grafana Cloud** (optional) | AWS telemetry | Free stack → OTLP endpoint + token, if you want AWS telemetry in Grafana. | `OTEL_EXPORTER_OTLP_*` |
| 9 | **Firebase** (push notifications) | Push | See "Firebase" below. Free; no billing account. | `PUSH_ENABLED`, `PUSH_CREDENTIALS_FILE` |

### Firebase (push notifications; ~5 min)
1. console.firebase.google.com → **Create a project** (any name, e.g. `soundcheck`; Google Analytics off).
2. Project overview → **Add app → Android** → package name `app.soundcheck` → Register → **download `google-services.json`** → save it as `app/google-services.json` (gitignored). Skip the SDK steps: Expo does them.
3. Project settings (gear) → **Service accounts** → **Generate new private key** → save it as `.secrets/fcm-service-account.json` (gitignored; it can send to every user's phone, so treat it like a password).
4. In `.env`: `PUSH_ENABLED=true` (the key path defaults to the file above). Restart `make dev`.
5. Rebuild the app once: `make android` (dev build) and `make apk` (release); both pick up `google-services.json`. Cloud Messaging (the HTTP v1 API) is on by default for new projects.

### AWS account (do this early; takes ~30 min)
1. Create the account at aws.amazon.com. When asked, choose the **Paid plan** — new accounts get sign-up credits on either plan, but the Free plan restricts some services and closes the account after 6 months. Credits still apply to the Paid plan.
2. Sign in as root → enable **MFA** on root → never use root again.
3. **IAM Identity Center** (region eu-west-2): enable → create user `alex` → permission set `AdministratorAccess` → assign to the account. Enable MFA for this user.
4. **Billing → Budgets**: create a monthly cost budget of $50 with alerts at 20 % ($10), 50 % ($25), 100 % actual and 100 % forecast to your email. Enable **Cost Anomaly Detection**. In Billing preferences, enable free-tier/credit usage alerts.
5. Install AWS CLI v2 (Part B), then `aws configure sso` (start URL from Identity Center, region `eu-west-2`, profile name `soundcheck`). Test: `aws sts get-caller-identity --profile soundcheck`.
   Sign in with `aws sso login --profile soundcheck` (it opens the browser); run it again when the credentials have run out.
6. Later: run `npx cdk bootstrap aws://<ACCOUNT_ID>/eu-west-2 --profile soundcheck` and create the GitHub OIDC deploy role.

## Part B — Ubuntu toolchain

```bash
# Basics
sudo apt update && sudo apt install -y build-essential curl git unzip zip jq make ffmpeg

# Docker Engine + Compose plugin (official repo)
# https://docs.docker.com/engine/install/ubuntu/  (remove Ubuntu's docker.io/containerd/runc first)  then:
sudo usermod -aG docker $USER    # log out and back in; newgrp only affects the current shell
docker run --rm hello-world
# If docker.service then fails with "no sockets found via socket activation" (seen after replacing docker.io):
#   sudo systemctl daemon-reload && sudo systemctl restart docker.socket docker.service

# Java 21 via SDKMAN
curl -s "https://get.sdkman.io" | bash && source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk install java 21.0.7-tem      # or the latest 21.x Temurin listed by `sdk list java`

# Node 22 LTS via fnm
curl -fsSL https://fnm.vercel.app/install | bash && source ~/.bashrc
fnm install 22 && fnm default 22

# Python + uv
curl -LsSf https://astral.sh/uv/install.sh | sh
uv python install 3.13

# AWS CLI v2 (AWS's install script: per-user install to ~/.local, verifies the signature, no sudo)
curl -fsSL https://awscli.amazonaws.com/v2/install.sh | bash

# k6
# https://grafana.com/docs/k6/latest/set-up/install-k6/ (apt repo for Debian/Ubuntu)

# GitHub CLI
# https://github.com/cli/cli/blob/trunk/docs/install_linux.md
```

**Android Studio** (for the SDK, platform-tools and an emulator): download from developer.android.com/studio, run `studio.sh`, install SDK Platform (latest), Build-Tools, Platform-Tools, an emulator image (x86_64) — the emulator is used for throttled-network measurements. Prefer an API 35 or 36 image with 2 GB RAM. Add to `~/.bashrc`:
```bash
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator
```
KVM acceleration: `sudo apt install -y qemu-system-x86 cpu-checker && sudo usermod -aG kvm $USER` (log out/in; on Ubuntu 24.04 `qemu-kvm` is only a virtual package). Check with `kvm-ok` and `emulator -accel-check`.

USB debugging (Part C) also needs udev rules and the `plugdev` group: `sudo apt install -y android-sdk-platform-tools-common && sudo usermod -aG plugdev $USER` (log out/in). Without them `adb devices` shows the phone as "no permissions".

Check everything: `make setup` (prints versions and missing tools).

## Part C — Android phone
1. Settings → About phone → tap **Build number** 7× → Settings → System → Developer options (Pixel path) → enable **USB debugging**.
2. Connect with a USB-C data cable, unlock the phone, accept the RSA prompt ("Always allow from this computer"); `adb devices` shows the phone as `device`.
3. `make android` runs `make phone` (`adb reverse` for 8088, 8081 and 4566, so the phone's `localhost:8088` reaches the local edge), then builds and installs the dev build and stays running as the bundler. Rebuild only after a native change (a new native module, `app.config.ts` plugins); otherwise the installed app just needs `make app` running and, after replugging the cable, `make phone` again (the forwards are cleared when the phone disconnects).
4. Optional: wireless debugging (`adb pair` / `adb connect`) for using the phone without a cable.

## Part D — First run
```bash
git clone git@github.com:alexslater1/soundcheck.git && cd soundcheck
cp .env.example .env            # fill in keys from Part A
make setup && make keys
make up                         # db, localstack, lgtm, edge
make api                        # terminal 1
make worker                     # terminal 2
make app                        # terminal 3 (Expo dev server; press w for web)
make android                    # phone (first time, or after native changes; else make phone)
make seed-demo                  # demo data (personas, gigs, clips)
make status                     # what is running

# stopping: Ctrl+C in each terminal, then
make down                       # containers + Gradle/Kotlin build daemons; ends with "Nothing left running."
```
Grafana: http://localhost:3000 · Edge: http://localhost:8088 · API direct: http://localhost:8080 · Expo web: http://localhost:8081
