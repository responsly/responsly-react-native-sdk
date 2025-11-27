const path = require('path');
const pkg = require('../package.json');

// The example uses the package straight from the parent folder instead of installing it.
module.exports = {
  dependencies: {
    [pkg.name]: {
      root: path.join(__dirname, '..'),
      platforms: {
        // Codegen needs the platforms to be listed; the defaults are found by autolinking.
        ios: {},
        android: {},
      },
    },
  },
};
