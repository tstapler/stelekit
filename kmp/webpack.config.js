const path = require('path');
const webpack = require('webpack');

module.exports = {
    mode: 'development',
    devtool: 'source-map',
    entry: {
        main: './build/classes/kotlin/js/main/com/logseq/kmp/browser/Main.kt.js'
    },
    output: {
        path: path.resolve(__dirname, 'build/distributions'),
        filename: 'logseq-kmp.js',
        libraryTarget: 'umd'
    },
    resolve: {
        extensions: ['.js', '.json', '.ts'],
        alias: {
            '@': path.resolve(__dirname, 'src/jsMain')
        }
    },
    module: {
        rules: [
            {
                test: /\.js$/,
                exclude: /node_modules/,
                use: {
                    loader: 'babel-loader',
                    options: {
                        presets: ['@babel/preset-env']
                    }
                }
            }
        ]
    },
    plugins: [
        new webpack.DefinePlugin({
            'process.env.NODE_ENV': JSON.stringify('development')
        })
    ],
    devServer: {
        static: {
            directory: path.join(__dirname, 'build/distributions')
        },
        hot: true,
        port: 8080,
        open: true
    },
    optimization: {
        minimize: false
    }
};
