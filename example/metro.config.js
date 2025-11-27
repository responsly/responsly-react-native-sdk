const path = require('path');
const { getDefaultConfig, mergeConfig } = require('@react-native/metro-config');

const root = path.resolve(__dirname, '..');
const pkg = require('../package.json');

const defaultConfig = getDefaultConfig(__dirname);
const defaultBlockList = defaultConfig.resolver.blockList;

function escape(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * The example bundles @responsly/react-native-sdk from the parent folder (its TypeScript
 * sources, through the package's "source" export condition), with the example's own copies of
 * react and react-native: the parent's node_modules are hidden from Metro.
 *
 * @type {import('@react-native/metro-config').MetroConfig}
 */
const config = {
  watchFolders: [root],
  resolver: {
    blockList: [
      ...(Array.isArray(defaultBlockList)
        ? defaultBlockList
        : defaultBlockList
          ? [defaultBlockList]
          : []),
      new RegExp(`^${escape(path.join(root, 'node_modules'))}\\/.*$`),
    ],
    nodeModulesPaths: [path.join(__dirname, 'node_modules')],
    extraNodeModules: {
      [pkg.name]: root,
    },
    unstable_conditionNames: [
      'require',
      'react-native',
      'responsly-react-native-sdk-source',
    ],
  },
};

module.exports = mergeConfig(defaultConfig, config);
