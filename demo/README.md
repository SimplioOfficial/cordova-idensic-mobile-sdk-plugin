# Demo App

## How to Run

1. Set up `Cordova` environment following [the instructions](https://cordova.apache.org/#getstarted)
2. In project checkout directory, run `npm install`
3. Run demo with either `cordova run ios` or `cordova run android` command

Note: In case you'd like to run from within an IDE, don't forget to sync with:
```bash
cordova prepare
```

In order to open in Xcode use `xed platforms/ios/` 


## How to Upgrade

1. Build the plugin (ensure that `../dist/SNSMobileSDK.js` is built from the `../www/SNSMobileSDK.js`)

```bash
cd ../ && webpack -p && cd demo/
```
In case `webpack` is not available, just install it with `yarn install` 

2. Then reinstall the plugin:

```bash
cordova plugin remove cordova-idensic-mobile-sdk-plugin --nosave && cordova plugin add ./cordova-idensic-mobile-sdk-plugin/ --nosave
```

## How to Distribute

### Prerequisites 

Install the [Cocoapods](https://cocoapods.org/) and [Fastlane](https://fastlane.tools/) if not yet
```sh
bundle install
```

### Prepare environment

Create `.env` file and put there the credentials as follows:
```text
APP_STORE_CONNECT_API_KEY_KEY_ID={key-id}
APP_STORE_CONNECT_API_KEY_ISSUER_ID={the-issuer-id}
APP_STORE_CONNECT_API_KEY_KEY_FILEPATH={path-to-p8-file}
```

### iOS app

The following command will set the build number according to the current number of commits, then build `./platforms/ios/build/SumSubCordova.ipa` file and upload it to TestFlight.
```sh
bundle exec fastlane ios beta
```
Pay attention please that all the changes made by the command in  `./platforms/ios` dir will be stashed right after the build is done.

In case you'd like to set a specific build number for some reasons, you can run as follows:
```sh
bundle exec fastlane ios beta force_build_number:{number}
```
