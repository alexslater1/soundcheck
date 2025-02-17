// ESLint flat config: Expo's rules + Prettier compatibility; no `any`.
// eslint-config-expo 8 (SDK 52) is an eslintrc config, so it is loaded through FlatCompat.
/* global __dirname */
const path = require('node:path');
const { FlatCompat } = require('@eslint/eslintrc');
const prettierConfig = require('eslint-config-prettier');

// Its plugins are its own dependencies, resolved from its folder.
const compat = new FlatCompat({
  baseDirectory: __dirname,
  resolvePluginsRelativeTo: path.dirname(require.resolve('eslint-config-expo/package.json')),
});

module.exports = [
  ...compat.extends('expo'),
  prettierConfig,
  {
    ignores: ['dist/*', 'android/*', 'ios/*', '.expo/*', 'src/api/generated/*', 'coverage/*'],
  },
  {
    files: ['**/*.ts', '**/*.tsx'],
    rules: {
      '@typescript-eslint/no-explicit-any': 'error',
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],
    },
  },
];
