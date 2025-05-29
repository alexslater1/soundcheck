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

export {};
