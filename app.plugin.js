/**
 * Expo config plugin of @responsly/react-native-sdk. It takes no options:
 *
 *   { "expo": { "plugins": ["@responsly/react-native-sdk"] } }
 *
 * The package needs no changes to the native projects that `expo prebuild` generates: the
 * Android manifest entries of the SDK are merged from the library by Gradle, the iOS privacy
 * manifest ships inside the pod, and autolinking does the rest. So the plugin returns the
 * config as it is. It exists so that the package can be listed under `plugins`, and so that a
 * later version can adjust the native projects without another setup step.
 *
 * The SDK contains native code: use a development build, not Expo Go.
 */
const pkg = require('./package.json');

function withResponsly(config) {
  return config;
}

let plugin = withResponsly;
try {
  // Records the plugin in the config's plugin history, so it is applied once. Optional: the
  // helper comes from the app's own Expo installation.
  const { createRunOncePlugin } = require('expo/config-plugins');
  plugin = createRunOncePlugin(withResponsly, pkg.name, pkg.version);
} catch {
  // Not resolvable from here (for example in a monorepo): the plain function is a valid plugin.
}

module.exports = plugin;
