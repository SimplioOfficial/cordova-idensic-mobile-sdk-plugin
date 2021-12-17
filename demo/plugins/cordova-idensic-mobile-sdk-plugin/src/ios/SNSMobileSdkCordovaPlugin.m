#import "SNSMobileSdkCordovaPlugin.h"
#import <Cordova/CDV.h>
#import <IdensicMobileSDK/IdensicMobileSDK.h>

@interface SNSMobileSdkCordovaPlugin ()
@property (nonatomic, copy) void(^tokenExpirationOnComplete)(NSString * _Nullable newAccessToken);
@property (nonatomic, copy) void(^actionResultHandlerOnComplete)(SNSActionResultHandlerReaction);
@property (nonatomic, weak) SNSMobileSDK *sdk;
@end

@implementation SNSMobileSdkCordovaPlugin

- (void)launchSNSMobileSDK:(CDVInvokedUrlCommand *)command {
    
    NSDictionary *params = command.arguments.firstObject;
    
    if (![params isKindOfClass:NSDictionary.class]) {
        return [self complete:command withInvalidParameters:@"No params detected"];
    }
    
    NSString *apiUrl = params[@"apiUrl"];
    NSString *flowName = params[@"flowName"];
    NSString *accessToken = params[@"accessToken"];
    NSString *locale = params[@"locale"];
    
    SNSMobileSDK *sdk;
    
    if (!flowName || flowName.length <= 0) {
        
        SNSEnvironment environment = (apiUrl && apiUrl.length > 0) ? apiUrl : SNSEnvironmentProduction;
    
        sdk = [SNSMobileSDK setupWithAccessToken:accessToken
                                     environment:environment];
        sdk.locale = locale;

    } else {
    
        sdk = [SNSMobileSDK setupWithBaseUrl:apiUrl
                                    flowName:flowName
                                 accessToken:accessToken
                                      locale:locale
                                supportEmail:params[@"supportEmail"]];
    }
    
    if (!sdk.isReady) {
        [self complete:command withSDK:sdk];
        return;
    }
    
    self.sdk = sdk;

    if ([params[@"debug"] boolValue]) {
        sdk.logLevel = SNSLogLevel_Debug;
    }
    
    __weak SNSMobileSdkCordovaPlugin *weakSelf = self;
    
    [sdk tokenExpirationHandler:^(void (^ _Nonnull onComplete)(NSString * _Nullable)) {
        
        weakSelf.tokenExpirationOnComplete = onComplete;
        
        [weakSelf.commandDelegate evalJs:@"SNSMobileSDK.getNewAccessToken();" scheduledOnRunLoop:NO];
    }];

    [sdk onDidDismiss:^(SNSMobileSDK * _Nonnull sdk) {
        
        [weakSelf complete:command withSDK:sdk];
    }];
    
    if (params[@"hasHandlers"][@"onStatusChanged"]) {

        [sdk setOnStatusDidChange:^(SNSMobileSDK * _Nonnull sdk, SNSMobileSDKStatus prevStatus) {
            
            [weakSelf sendEventWithName:@"onStatusChanged" body:@{
                @"newStatus": [sdk descriptionForStatus:sdk.status] ?: @"",
                @"prevStatus": [sdk descriptionForStatus:prevStatus] ?: @"",
            }];
        }];
    }
    
    if (params[@"hasHandlers"][@"onEvent"]) {
        
        [sdk onEvent:^(SNSMobileSDK * _Nonnull sdk, SNSEvent * _Nonnull event) {

            [weakSelf sendEventWithName:@"onEvent" body:@{
                @"eventType": [event descriptionForEventType:event.eventType] ?: @"",
                @"payload": event.payload ?: @{},
            }];
        }];
    }

    if (params[@"hasHandlers"][@"onActionResult"]) {

        [sdk actionResultHandler:^(SNSMobileSDK * _Nonnull sdk, SNSActionResult * _Nonnull result, void (^ _Nonnull onComplete)(SNSActionResultHandlerReaction)) {
            
            weakSelf.actionResultHandlerOnComplete = onComplete;

            [weakSelf sendEventWithName:@"onActionResult" body:@{
                @"actionId": result.actionId ?: @"",
                @"actionType": result.actionType ?: @"",
                @"answer": result.answer ?: @"",
                @"allowContinuing": @(result.allowContinuing),
            }];
        }];
    }

    [self applyCustomizationIfAny];

    [sdk presentFrom:self.viewController];
}

- (void)setNewAccessToken:(CDVInvokedUrlCommand*)command {
    
    NSString *newAccessToken = command.arguments.firstObject;
    
//    NSLog(@"got new token: %@", newAccessToken);
    
    dispatch_async(dispatch_get_main_queue(), ^{
        if (self.tokenExpirationOnComplete) {
            self.tokenExpirationOnComplete(newAccessToken);
            self.tokenExpirationOnComplete = nil;
        }
    });
}

- (void)onActionResultCompleted:(CDVInvokedUrlCommand*)command {

    NSDictionary *params = command.arguments.firstObject;

    SNSActionResultHandlerReaction reaction = SNSActionResultHandlerReaction_Continue;
    if ([params[@"result"] isEqualToString:@"cancel"]) {
        reaction = SNSActionResultHandlerReaction_Cancel;
    }

    dispatch_async(dispatch_get_main_queue(), ^{
        if (self.actionResultHandlerOnComplete) {
            self.actionResultHandlerOnComplete(reaction);
            self.actionResultHandlerOnComplete = nil;
        }
    });
}

- (void)dismiss:(CDVInvokedUrlCommand*)command {

    [self.sdk dismiss];
}

#pragma mark - Customization

/**
 * Usage:
 *
 * Add a class named `IdensicMobileSDKCustomization` into the main project
 * and define a static method named `apply:` that will take an instance of `SNSMobileSDK`
 *
 * For example, in Swift:
 *
 * import IdensicMobileSDK
 *
 * class IdensicMobileSDKCustomization: NSObject {
 *
 *   @objc static func apply(_ sdk: SNSMobileSDK) {
 *   }
 * }
 *
 */
- (void)applyCustomizationIfAny {
    
    NSString *className = @"IdensicMobileSDKCustomization";
    
    Class customization = [NSBundle.mainBundle classNamed:className];
    if (!customization) {
        NSString *classPrefix = [NSBundle.mainBundle objectForInfoDictionaryKey:(__bridge NSString *)kCFBundleExecutableKey];
        if (classPrefix) {
            customization = [NSBundle.mainBundle classNamed:[NSString stringWithFormat:@"%@.%@", classPrefix, className]];
        }
    }
    
    if (customization && [customization respondsToSelector:@selector(apply:)]) {
        [customization performSelector:@selector(apply:) withObject:self.sdk];
    }
}

#pragma mark - Helpers

- (NSString *)stringFromJSON:(NSDictionary *)dict {
    
    NSError *error;
    NSData *jsonData = [NSJSONSerialization dataWithJSONObject:dict
                                                       options:0
                                                         error:&error];
    
    if (!jsonData) {
        return @"{}";
    } else {
        return [NSString.alloc initWithData:jsonData encoding:NSUTF8StringEncoding];
    }
}

- (void)sendEventWithName:(NSString *)name body:(NSDictionary *)body {

    NSString *jsonString = body ? [self stringFromJSON:body] : @"{}"; 

    NSString *command = [NSString stringWithFormat:@"SNSMobileSDK.sendEvent('%@', %@);", name, jsonString];

    if (NSThread.isMainThread) {
        [self.commandDelegate evalJs:command scheduledOnRunLoop:NO];
    } else{
        dispatch_async(dispatch_get_main_queue(), ^{
            [self.commandDelegate evalJs:command scheduledOnRunLoop:NO];
        });
    }
}

- (void)complete:(CDVInvokedUrlCommand *)command withSDK:(SNSMobileSDK *)sdk {

    NSMutableDictionary *result = NSMutableDictionary.new;
    
    result[@"success"] = @(sdk.status != SNSMobileSDKStatus_Failed);
    result[@"status"] = [sdk descriptionForStatus:sdk.status];
    
    if (sdk.status == SNSMobileSDKStatus_Failed) {
        result[@"errorType"] = [sdk descriptionForFailReason:sdk.failReason];
        result[@"errorMsg"] = sdk.verboseStatus;
    }
    
    if (sdk.status == SNSMobileSDKStatus_ActionCompleted && sdk.actionResult) {
        NSMutableDictionary *actionResult = NSMutableDictionary.new;
        
        actionResult[@"actionId"] = sdk.actionResult.actionId;
        actionResult[@"actionType"] = sdk.actionResult.actionType;
        actionResult[@"answer"] = sdk.actionResult.answer;
        actionResult[@"allowContinuing"] = @(sdk.actionResult.allowContinuing);

        result[@"actionResult"] = actionResult.copy;
    }

    [self complete:command withResult:result.copy];
}

- (void)complete:(CDVInvokedUrlCommand *)command withInvalidParameters:(NSString *)message {

    NSDictionary *result = @{
        @"success": @NO,
        @"status": @"Failed",
        @"errorType": @"InvalidParameters",
        @"errorMsg": message ?: @"",
    };
    
    [self complete:command withResult:result];
}

- (void)complete:(CDVInvokedUrlCommand *)command withResult:(NSDictionary *)result {
    
    CDVCommandStatus commandStatus = CDVCommandStatus_OK; //[result[@"success"] boolValue] ? CDVCommandStatus_OK : CDVCommandStatus_ERROR;
    
    CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:commandStatus
                                                  messageAsDictionary:result];
    
    [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
}

@end
