package com.sumsub.msdk.plugins.cordova;

import android.content.Intent;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.ValueCallback;

import com.google.gson.Gson;
import com.sumsub.sns.R;
import com.sumsub.sns.core.SNSActionResult;
import com.sumsub.sns.core.SNSMobileSDK;
import com.sumsub.sns.core.data.listener.SNSActionResultHandler;
import com.sumsub.sns.core.data.listener.SNSCompleteHandler;
import com.sumsub.sns.core.data.listener.SNSErrorHandler;
import com.sumsub.sns.core.data.listener.SNSEvent;
import com.sumsub.sns.core.data.listener.SNSEventHandler;
import com.sumsub.sns.core.data.listener.SNSStateChangedHandler;
import com.sumsub.sns.core.data.listener.TokenExpirationHandler;
import com.sumsub.sns.core.data.model.SNSCompletionResult;
import com.sumsub.sns.core.data.model.SNSException;
import com.sumsub.sns.core.data.model.SNSSDKState;
import com.sumsub.sns.core.data.model.SNSSupportItem;
import com.sumsub.sns.prooface.SNSProoface;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import androidx.annotation.Nullable;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function2;
import timber.log.Timber;

public class SNSMobileSdkCordovaPlugin extends CordovaPlugin {
    private static final String LAUNCH_ACTION = "launchSNSMobileSDK";
    private static final String NEW_TOKEN_ACTION = "setNewAccessToken";
    private static final String ACTION_COMPLETED_ACTION = "onActionResultCompleted";
    private static final String DISMISS_ACTION = "dismiss";

    private static volatile String newAccessToken = null;
    private static SNSMobileSDK.SDK snsSdk;
    private volatile static SNSActionResult actionResultHandlerComplete;

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        Timber.d("execute: " + action + " args: " + args);
        if (action.equals(LAUNCH_ACTION)) {
            if (args.isNull(0)) {
                callbackContext.error("Error: SDK Config object must be provided");
                return false;
            }

            JSONObject conf = args.getJSONObject(0);
            String apiUrl = conf.optString("apiUrl");
            String flowName = conf.optString("flowName");
            String accessToken = conf.optString("accessToken");
            String supportEmail = conf.optString("supportEmail");
            String locale = conf.optString("locale");
            boolean isDebug = conf.optBoolean("debug", false);
            JSONObject hasHandlers = conf.getJSONObject("hasHandlers");

            if (TextUtils.isEmpty(supportEmail)) {
                supportEmail = "support@sumsub.com";
            }

            if (TextUtils.isEmpty(accessToken)) {
                callbackContext.error("Error: Access token must be provided");
                return false;
            }
            if (TextUtils.isEmpty(locale)) {
                locale = Locale.getDefault().getLanguage();
            }
            this.launchSNSMobileSDK(apiUrl, flowName, accessToken, supportEmail, locale, isDebug, hasHandlers, callbackContext);
            return true;
        } else if (action.equals(NEW_TOKEN_ACTION)) {
            newAccessToken = args.getString(0);
            return true;
        } else if (action.equals(DISMISS_ACTION)) {
            cordova.getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    snsSdk.dismiss();
                }
            });
            return true;
        } else if (ACTION_COMPLETED_ACTION.equalsIgnoreCase(action)) {
            String result = args.getJSONObject(0).getString("result");
            actionResultHandlerComplete = "cancel".equalsIgnoreCase(result) ? SNSActionResult.Cancel : SNSActionResult.Continue;
            return true;
        } else {
            callbackContext.error("Method not implemented");
            return false;
        }
    }

    private void requestNewAccessToken() {
        cordova.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                webView.getEngine().evaluateJavascript("window.SNSMobileSDK.getNewAccessToken()", new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String s) {
                        // no op
                    }
                });
            }
        });
    }

    private void requestActionResult(String actionId, String answer, String actionType, Boolean allowContinuing) {
        cordova.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {

                final String func = "window.SNSMobileSDK.sendEvent('onActionResult', { actionId: '" + actionId + "', answer: '" + answer + "', actionType: '" + actionType + ", allowContinuing: '" + allowContinuing + "})";

                webView.getEngine().evaluateJavascript(func, new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String s) {
                        // no op
                    }
                });
            }
        });
    }

    private void launchSNSMobileSDK(final String apiUrl, final String flowName, final String accessToken, String supportEmail, final String locale, final boolean isDebug, final JSONObject hasHandlers, CallbackContext callbackContext) {
        final SNSSupportItem supportItem = new SNSSupportItem(
                R.string.sns_support_EMAIL_title,
                R.string.sns_support_EMAIL_description,
                R.drawable.sns_ic_email,
                SNSSupportItem.Type.Email,
                supportEmail, null);


        cordova.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {

                try {

                    final SNSActionResultHandler actionResultHandler = hasHandlers.optBoolean("onActionResult") ?
                            (actionId, actionType, answer, allowContinuing) -> {
                                Timber.d("Calling onActionResult(" + actionId + ", " + answer + ")");
                                actionResultHandlerComplete = null;
                                requestActionResult(actionId, answer, actionType, allowContinuing);
                                int cnt = 0;
                                while (actionResultHandlerComplete == null) {
                                    try {
                                        Thread.sleep(100);
                                    } catch (InterruptedException e) {
                                        //no op
                                    }
                                    if (++cnt > 100) {
                                        return SNSActionResult.Continue;
                                    }
                                }
                                Timber.d("SumSub: Received: " + actionResultHandlerComplete + ' ' + Thread.currentThread().getName());
                                return actionResultHandlerComplete;
                            } : null;

                    final SNSErrorHandler errorHandler = e -> Timber.d(Log.getStackTraceString(e));

                    final SNSStateChangedHandler stateChangedHandler = (oldState, newState) -> {
                        final String newStatus = newState.getClass().getSimpleName();
                        final String prevStatus = oldState.getClass().getSimpleName();
                        final String func = "window.SNSMobileSDK.sendEvent('onStatusChanged', { newStatus: '" + newStatus + "', prevStatus: '" + prevStatus + "' })";
                        cordova.getActivity().runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                webView.getEngine().evaluateJavascript(func, new ValueCallback<String>() {
                                    @Override
                                    public void onReceiveValue(String s) {
                                        // no op
                                    }
                                });
                            }
                        });
                    };

                    final SNSCompleteHandler completeHandler = (snsCompletionResult, snssdkState) -> getResultToTheClient(snsCompletionResult, snssdkState, callbackContext);

                    final SNSEventHandler eventHandler = snsEvent -> {
                        Map<String, Object> params = new HashMap<>();
                        final Map<String, Object> payload = snsEvent.getPayload();
                        for (String key: payload.keySet()) {
                            if (key.equals("isCanceled") || key.equals("isCancelled")) {
                                params.put("isCancelled", (Boolean) payload.get(key));
                            } else {
                                params.put(key, payload.get(key).toString());
                            }
                        }
                        final String func = "window.SNSMobileSDK.sendEvent('onEvent', { 'eventType': '" + upperCaseFirstLetter(snsEvent.getEventType()) + "', 'payload': " + mapToString(params) + " })";
                        cordova.getActivity().runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                webView.getEngine().evaluateJavascript(func, new ValueCallback<String>() {
                                    @Override
                                    public void onReceiveValue(String s) {
                                        // no op
                                    }
                                });
                            }
                        });
                    };

                    SNSMobileSDK.Builder snsSdkBuilder;
                    if (flowName.isEmpty()) {
                        snsSdkBuilder = new SNSMobileSDK.Builder(cordova.getActivity());
                    } else {
                        snsSdkBuilder = new SNSMobileSDK.Builder(cordova.getActivity(), null, flowName);
                    }

                    if (!apiUrl.isEmpty()) {
                        snsSdkBuilder.withBaseUrl(apiUrl);
                    }

                    snsSdk = snsSdkBuilder
                            .withAccessToken(accessToken, () -> {
                                Timber.d("SumSub: calling onTokenExpired!");
                                newAccessToken = null;
                                requestNewAccessToken();
                                int cnt = 0;
                                while (newAccessToken == null) {
                                    try {
                                        Thread.sleep(100);
                                    } catch (InterruptedException e) {
                                        //no op
                                    }
                                    if (++cnt > 100) {
                                        return null;
                                    }
                                }
                                Timber.d("SumSub: Received new token: " + newAccessToken + ' ' + Thread.currentThread().getName());
                                return newAccessToken;
                            })
                            .withDebug(isDebug)
                            .withModules(Arrays.asList(new SNSProoface()))
                            .withErrorHandler(errorHandler)
                            .withStateChangedHandler(stateChangedHandler)
                            .withCompleteHandler(completeHandler)
                            .withActionResultHandler(actionResultHandler)
                            .withEventHandler(eventHandler)
                            .withSupportItems(Collections.singletonList(supportItem))
                            .withLocale(new Locale(locale))
                            .build();
                    snsSdk.launch();
                } catch (Exception e) {
                    Timber.e(e);
                }

            }
        });

        cordova.setActivityResultCallback(this);
    }

    private String upperCaseFirstLetter(String s) {
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    private void getResultToTheClient(SNSCompletionResult snsCompletionResult, SNSSDKState snssdkState, CallbackContext callbackContext) {
        if (snsCompletionResult instanceof SNSCompletionResult.SuccessTermination) {
            callbackContext.success(getResult(true, snssdkState, null, null));
        } else if (snsCompletionResult instanceof SNSCompletionResult.AbnormalTermination) {
            SNSCompletionResult.AbnormalTermination abnormalTermination = (SNSCompletionResult.AbnormalTermination) snsCompletionResult;
            String message = abnormalTermination.getException() != null ? abnormalTermination.getException().getMessage() : null;
            if (snssdkState instanceof SNSSDKState.Failed) {
                callbackContext.success(getResult(false, snssdkState, message, snssdkState.getClass().getSimpleName()));
            } else {
                callbackContext.success(getResult(false, new SNSSDKState.Failed.Unknown(new Exception()), message, "Unknown"));
            }
        } else {
            callbackContext.error("Unknown completion result: " + snsCompletionResult.getClass().getName());
        }
    }

    private JSONObject getResult(boolean success, SNSSDKState state, String errorMsg, String errorType) {
        final JSONObject result = new JSONObject();
        try {
            result.put("success", success);
            result.put("status", state != null ? getSDKStateName(state) : "Unknown");
            result.put("errorType", errorType);
            result.put("errorMsg", errorMsg);
            if (state instanceof SNSSDKState.ActionCompleted) {
                final SNSSDKState.ActionCompleted action = (SNSSDKState.ActionCompleted) state;
                final JSONObject actionResult = new JSONObject();
                actionResult.put("actionId", action.getActionId());
                actionResult.put("answer", action.getAnswer() != null ? action.getAnswer().getValue() : null);
                result.put("actionResult", actionResult);
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    private String getSDKStateName(SNSSDKState state) {
        if (state instanceof SNSSDKState.Failed) {
            return "Failed";
        } else {
            return state.getClass().getSimpleName();
        }
    }

    private String mapToString(Map<String, Object> values) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        for (String key: values.keySet()) {
            Object value = values.get(key);
            sb.append("'");
            sb.append(key);
            sb.append("':  ");
            if (value instanceof String) sb.append("'");
            sb.append(value);
            if (value instanceof String) sb.append("'");
            sb.append(",");
        }
        sb.setLength(sb.length() - 1); // strip last comma
        sb.append('}');
        return sb.toString();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
    }

}
