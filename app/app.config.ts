import type { ConfigContext, ExpoConfig } from 'expo/config';

// Release builds must not allow cleartext HTTP; dev builds talk to http://localhost:8088.
// `make android` leaves APP_ENV unset (dev); the release build sets APP_ENV=production.
const isProduction = process.env.APP_ENV === 'production';

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
    adaptiveIcon: {
      foregroundImage: './assets/android-icon-foreground.png',
      backgroundImage: './assets/android-icon-background.png',
      monochromeImage: './assets/android-icon-monochrome.png',
      backgroundColor: '#0B0B10',
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
    'expo-video',
    [
      'expo-image-picker',
      {
        cameraPermission: 'Soundcheck uses the camera to record clips at gigs.',
        microphonePermission: 'Soundcheck uses the microphone to record sound with your clips.',
      },
    ],
    'react-native-compressor',
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
