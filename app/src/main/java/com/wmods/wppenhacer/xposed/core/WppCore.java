package com.wmods.wppenhacer.xposed.core;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.drawable.Drawable;
import android.view.Menu;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.wmods.wppenhacer.xposed.core.devkit.Unobfuscator;
import com.wmods.wppenhacer.xposed.utils.ReflectionUtils;
import com.wmods.wppenhacer.xposed.utils.Utils;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class WppCore {

    private static Class<?> mGenJidClass;
    private static Method mGenJidMethod;
    private static Class bottomDialog;
    static final HashSet<ActivityChangeState> listenerChat = new HashSet<>();
    private static Field convChatField;
    private static Field chatJidField;
    private static SharedPreferences privPrefs;
    private static Object mStartUpConfig;
    private static Object mActionUser;
    @SuppressLint("StaticFieldLeak")
    static Activity mCurrentActivity;
    static LinkedHashSet<Activity> activities = new LinkedHashSet<>();
    private static SQLiteDatabase mWaDatabase;


    public static void sendMessage(String number, String message) {
        try {
            var senderMethod = ReflectionUtils.findMethodUsingFilterIfExists(mActionUser.getClass(), (method) -> List.class.isAssignableFrom(method.getReturnType()) && ReflectionUtils.findIndexOfType(method.getParameterTypes(), String.class) != -1);
            if (senderMethod != null) {
                var userJid = createUserJid(number + "@s.whatsapp.net");
                if (userJid == null) {
                    Utils.showToast("UserJID not found", Toast.LENGTH_SHORT);
                    return;
                }
                var newObject = new Object[senderMethod.getParameterCount()];
                for (int i = 0; i < newObject.length; i++) {
                    var param = senderMethod.getParameterTypes()[i];
                    if (param.isPrimitive()) {
                        newObject[i] = 0;
                    }
                }

                var index = ReflectionUtils.findIndexOfType(senderMethod.getParameterTypes(), String.class);
                newObject[index] = message;
                var index2 = ReflectionUtils.findIndexOfType(senderMethod.getParameterTypes(), List.class);
                newObject[index2] = Collections.singletonList(userJid);
                senderMethod.invoke(mActionUser, newObject);
                Utils.showToast("Message sent to " + number, Toast.LENGTH_SHORT);
            }
        } catch (Exception e) {
            Utils.showToast("Error in sending message:" + e.getMessage(), Toast.LENGTH_SHORT);
            XposedBridge.log(e);
        }
    }

    public static void Initialize(ClassLoader loader) throws Exception {
        privPrefs = Utils.getApplication().getSharedPreferences("WaGlobal", Context.MODE_PRIVATE);


        // init UserJID
        var mSendReadClass = XposedHelpers.findClass("com.whatsapp.jobqueue.job.SendReadReceiptJob", loader);
        var subClass = Arrays.stream(mSendReadClass.getConstructors()).filter(c -> c.getParameterTypes().length == 8).findFirst().orElse(null).getParameterTypes()[0];
        mGenJidClass = Arrays.stream(subClass.getFields()).filter(field -> Modifier.isStatic(field.getModifiers())).findFirst().orElse(null).getType();
        mGenJidMethod = Arrays.stream(mGenJidClass.getMethods()).filter(m -> m.getParameterCount() == 1 && !Modifier.isStatic(m.getModifiers())).findFirst().orElse(null);
        // Bottom Dialog
        bottomDialog = Unobfuscator.loadDialogViewClass(loader);

        convChatField = Unobfuscator.loadAntiRevokeConvChatField(loader);
        chatJidField = Unobfuscator.loadAntiRevokeChatJidField(loader);

        // StartUpPrefs
        var startPrefsConfig = Unobfuscator.loadStartPrefsConfig(loader);
        XposedBridge.hookMethod(startPrefsConfig, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                mStartUpConfig = param.thisObject;
            }
        });

        var actionUser = Unobfuscator.loadActionUser(loader);
        XposedBridge.hookAllConstructors(actionUser, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                mActionUser = param.thisObject;
            }
        });

        // Load wa database
        loadDatabase();


    }

    public static void loadDatabase() {
        if (mWaDatabase != null) return;
        var dataDir = Utils.getApplication().getFilesDir().getParentFile();
        var database = new File(dataDir, "databases/wa.db");
        if (database.exists()) {
            mWaDatabase = SQLiteDatabase.openDatabase(database.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        }
    }


    public static Activity getCurrentActivity() {
        return mCurrentActivity;
    }

    public static Activity getActivityBySimpleName(String name) {
        for (var activity : activities) {
            if (activity.getClass().getSimpleName().equals(name)) {
                return activity;
            }
        }
        return null;
    }


    public static int getDefaultTheme() {
        if (mStartUpConfig != null) {
            var result = ReflectionUtils.findMethodUsingFilterIfExists(mStartUpConfig.getClass(), (method) -> method.getParameterCount() == 0 && method.getReturnType() == int.class);
            if (result != null) {
                var value = ReflectionUtils.callMethod(result, mStartUpConfig);
                if (value != null) return (int) value;
            }
        }
        var startup_prefs = Utils.getApplication().getSharedPreferences("startup_prefs", Context.MODE_PRIVATE);
        return startup_prefs.getInt("night_mode", 0);
    }

    @NonNull
    public static String getContactName(Object userJid) {
        loadDatabase();
        if (mWaDatabase == null || userJid == null) return "";
        String name = null;
        var rawJid = getRawString(userJid);
        var cursor = mWaDatabase.query("wa_contacts", new String[]{"display_name"}, "jid = ?", new String[]{rawJid}, null, null, null);
        if (cursor != null && cursor.moveToFirst()) {
            name = cursor.getString(0);
            cursor.close();
        }
        if (name == null) {
            var cursor2 = mWaDatabase.query("wa_vnames", new String[]{"verified_name"}, "jid = ?", new String[]{rawJid}, null, null, null);
            if (cursor2 != null && cursor2.moveToFirst()) {
                name = cursor2.getString(0);
                cursor2.close();
            }
        }
        return name == null ? "" : name;
    }

    public static Object createUserJid(String rawjid) {
        var genInstance = XposedHelpers.newInstance(mGenJidClass);
        try {
            return mGenJidMethod.invoke(genInstance, rawjid);
        } catch (Exception e) {
            XposedBridge.log(e);
        }
        return null;
    }

    public static String getRawString(Object userjid) {
        if (userjid == null) return null;
        return (String) XposedHelpers.callMethod(userjid, "getRawString");
    }

    public static boolean isGroup(String str) {
        if (str == null) return false;
        return str.contains("-") || str.contains("@g.us") || (!str.contains("@") && str.length() > 16);
    }

    public static String getCurrentRawJID() {
        var conversation = getCurrentConversation();
        if (conversation == null) return null;
        var chatField = XposedHelpers.getObjectField(conversation, convChatField.getName());
        var chatJidObj = XposedHelpers.getObjectField(chatField, chatJidField.getName());
        return getRawString(chatJidObj);
    }

    public static String stripJID(String str) {
        try {
            return (str.contains("@g.us") || str.contains("@s.whatsapp.net") || str.contains("@broadcast")) ? str.substring(0, str.indexOf("@")) : str;
        } catch (Exception e) {
            XposedBridge.log(e.getMessage());
            return str;
        }
    }

    public static Drawable getContactPhotoDrawable(String jid) {
        var file = getContactPhotoFile(jid);
        if (file == null) return null;
        return Drawable.createFromPath(file.getAbsolutePath());
    }

    public static File getContactPhotoFile(String jid) {
        String datafolder = Utils.getApplication().getCacheDir().getParent() + "/";
        File file = new File(datafolder + "/cache/" + "Profile Pictures" + "/" + stripJID(jid) + ".jpg");
        if (!file.exists())
            file = new File(datafolder + "files" + "/" + "Avatars" + "/" + jid + ".j");
        if (file.exists()) return file;
        return null;
    }

    public static String getMyName() {
        var startup_prefs = Utils.getApplication().getSharedPreferences("startup_prefs", Context.MODE_PRIVATE);
        return startup_prefs.getString("push_name", "WhatsApp");
    }

    public static String getMyNumber() {
        var mainPrefs = getMainPrefs();
        return mainPrefs.getString("registration_jid", "");
    }

    public static SharedPreferences getMainPrefs() {
        return Utils.getApplication().getSharedPreferences(Utils.getApplication().getPackageName() + "_preferences_light", Context.MODE_PRIVATE);
    }


    public static String getMyBio() {
        var mainPrefs = getMainPrefs();
        return mainPrefs.getString("my_current_status", "");
    }

    public static Drawable getMyPhoto() {
        String datafolder = Utils.getApplication().getCacheDir().getParent() + "/";
        File file = new File(datafolder + "files" + "/" + "me.jpg");
        if (file.exists()) return Drawable.createFromPath(file.getAbsolutePath());
        return null;
    }

    public static Dialog createDialog(Context context) {
        return (Dialog) XposedHelpers.newInstance(bottomDialog, context, 0);
    }

    @Nullable
    public static Activity getCurrentConversation() {
        if (mCurrentActivity == null) return null;
        Class<?> conversation = XposedHelpers.findClass("com.whatsapp.Conversation", mCurrentActivity.getClassLoader());
        if (conversation.isInstance(mCurrentActivity)) return mCurrentActivity;
        return null;
    }

    @SuppressLint("ApplySharedPref")
    public static void setPrivString(String key, String value) {
        privPrefs.edit().putString(key, value).commit();
    }

    public static String getPrivString(String key, String defaultValue) {
        return privPrefs.getString(key, defaultValue);
    }

    @SuppressLint("ApplySharedPref")
    public static void removePrivKey(String s) {
        if (s != null && privPrefs.contains(s))
            privPrefs.edit().remove(s).commit();
    }


    @SuppressLint("ApplySharedPref")
    public static void setPrivBoolean(String key, boolean value) {
        privPrefs.edit().putBoolean(key, value).commit();
    }

    public static boolean getPrivBoolean(String key, boolean defaultValue) {
        return privPrefs.getBoolean(key, defaultValue);
    }

    public static void addListenerChat(ActivityChangeState listener) {
        listenerChat.add(listener);
    }

    public interface ActivityChangeState {

        void onChange(Object object, ChangeType type);

        enum ChangeType {
            START, END, RESUME, PAUSE
        }
    }

    public abstract static class OnMenuCreate {
        public void onBeforeCreate(Activity activity, Menu menu) {
        }

        public void onAfterCreate(Activity activity, Menu menu) {
        }
    }

}
