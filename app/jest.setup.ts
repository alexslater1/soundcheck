// Shared Jest setup: RNTL matchers are built in since v12.4.
// Tests that need the network mock `global.fetch` themselves.
import { NativeModules } from 'react-native';

// react-native-compressor's native module does not exist under Jest, and the library reads it as soon as
// it is imported (its event emitters). An empty one lets it load. Nothing calls into it in tests.
NativeModules.Compressor = {};

// The app's telemetry queue (timers, POST /events) is replaced by a recorder in every test; its rules
// are tested on their own in src/features/telemetry/telemetryQueue.test.ts.
jest.mock('@/features/telemetry/telemetry', () => ({
  telemetry: {
    track: jest.fn(),
    flush: jest.fn(async () => undefined),
    clear: jest.fn(),
    size: () => 0,
  },
}));

// The font packages also export expo-font's useFonts, whose loader needs expo-asset (not available
// under Jest). Tests need only the family names: each font module is a number, as with any asset.
jest.mock('@expo-google-fonts/inter', () => ({
  Inter_400Regular: 1,
  Inter_500Medium: 2,
  Inter_600SemiBold: 3,
  Inter_700Bold: 4,
}));
jest.mock('@expo-google-fonts/space-grotesk', () => ({
  SpaceGrotesk_500Medium: 5,
  SpaceGrotesk_600SemiBold: 6,
  SpaceGrotesk_700Bold: 7,
}));

// expo-location's native module does not exist under Jest. By default the phone has never been asked
// (no position: distances stay from the home city); the location tests give their own answers.
jest.mock('expo-location', () => ({
  PermissionStatus: { GRANTED: 'granted', UNDETERMINED: 'undetermined', DENIED: 'denied' },
  Accuracy: { Lowest: 1, Low: 2, Balanced: 3, High: 4, Highest: 5, BestForNavigation: 6 },
  getForegroundPermissionsAsync: jest.fn(async () => ({
    granted: false,
    status: 'undetermined',
    canAskAgain: true,
    expires: 'never',
  })),
  requestForegroundPermissionsAsync: jest.fn(async () => ({
    granted: false,
    status: 'denied',
    canAskAgain: true,
    expires: 'never',
  })),
  getLastKnownPositionAsync: jest.fn(async () => null),
  getCurrentPositionAsync: jest.fn(() => new Promise(() => undefined)),
}));

// Chip can show an icon, so most screens load Ionicons, whose font loader needs expo-asset (not available
// under Jest). Tests that look at an icon's props mock it themselves, as before.
jest.mock('@expo/vector-icons/Ionicons', () => 'Ionicons');
