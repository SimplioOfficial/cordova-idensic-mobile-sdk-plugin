//const webpack = require('webpack');
const path = require('path');

const MAIN_FILE = './www/SNSMobileSDK.js';
const BUILD_PATH = '/dist/';
const FILE_NAME = 'SNSMobileSDK';
const LIBRARY_NAME = 'SNSMobileSDK';

module.exports = {
	entry: {
		wrapper: MAIN_FILE,
	},
	output: {
		path: path.join(__dirname, BUILD_PATH),
		filename: `${FILE_NAME}.js`,
		publicPath: '/',
		libraryTarget: 'umd',
		library: LIBRARY_NAME,
	},
	externals: {
		'cordova/exec': 'cordova/exec',
	},
	// production
	/*
    plugins: [
        new webpack.DefinePlugin({
            'process.env': {
                'NODE_ENV': JSON.stringify('production')
            }
        })
    ],
    */
	module: {
		rules: [
			{
				test: /\.(js)$/,
				exclude: /node_modules/,
				use: ['babel-loader', 'eslint-loader'],
			},
		],
	},
};
