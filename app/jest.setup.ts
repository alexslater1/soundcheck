// Shared Jest setup: RNTL matchers are built in since v12.4.
// Tests that need the network mock `global.fetch` themselves.
import { NativeModules } from 'react-native';

// react-native-compressor's native module does not exist under Jest, and the library reads it as soon as
// it is imported (its event emitters). An empty one lets it load. Nothing calls into it in tests.
NativeModules.Compressor = {};

export {};
