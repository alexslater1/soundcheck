import { existsSync } from 'node:fs';

import type { ConfigContext, ExpoConfig } from 'expo/config';

// Release builds must not allow cleartext HTTP; dev builds talk to http://localhost:8088.
// `make android` leaves APP_ENV unset (dev); the release build sets APP_ENV=production.
const isProduction = process.env.APP_ENV === 'production';
// The Firebase project's Android config (gitignored, see docs/SETUP.md). Without it the build
// still works, with no push notifications: Activity only.
const googleServicesFile = existsSync('./google-services.json')
  ? './google-services.json'
  : undefined;

export default ({ config }: ConfigContext): ExpoConfig => ({
  ...config,
  name: 'Soundcheck',
  slug: 'soundcheck',
  scheme: 'soundcheck',
  version: '0.1.0',
  orientation: 'portrait',
  icon: './assets/icon.png',
  userInterfaceStyle: 'automatic',
  android: {
    package: 'app.soundcheck',
    googleServicesFile,
    // Draw behind the system bars and the keyboard (opt-in on SDK 53): `Screen` makes the room itself.
    edgeToEdgeEnabled: true,
    // Distances need only the approximate location: without the precise permission, Android
    // offers "approximate" alone. expo-location's own manifest asks for both, so the precise one is removed.
    blockedPermissions: ['android.permission.ACCESS_FINE_LOCATION'],
    adaptiveIcon: {
      foregroundImage: './assets/android-icon-foreground.png',
      backgroundImage: './assets/android-icon-background.png',
      monochromeImage: './assets/android-icon-monochrome.png',
      backgroundColor: '#09090B', // the Electric theme's background, as in the icon layers (ops/scripts/make_icons.py)
    },
  },
  web: {
    bundler: 'metro',
    output: 'single',
    favicon: './assets/favicon.png',
  },
  plugins: [
    'expo-router',
    'expo-dev-client',
    'expo-secure-store',
    'expo-font',
    [
      // The mic-check mark (ops/scripts/make_icons.py → splash-icon*.png) on the Electric
      // theme's background, in that palette's colours, light or dark as the phone is; the root layout
      // hides it once the app is ready.
      'expo-splash-screen',
      {
        image: './assets/splash-icon-light.png',
        imageWidth: 160,
        resizeMode: 'contain',
        backgroundColor: '#F7F6FB',
        dark: { image: './assets/splash-icon.png', backgroundColor: '#09090B' },
      },
    ],
    'expo-video',
    [
      // Push notifications through FCM. The status-bar icon is the monochrome mark.
      'expo-notifications',
      { icon: './assets/android-icon-monochrome.png', color: '#7C3AED' },
    ],
    [
      'expo-image-picker',
      {
        cameraPermission: 'Soundcheck uses the camera to record clips at gigs.',
        microphonePermission: 'Soundcheck uses the microphone to record sound with your clips.',
      },
    ],
    'react-native-compressor',
    [
      // Distances to gigs from where the phone is, when the user allows it.
      'expo-location',
      {
        locationWhenInUsePermission:
          'Soundcheck uses your approximate location only to show how far gigs are from you.',
      },
    ],
    [
      'expo-build-properties',
      {
        android: {
          usesCleartextTraffic: !isProduction,
        },
      },
    ],
  ],
  experiments: {
    typedRoutes: true,
  },
});
