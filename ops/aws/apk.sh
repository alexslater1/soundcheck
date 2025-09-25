#!/usr/bin/env bash
# make apk TARGET=aws: the release APK for the AWS address, HTTPS only (APP_ENV=production turns off
# cleartext), copied to app/build/soundcheck-aws.apk. Then android/ goes back to the dev configuration,
# so make android still reaches http://localhost:8088. Plain make apk is unchanged.
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"

url=$(out SoundcheckEdge PublicBaseUrl)
apk="$ROOT/app/android/app/build/outputs/apk/release/app-release.apk"
target="$ROOT/app/build/soundcheck-aws.apk"
say "APK for $url (APP_ENV=production: HTTPS only)"

restore_dev_config() {
  say "putting android/ back to the dev configuration (cleartext to localhost allowed)"
  (cd "$ROOT/app" && run env -u APP_ENV npx expo prebuild --platform android --no-install)
}
trap restore_dev_config EXIT

run make -C "$ROOT" apk TARGET= APK_API_URL="$url" APK_APP_ENV=production
dry && exit 0
mkdir -p "$(dirname "$target")"
cp "$apk" "$target"
ls -lh "$target"
say "install: adb install -r $target   (replaces the dev build; make android puts it back)"
