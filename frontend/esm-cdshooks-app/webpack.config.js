const path = require('path');

/**
 * Minimal webpack config for an OpenMRS 3.x ESM. Real OpenMRS ESM packages
 * use rspack (configured via @openmrs/esm-framework's shared toolchain). This
 * webpack config is a direct port for environments without rspack, sufficient
 * for `webpack --mode=production` to produce a SystemJS-loadable bundle that
 * the SPA assembler can pick up.
 */
module.exports = (env, argv) => ({
  entry: path.resolve(__dirname, 'src/index.ts'),
  output: {
    path: path.resolve(__dirname, 'dist'),
    filename: 'openmrs-esm-cdshooks-app.js',
    libraryTarget: 'system',
    publicPath: 'auto',
  },
  resolve: {
    extensions: ['.ts', '.tsx', '.js', '.json'],
  },
  module: {
    rules: [
      {
        test: /\.(ts|tsx)$/,
        exclude: /node_modules/,
        use: [
          {
            loader: 'ts-loader',
            options: {
              transpileOnly: true,
              compilerOptions: {
                module: 'esnext',
                target: 'es2022',
                jsx: 'react',
                esModuleInterop: true,
                allowSyntheticDefaultImports: true,
              },
            },
          },
        ],
      },
      {
        test: /\.json$/,
        type: 'json',
      },
    ],
  },
  // Everything OpenMRS ships at runtime stays external — the SPA assembler
  // injects shared instances via import-map.
  externals: [
    'react',
    'react-dom',
    'react-i18next',
    'single-spa',
    'single-spa-react',
    'swr',
    /^@openmrs\//,
    /^@carbon\//,
    'lodash-es',
    'dayjs',
    'react-router-dom',
  ],
  devtool: argv.mode === 'production' ? 'source-map' : 'inline-source-map',
});
