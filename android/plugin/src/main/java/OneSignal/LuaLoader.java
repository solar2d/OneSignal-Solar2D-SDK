// MUST keep as package as "OneSignal"!
// Corona looks for this based on "require" in lua
//   that currently already in developer's apps.
package OneSignal;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import com.ansca.corona.CoronaActivity;
import com.ansca.corona.CoronaEnvironment;
import com.ansca.corona.CoronaRuntime;
import com.ansca.corona.CoronaRuntimeListener;
import com.ansca.corona.CoronaRuntimeTask;
import com.ansca.corona.CoronaRuntimeTaskDispatcher;
import com.naef.jnlua.JavaFunction;
import com.naef.jnlua.LuaState;
import com.naef.jnlua.LuaType;
import com.naef.jnlua.NamedJavaFunction;
import com.onesignal.FunctionExposer;
import com.onesignal.OSInAppMessageAction;
import com.onesignal.OSNotification;
import com.onesignal.OSNotificationOpenedResult;
import com.onesignal.OSNotificationReceivedEvent;
import com.onesignal.OSSubscriptionObserver;
import com.onesignal.OSSubscriptionState;
import com.onesignal.OSSubscriptionStateChanges;
import com.onesignal.OneSignal;
import com.onesignal.OneSignal.PostNotificationResponseHandler;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collection;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Map.Entry;

public class LuaLoader implements JavaFunction {

    private static CoronaRuntimeListener mCoronaRuntimeListener = null;

    private class OneSignalCoronaRuntimeListener implements CoronaRuntimeListener {
        @Override
        public void onExiting(CoronaRuntime runtime) {
            OneSignal.setNotificationWillShowInForegroundHandler(null);
            OneSignal.setNotificationOpenedHandler(null);
            OneSignal.setInAppMessageClickHandler(null);
            // todo
            // OneSignal.removeNotificationReceivedHandler();
        }

        @Override
        public void onLoaded(CoronaRuntime runtime) {
        }

        @Override
        public void onResumed(CoronaRuntime runtime) {
        }

        @Override
        public void onStarted(CoronaRuntime runtime) {
        }

        @Override
        public void onSuspended(CoronaRuntime runtime) {
        }
    }

    @Override
    public int invoke(LuaState luaState) {
        final String libName = luaState.toString(1);
        luaState.register(libName, new NamedJavaFunction[]{
                new InitFunction(),
                new SendTagsFunction(),
                new GetTagsFunction(),
                new IdsAvailableFunction(),
                new ClearAllNotificationsFunction(),
                new EnableVibrateFunction(),
                new EnableSoundFunction(),
                new EnableNotificationsWhenActiveFunction(),
                new EnableInAppAlertNotificationFunction(),
                new SetSubscriptionFunction(),
                new DisablePushFunction(),
                new PostNotificationFunction(),
                new SetEmailFunction(),
                new PromptLocationFunction(),
                new SetLogLevelFunction(),
                new SetInAppMessageClickHandlerFunction(),
                new AddTriggerFunction(),
                new AddTriggersFunction(),
                new RemoveTriggerForKeyFunction(),
                new RemoveTriggersForKeysFunction(),
                new GetTriggerValueForKeyFunction(),
                new PauseInAppMessagesFunction()
        });

        return 1;
    }

    private boolean fCompleteInFocusNotifications = true;

    private class InitFunction implements NamedJavaFunction {

        private InitFunction() {
        }

        @Override
        public String getName() {
            return "init";
        }

        @Override
        public int invoke(LuaState luaState) {
            try {
                String appId = luaState.checkString(1);
                String googleProjectNum = luaState.checkString(2);

                CoronaNotificationOpenedHandler notifOpenHandler = null;
                try {
                    luaState.checkType(3, LuaType.FUNCTION); // throws if this doesn't match
                    int luaFunctionRefkey = luaState.ref(LuaState.REGISTRYINDEX);
                    notifOpenHandler = new CoronaNotificationOpenedHandler(luaFunctionRefkey, new CoronaRuntimeTaskDispatcher(luaState));
                } catch (Throwable ignored) {
                } // Not required so continue on.

                OneSignal.sdkType = "corona";
                Activity coronaActivity = CoronaEnvironment.getCoronaActivity();
                if (mCoronaRuntimeListener == null) {
                    mCoronaRuntimeListener = new OneSignalCoronaRuntimeListener();
                    CoronaEnvironment.addRuntimeListener(mCoronaRuntimeListener);
                }

                OneSignal.initWithContext(coronaActivity);
                OneSignal.setAppId(appId);
                OneSignal.setNotificationOpenedHandler(notifOpenHandler);
                OneSignal.setNotificationWillShowInForegroundHandler(new OneSignal.OSNotificationWillShowInForegroundHandler() {
                    @Override
                    public void notificationWillShowInForeground(OSNotificationReceivedEvent osNotificationReceivedEvent) {
                        if (fCompleteInFocusNotifications) {
                            OSNotification notification = osNotificationReceivedEvent.getNotification();
                            osNotificationReceivedEvent.complete(notification);
                        } else {
                            osNotificationReceivedEvent.complete(null);
                        }
                    }
                });
//						init(coronaActivity, googleProjectNum, appId, notifOpenHandler);

                // Disabling Corona's GCM receiver so OneSignal notifications are not displayed 2x.
                //   - Our CoronaGCMFilterProxyReceiver is called instead which we can then allow non-Onesignal GCM messages through.
                String packageName = coronaActivity.getApplicationContext().getPackageName();
                PackageManager packageManager = coronaActivity.getPackageManager();
                ComponentName componentName = new ComponentName(packageName, "com.ansca.corona.notifications.GoogleCloudMessagingBroadcastReceiver");
                packageManager.setComponentEnabledSetting(componentName, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            } catch (Throwable t) {
                t.printStackTrace();
            }

            return 0;
        }

        private class CoronaNotificationOpenedHandler implements OneSignal.OSNotificationOpenedHandler {
            private int luaFunctionRefkey;
            private CoronaRuntimeTaskDispatcher coronaDispatcher;

            public CoronaNotificationOpenedHandler(int inLuaFunctionRefKey, CoronaRuntimeTaskDispatcher inCoronaDispatcher) {
                this.luaFunctionRefkey = inLuaFunctionRefKey;
                this.coronaDispatcher = inCoronaDispatcher;
            }

            @Override
            public void notificationOpened(final OSNotificationOpenedResult osNotificationOpenResult) {
                // The Corona Activity could be dead at this point if the user pressed the android back button.
                if (CoronaEnvironment.getCoronaActivity() == null || osNotificationOpenResult == null)
                    return;

                final OSNotification notification = osNotificationOpenResult.getNotification();
                if (notification == null)
                    return;

                CoronaEnvironment.getCoronaActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        CoronaRuntimeTask task = new CoronaRuntimeTask() {
                            @Override
                            public void executeUsing(CoronaRuntime runtime) {
                                try {
                                    LuaState luaState = runtime.getLuaState();
                                    luaState.rawGet(LuaState.REGISTRYINDEX, luaFunctionRefkey);

                                    if (notification.getBody() != null)
                                        luaState.pushString(notification.getBody());
                                    else
                                        luaState.pushString("");

                                    if (notification.getAdditionalData() != null && notification.getAdditionalData().length() > 0)
                                        luaState.pushString(notification.getAdditionalData().toString());
                                    else
                                        luaState.pushNil();

                                    // luaState.pushBoolean(notification.isAppInFocus);
                                    luaState.pushNil();
                                    luaState.call(3, 0);
                                } catch (Exception ex) {
                                    ex.printStackTrace();
                                }
                            }
                        };

                        coronaDispatcher.send(task);
                    }
                });
            }
        }
    }

    private class SendTagsFunction implements NamedJavaFunction {
        private SendTagsFunction() {
        }

        @Override
        public String getName() {
            return "sendTags";
        }

        @Override
        public int invoke(LuaState luaState) {
            try {
                OneSignal.sendTags(new JSONObject(luaState.checkString(1)));
            } catch (Throwable t) {
                t.printStackTrace();
            }
            return 0;
        }
    }

    private class GetTagsFunction implements NamedJavaFunction {
        private GetTagsFunction() {
        }

        @Override
        public String getName() {
            return "getTags";
        }

        @Override
        public int invoke(LuaState luaState) {
            try {
                luaState.checkType(1, LuaType.FUNCTION); // throws if this doesn't match
                int luaFunctionRefkey = luaState.ref(LuaState.REGISTRYINDEX);
                CoronaGetTagsHandler getTagsHandler = new CoronaGetTagsHandler(luaFunctionRefkey, new CoronaRuntimeTaskDispatcher(luaState));

                OneSignal.getTags(getTagsHandler);
            } catch (Throwable t) {
                t.printStackTrace();
            }
            return 0;
        }

        private class CoronaGetTagsHandler implements OneSignal.OSGetTagsHandler {
            private int luaFunctionRefkey;
            private CoronaRuntimeTaskDispatcher coronaDispatcher;

            public CoronaGetTagsHandler(int inLuaFunctionRefKey, CoronaRuntimeTaskDispatcher inCoronaDispatcher) {
                this.luaFunctionRefkey = inLuaFunctionRefKey;
                this.coronaDispatcher = inCoronaDispatcher;
            }


            @Override
            public void tagsAvailable(final JSONObject tags) {
                // The Corona Activity could be dead at this point if the user pressed the android back button.
                if (CoronaEnvironment.getCoronaActivity() == null)
                    return;

                CoronaEnvironment.getCoronaActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        CoronaRuntimeTask task = new CoronaRuntimeTask() {
                            @Override
                            public void executeUsing(CoronaRuntime runtime) {
                                try {
                                    LuaState luaState = runtime.getLuaState();
                                    luaState.rawGet(LuaState.REGISTRYINDEX, luaFunctionRefkey);
                                    luaState.unref(LuaState.REGISTRYINDEX, luaFunctionRefkey);

                                    // Send tags to lua for decode if there is data
                                    if (tags != null && tags.length() > 0)
                                        luaState.pushString(tags.toString());
                                    else
                                        luaState.pushNil();

                                    luaState.call(1, 0);
                                } catch (Exception ex) {
                                    ex.printStackTrace();
                                }
                            }
                        };

                        coronaDispatcher.send(task);
                    }
                });
            }
        }
    }


    private class IdsAvailableFunction implements NamedJavaFunction {
        private IdsAvailableFunction() {
        }

        @Override
        public String getName() {
            return "idsAvailable";
        }

        @Override
        public int invoke(LuaState luaState) {
            try {
                luaState.checkType(1, LuaType.FUNCTION); // throws if this doesn't match
                final int luaFunctionRefkey = luaState.ref(LuaState.REGISTRYINDEX);
                final CoronaRuntimeTaskDispatcher coronaDispatcher = new CoronaRuntimeTaskDispatcher(luaState);

                OneSignal.addSubscriptionObserver(new OSSubscriptionObserver() {
                    @Override
                    public void onOSSubscriptionChanged(OSSubscriptionStateChanges changes) {
                        if (changes.getTo() != null && changes.getTo().getUserId() != null) {
                            OneSignal.removeSubscriptionObserver(this);
                            DispatchIDs(changes.getTo(), luaFunctionRefkey, coronaDispatcher);
                        }
                    }
                });
            } catch (Throwable t) {
                t.printStackTrace();
            }
            return 0;
        }

        private void DispatchIDs(OSSubscriptionState state, int luaFunctionRefkey, CoronaRuntimeTaskDispatcher coronaDispatcher) {
            if (CoronaEnvironment.getCoronaActivity() != null) {
                coronaDispatcher.send(new CoronaRuntimeTask() {
                    @Override
                    public void executeUsing(CoronaRuntime runtime) {
                        try {
                            LuaState luaState = runtime.getLuaState();
                            luaState.rawGet(LuaState.REGISTRYINDEX, luaFunctionRefkey);
                            luaState.unref(LuaState.REGISTRYINDEX, luaFunctionRefkey);

                            luaState.pushString(state.getUserId());
                            if (state.getPushToken() != null)
                                luaState.pushNil();
                            else
                                luaState.pushString(state.getPushToken());

                            luaState.call(2, 0);
                        } catch (Throwable t) {
                            t.printStackTrace();
                        }
                    }
                });
            }
        }
    }


    private class ClearAllNotificationsFunction implements NamedJavaFunction {
        private ClearAllNotificationsFunction() {
        }

        @Override
        public String getName() {
            return "clearAllNotifications";
        }

        @Override
        public int invoke(LuaState luaState) {
            try {
                Context context = CoronaEnvironment.getApplicationContext();
                ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE)).cancelAll();
            } catch (Throwable t) {
                t.printStackTrace();
            }
            return 0;
        }
    }

    private class EnableVibrateFunction implements NamedJavaFunction {
        private EnableVibrateFunction() {
        }

        @Override
        public String getName() {
            return "enableVibrate";
        }

        @Override
        public int invoke(LuaState luaState) {
            return 0;
        }
    }

    private class EnableSoundFunction implements NamedJavaFunction {
        private EnableSoundFunction() {
        }

        @Override
        public String getName() {
            return "enableSound";
        }

        @Override
        public int invoke(LuaState luaState) {
            return 0;
        }
    }


    private class EnableNotificationsWhenActiveFunction implements NamedJavaFunction {
        private EnableNotificationsWhenActiveFunction() {
        }

        @Override
        public String getName() {
            return "enableNotificationsWhenActive";
        }

        @Override
        public int invoke(LuaState luaState) {
            try {
                fCompleteInFocusNotifications = (luaState.checkBoolean(1));
            } catch (Throwable t) {
                t.printStackTrace();
            }
            return 0;
        }
    }

    private class EnableInAppAlertNotificationFunction implements NamedJavaFunction {
        private EnableInAppAlertNotificationFunction() {
        }

        @Override
        public String getName() {
            return "enableInAppAlertNotification";
        }

        @Override
        public int invoke(LuaState luaState) {
            try {
                fCompleteInFocusNotifications = luaState.checkBoolean(1);
            } catch (Throwable t) {
                t.printStackTrace();
            }
            return 0;
        }
    }

    private class DisablePushFunction implements NamedJavaFunction {
        private DisablePushFunction() {
        }

        @Override
        public String getName() {
            return "disablePush";
        }

        @Override
        public int invoke(LuaState luaState) {
            try {
                OneSignal.disablePush(luaState.checkBoolean(1));
            } catch (Throwable t) {
                t.printStackTrace();
            }
            return 0;
        }
    }

    private class SetSubscriptionFunction implements NamedJavaFunction {
        private SetSubscriptionFunction() {
        }

        @Override
        public String getName() {
            return "setSubscription";
        }

        @Override
        public int invoke(LuaState luaState) {
            try {
                OneSignal.disablePush(!luaState.checkBoolean(1));
            } catch (Throwable t) {
                t.printStackTrace();
            }
            return 0;
        }
    }

    private class PostNotificationFunction implements NamedJavaFunction {
        private PostNotificationFunction() {
        }

        @Override
        public String getName() {
            return "postNotification";
        }

        @Override
        public int invoke(LuaState luaState) {
            try {
                final int luaOnFailureFunctionRefkey = luaState.ref(LuaState.REGISTRYINDEX);
                final int luaOnSuccessFunctionRefkey = luaState.ref(LuaState.REGISTRYINDEX);
                final CoronaRuntimeTaskDispatcher coronaDispatcher = new CoronaRuntimeTaskDispatcher(luaState);

                OneSignal.postNotification(luaState.checkString(1), new PostNotificationResponseHandler() {
                    private void fireLuaCallBack(final int luaFunctionRefKey, final JSONObject jsonObj) {
                        // The Corona Activity could be dead at this point if the user pressed the android back button.
                        if (CoronaEnvironment.getCoronaActivity() == null)
                            return;

                        CoronaEnvironment.getCoronaActivity().runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                CoronaRuntimeTask task = new CoronaRuntimeTask() {
                                    @Override
                                    public void executeUsing(CoronaRuntime runtime) {
                                        try {
                                            LuaState luaState = runtime.getLuaState();
                                            luaState.rawGet(LuaState.REGISTRYINDEX, luaFunctionRefKey);

                                            luaState.pushString(jsonObj.toString());

                                            luaState.call(1, 0);
                                        } catch (Throwable t) {
                                            t.printStackTrace();
                                        }
                                    }
                                };
                                coronaDispatcher.send(task);
                            }
                        });
                    }

                    @Override
                    public void onSuccess(JSONObject response) {
                        fireLuaCallBack(luaOnSuccessFunctionRefkey, response);
                    }

                    @Override
                    public void onFailure(JSONObject response) {
                        fireLuaCallBack(luaOnFailureFunctionRefkey, response);
                    }
                });
            } catch (Throwable t) {
                t.printStackTrace();
            }
            return 0;
        }
    }

    private class SetEmailFunction implements NamedJavaFunction {
        private SetEmailFunction() {
        }

        @Override
        public String getName() {
            return "setEmail";
        }

        @Override
        public int invoke(LuaState luaState) {
            try {
                OneSignal.setEmail(luaState.checkString(1));
            } catch (Throwable t) {
                t.printStackTrace();
            }
            return 0;
        }
    }

    private class PromptLocationFunction implements NamedJavaFunction {
        private PromptLocationFunction() {
        }

        @Override
        public String getName() {
            return "promptLocation";
        }

        @Override
        public int invoke(LuaState luaState) {
            OneSignal.promptLocation();
            return 0;
        }
    }

    private class SetLogLevelFunction implements NamedJavaFunction {
        private SetLogLevelFunction() {
        }

        @Override
        public String getName() {
            return "setLogLevel";
        }

        @Override
        public int invoke(LuaState luaState) {
            try {
                OneSignal.setLogLevel(luaState.checkInteger(1), luaState.checkInteger(2));
            } catch (Throwable t) {
                t.printStackTrace();
            }
            return 0;
        }
    }

    private static class SetInAppMessageClickHandlerFunction implements NamedJavaFunction {
        private SetInAppMessageClickHandlerFunction() {
        }

        @Override
        public String getName() {
            return "setInAppMessageClickHandler";
        }

        @Override
        public int invoke(LuaState luaState) {
            try {
                CoronaInAppMessageClickHandler inAppMessageClickHandler = null;
                try {
                    luaState.checkType(1, LuaType.FUNCTION); // throws if this doesn't match
                    int luaFunctionRefkey = luaState.ref(LuaState.REGISTRYINDEX);
                    inAppMessageClickHandler = new CoronaInAppMessageClickHandler(luaFunctionRefkey, new CoronaRuntimeTaskDispatcher(luaState));
                } catch (Throwable ignored) {
                } // Not required so continue on.

                OneSignal.setInAppMessageClickHandler(inAppMessageClickHandler);
            } catch (Throwable t) {
                t.printStackTrace();
            }
            return 0;
        }

        private class CoronaInAppMessageClickHandler implements OneSignal.OSInAppMessageClickHandler {
            private int luaFunctionRefkey;
            private CoronaRuntimeTaskDispatcher coronaDispatcher;

            public CoronaInAppMessageClickHandler(int inLuaFunctionRefKey, CoronaRuntimeTaskDispatcher inCoronaDispatcher) {
                this.luaFunctionRefkey = inLuaFunctionRefKey;
                this.coronaDispatcher = inCoronaDispatcher;
            }

            @Override
            public void inAppMessageClicked(final OSInAppMessageAction osInAppMessageAction) {
                // The Corona Activity could be dead at this point if the user pressed the android back button.
                if (CoronaEnvironment.getCoronaActivity() == null)
                    return;

                CoronaEnvironment.getCoronaActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        CoronaRuntimeTask task = new CoronaRuntimeTask() {
                            @Override
                            public void executeUsing(CoronaRuntime runtime) {
                                try {
                                    LuaState luaState = runtime.getLuaState();
                                    luaState.rawGet(LuaState.REGISTRYINDEX, luaFunctionRefkey);

                                    luaState.pushString(osInAppMessageAction.toJSONObject().toString());

                                    luaState.call(1, 0);
                                } catch (Exception ex) {
                                    ex.printStackTrace();
                                }
                            }
                        };
                        coronaDispatcher.send(task);
                    }
                });
            }
        }
    }

    private class AddTriggerFunction implements NamedJavaFunction {
        private AddTriggerFunction() {
        }

        @Override
        public String getName() {
            return "addTrigger";
        }

        @Override
        public int invoke(LuaState luaState) {
            try {
                JSONObject jsonObject = new JSONObject(luaState.checkString(1));
                OneSignal.addTriggers(FunctionExposer.jsonObjectToMap(jsonObject));
            } catch (JSONException e) {
                e.printStackTrace();
            }
            return 0;
        }
    }

    private static class AddTriggersFunction implements NamedJavaFunction {
        private AddTriggersFunction() {
        }

        @Override
        public String getName() {
            return "addTriggers";
        }

        @Override
        public int invoke(LuaState luaState) {
            try {
                JSONObject jsonObject = new JSONObject(luaState.checkString(1));
                OneSignal.addTriggers(FunctionExposer.jsonObjectToMap(jsonObject));
            } catch (JSONException e) {
                e.printStackTrace();
            }
            return 0;
        }
    }

    private static class RemoveTriggerForKeyFunction implements NamedJavaFunction {
        private RemoveTriggerForKeyFunction() {
        }

        @Override
        public String getName() {
            return "removeTriggerForKey";
        }

        @Override
        public int invoke(LuaState luaState) {
            try {
                OneSignal.removeTriggerForKey(luaState.checkString(1));
            } catch (Throwable t) {
                t.printStackTrace();
            }
            return 0;
        }
    }

    private static class RemoveTriggersForKeysFunction implements NamedJavaFunction {
        private RemoveTriggersForKeysFunction() {
        }

        @Override
        public String getName() {
            return "removeTriggersForKeys";
        }

        @Override
        public int invoke(LuaState luaState) {
            String keys = luaState.checkString(1);
            try {
                JSONArray jsonArray = new JSONArray(keys);
                Collection<String> keysCollection = FunctionExposer.extractStringsFromCollection(
                        FunctionExposer.jsonArrayToList(jsonArray)
                );
                OneSignal.removeTriggersForKeys(keysCollection);
            } catch (Throwable e) {
                e.printStackTrace();
            }

            return 0;
        }
    }

    private class GetTriggerValueForKeyFunction implements NamedJavaFunction {
        private GetTriggerValueForKeyFunction() {
        }

        @Override
        public String getName() {
            return "getTriggerValueForKey";
        }

        @Override
        public int invoke(LuaState luaState) {
            try {
                final String triggerKey = luaState.checkString(1);
                luaState.checkType(2, LuaType.FUNCTION); // throws if this doesn't match
                final int luaFunctionRefkey = luaState.ref(LuaState.REGISTRYINDEX);

                final CoronaRuntimeTaskDispatcher coronaDispatcher = new CoronaRuntimeTaskDispatcher(luaState);

                // The Corona Activity could be dead at this point if the user pressed the android back button.
                if (CoronaEnvironment.getCoronaActivity() != null) {
                    CoronaEnvironment.getCoronaActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            CoronaRuntimeTask task = new CoronaRuntimeTask() {
                                @Override
                                public void executeUsing(CoronaRuntime runtime) {
                                    try {
                                        LuaState luaState = runtime.getLuaState();

                                        final Object value = OneSignal.getTriggerValueForKey(triggerKey);
                                        luaState.rawGet(LuaState.REGISTRYINDEX, luaFunctionRefkey);
                                        luaState.unref(LuaState.REGISTRYINDEX, luaFunctionRefkey);

                                        luaState.pushString(triggerKey);
                                        if (value == null)
                                            luaState.pushNil();
                                        else
                                            luaState.pushJavaObject(value);

                                        luaState.call(2, 0);

                                    } catch (Throwable t) {
                                        t.printStackTrace();
                                    }
                                }
                            };
                            coronaDispatcher.send(task);
                        }
                    });
                }
            } catch (Throwable t) {
                t.printStackTrace();
            }
            return 0;
        }
    }

    private class PauseInAppMessagesFunction implements NamedJavaFunction {
        private PauseInAppMessagesFunction() {
        }

        @Override
        public String getName() {
            return "pauseInAppMessages";
        }

        @Override
        public int invoke(LuaState luaState) {
            try {
                OneSignal.pauseInAppMessages(luaState.checkBoolean(1));
            } catch (Throwable t) {
                t.printStackTrace();
            }
            return 0;
        }
    }
}