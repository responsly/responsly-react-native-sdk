const PLUGIN = '../../app.plugin.js';

describe('Expo config plugin (app.plugin.js)', () => {
  beforeEach(() => {
    jest.resetModules();
  });

  it('is a function that returns the config unchanged, with no options', () => {
    const plugin = require(PLUGIN);
    expect(typeof plugin).toBe('function');

    const config = {
      name: 'App',
      slug: 'app',
      plugins: ['@responsly/react-native-sdk'],
      ios: { bundleIdentifier: 'com.acme.app' },
      android: { package: 'com.acme.app' },
    };
    const snapshot = JSON.parse(JSON.stringify(config));

    expect(plugin(config)).toBe(config);
    expect(plugin(config, undefined)).toBe(config);
    expect(config).toEqual(snapshot);
  });

  it('registers itself with Expo by package name and version when Expo is installed', () => {
    const createRunOncePlugin = jest.fn(
      (plugin: (config: unknown) => unknown) => plugin
    );
    jest.doMock('expo/config-plugins', () => ({ createRunOncePlugin }), {
      virtual: true,
    });

    const plugin = require(PLUGIN);
    const pkg = require('../../package.json');

    expect(createRunOncePlugin).toHaveBeenCalledTimes(1);
    expect(createRunOncePlugin).toHaveBeenCalledWith(
      expect.any(Function),
      '@responsly/react-native-sdk',
      pkg.version
    );
    const config = { name: 'App', slug: 'app' };
    expect(plugin(config)).toBe(config);

    jest.dontMock('expo/config-plugins');
  });

  it('is published with the package', () => {
    const pkg = require('../../package.json');
    expect(pkg.files).toContain('app.plugin.js');
    expect(pkg.exports['./app.plugin.js']).toBe('./app.plugin.js');
    expect(pkg.exports['./package.json']).toBe('./package.json');
  });
});
