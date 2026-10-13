// Expo's preset, with `import.meta` polyfilled: zustand's ESM middleware reads `import.meta.env`,
// which the web bundle (a classic script) can't parse.
module.exports = function (api) {
  api.cache(true);
  return {
    presets: [['babel-preset-expo', { unstable_transformImportMeta: true }]],
  };
};
