const path = require('path');
const CopyWebpackPlugin = require('copy-webpack-plugin');

config.resolve = {
    fallback: {
        fs: false,
        path: false,
        crypto: false,
    }
};

config.plugins.push(
    new CopyWebpackPlugin({
        patterns: [
            '../../node_modules/sql.js/dist/sql-wasm.wasm'
        ]
    })
);