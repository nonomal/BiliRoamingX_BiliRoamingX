package app.revanced.bilibili.patches.main;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;

import com.bilibili.app.preferences.BiliPreferencesActivity;
import com.bilibili.bplus.im.setting.MessageTipItemActivity;
import com.bilibili.magicasakura.widgets.TintCheckBox;
import com.bilibili.magicasakura.widgets.TintRadioButton;
import com.bilibili.magicasakura.widgets.TintSwitchCompat;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Objects;

import app.revanced.bilibili.account.PassportChangeReceiver;
import app.revanced.bilibili.patches.CustomThemePatch;
import app.revanced.bilibili.patches.DpiPatch;
import app.revanced.bilibili.patches.PlaybackSpeedPatch;
import app.revanced.bilibili.patches.okhttp.BangumiSeasonHook;
import app.revanced.bilibili.settings.Settings;
import app.revanced.bilibili.utils.KtUtils;
import app.revanced.bilibili.utils.Logger;
import app.revanced.bilibili.utils.PreferenceUpdater;
import app.revanced.bilibili.utils.Reflex;
import app.revanced.bilibili.utils.SettingsSyncHelper;
import app.revanced.bilibili.utils.SubtitleParamsCache;
import app.revanced.bilibili.utils.Themes;
import app.revanced.bilibili.utils.UposReplacer;
import app.revanced.bilibili.utils.Utils;
import tv.danmaku.bili.MainActivityV2;

public class ApplicationDelegate {
    private static final ArrayDeque<WeakReference<Activity>> activityRefs = new ArrayDeque<>();
    private static final Point screenSize = new Point();

    @Keep
    public static void onCreate(Application app) {
        app.registerActivityLifecycleCallbacks(new ActivityLifecycleCallback());
        app.registerComponentCallbacks(new ComponentCallbacks());
        updateBitmapDefaultDensity();
        PassportChangeReceiver.register();
        CustomThemePatch.refresh();
        Utils.runOnMainThread(500L, CustomThemePatch::delayRefresh);
        if (Utils.isMainProcess()) {
            Utils.async(ApplicationDelegate::startLog);
            Utils.async(PlaybackSpeedPatch::refreshOverrideSpeedList);
            SubtitleParamsCache.updateFont();
            KtUtils.getAreaTask();
            UposReplacer.getBaseUposList();
            PreferenceUpdater.register();
            Themes.registerGarbChangeObserver();
            Utils.runOnMainThread(500L, () -> Utils.async(BangumiSeasonHook::injectExtraSearchTypes));
            Utils.runOnMainThread(500L, () -> Utils.async(BangumiSeasonHook::injectExtraSearchTypesV2));
            Utils.runOnMainThread(2000L, () -> Utils.async(CouponAutoReceiver::check));
        } else {
            SettingsSyncHelper.register();
        }
    }

    @Keep
    public static Resources getResources(Resources resources) {
        if (Utils.getContext() == null)
            return resources;
        int newDpi = getCustomDpi();
        if (newDpi != 0) {
            updateDpi(resources.getDisplayMetrics(), newDpi);
            Configuration configuration = resources.getConfiguration();
            configuration.densityDpi = newDpi;
            var newAxisDpi = calcNewAxisDpi();
            configuration.screenWidthDp = newAxisDpi.first;
            configuration.screenHeightDp = newAxisDpi.second;
        }
        return resources;
    }

    @Keep
    static void updateBitmapDefaultDensity() {
        // to let pictures like cover show correctly when customizing dpi.
        float scale = DpiPatch.displayScale;
        if (scale != 0f) {
            float density = Resources.getSystem().getDisplayMetrics().density;
            int newDpi = (int) ((density + scale) * 160);
            try {
                Reflex.callStaticMethod(Bitmap.class, "setDefaultDensity", newDpi);
            } catch (Throwable t) {
                Logger.error(t, () -> "Failed to update bitmap default density");
            }
        }
    }

    @Keep
    static int getCustomDpi() {
        float scale = DpiPatch.displayScale;
        if (scale == 0f) return 0;
        float sysDensity = Resources.getSystem().getDisplayMetrics().density;
        float newDensity = sysDensity + scale;
        return (int) (160 * newDensity);
    }

    @Keep
    public static void onActivityPreConfigurationChanged(Activity activity, Configuration newConfig) {
        ActivityLifecycleCallback.printLifecycle(activity, "onActivityPreConfigurationChanged", false);
        var newDpi = getCustomDpi();
        if (newDpi != 0) {
            newConfig.densityDpi = newDpi;
            var newAxisDpi = calcNewAxisDpi();
            newConfig.screenWidthDp = newAxisDpi.first;
            newConfig.screenHeightDp = newAxisDpi.second;
            updateDpi(activity, newDpi);
        }
    }

    @Keep
    public static Context onActivityPreAttachBaseContext(Activity activity, Context base) {
        ActivityLifecycleCallback.printLifecycle(activity, "onActivityPreAttachBaseContext", false);
        var newDpi = getCustomDpi();
        if (newDpi == 0) return base;
        var configuration = base.getResources().getConfiguration();
        configuration.densityDpi = newDpi;
        var newAxisDpi = calcNewAxisDpi();
        configuration.screenWidthDp = newAxisDpi.first;
        configuration.screenHeightDp = newAxisDpi.second;
        return base.createConfigurationContext(configuration);
    }

    @Keep
    private static Pair<Integer, Integer> calcNewAxisDpi() {
        Resources sysRes = Resources.getSystem();
        float sysDensity = sysRes.getDisplayMetrics().density;
        int sysWidthDp = sysRes.getConfiguration().screenWidthDp;
        int sysHeightDp = sysRes.getConfiguration().screenHeightDp;
        var windowManager = (WindowManager) Utils.getContext().getSystemService(Context.WINDOW_SERVICE);
        windowManager.getDefaultDisplay().getRealSize(screenSize);
        int widthPixels = screenSize.x;
        int heightPixels = screenSize.y;
        int widthInsets = Math.max(0, widthPixels - (int) (sysWidthDp * sysDensity));
        int heightInsets = Math.max(0, heightPixels - (int) (sysHeightDp * sysDensity));
        float customDensity = DpiPatch.displayScale + sysDensity;
        int newWidthDp = (int) ((widthPixels - widthInsets) / customDensity);
        int newHeightDp = (int) ((heightPixels - heightInsets) / customDensity);
        return Pair.create(newWidthDp, newHeightDp);
    }

    static void updateDpi(Activity activity, int newDpi) {
        updateDpi(activity.getResources().getDisplayMetrics(), newDpi);
    }

    static void updateDpi(DisplayMetrics dm, int newDpi) {
        var newDensity = newDpi / 160f;
        dm.densityDpi = newDpi;
        dm.density = newDensity;
        dm.scaledDensity = newDensity;
    }

    @Nullable
    public static Activity getTopActivity() {
        var ref = activityRefs.peek();
        if (ref != null)
            return ref.get();
        return null;
    }

    @NonNull
    public static Activity requireTopActivity() {
        return Objects.requireNonNull(getTopActivity());
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    static void startLog() {
        if (!Settings.Debug.get())
            return;
        try {
            File logFile = KtUtils.getLogFile();
            File oldLogFile = KtUtils.getOldLogFile();
            if (logFile.exists()) {
                if (oldLogFile.exists())
                    oldLogFile.delete();
                logFile.renameTo(oldLogFile);
            }
            logFile.delete();
            logFile.createNewFile();
            Runtime.getRuntime().exec(
                    new String[]{
                            "logcat",
                            "-T",
                            "100",
                            "-f",
                            logFile.getAbsolutePath(),
                            Logger.LOG_TAG,
                            "*:S"
                    }
            );
        } catch (Throwable ignored) {
        }
    }

    static class SettingsLayoutFactory implements LayoutInflater.Factory2 {

        private final LayoutInflater.Factory2 delegate;

        public SettingsLayoutFactory(LayoutInflater.Factory2 delegate) {
            this.delegate = delegate;
        }

        public View redirectView(String name, Context context, AttributeSet attrs) {
            if (name.equals("CheckBox")) {
                TintCheckBox checkBox = new TintCheckBox(context, attrs);
                int drawableId = Utils.getResId("abc_btn_check_material_anim", "drawable");
                int tintId = Utils.getResId("selector_compoundbutton_normal", "color");
                checkBox.setButtonDrawable(drawableId);
                checkBox.setCompoundButtonTintList(tintId);
                return checkBox;
            } else if (name.equals("RadioButton")) {
                TintRadioButton radioButton = new TintRadioButton(context, attrs);
                int drawableId = Utils.getResId("abc_btn_radio_material_anim", "drawable");
                int tintId = Utils.getResId("selector_radiobutton_preference_tint", "color");
                radioButton.setButtonDrawable(drawableId);
                radioButton.setCompoundButtonTintList(tintId);
                radioButton.setText(null);
                return radioButton;
            } else if (Utils.isHd() && name.equals(SwitchCompat.class.getName())) {
                TintSwitchCompat switchCompat = new TintSwitchCompat(context, attrs);
                Drawable trackDrawable = Utils.getDrawable("abc_switch_track_mtrl_alpha");
                Drawable thumbDrawable = Utils.getDrawable("abc_switch_thumb_material");
                ColorStateList trackTint = Utils.getColorStateList(context, "selector_switch_track");
                ColorStateList thumbTint = Utils.getColorStateList(context, "selector_switch_thumb");
                trackDrawable.setTintMode(PorterDuff.Mode.SRC_IN);
                trackDrawable.setTintList(trackTint);
                thumbDrawable.setTintMode(PorterDuff.Mode.MULTIPLY);
                thumbDrawable.setTintList(thumbTint);
                switchCompat.setTrackDrawable(trackDrawable);
                switchCompat.setThumbDrawable(thumbDrawable);
                return switchCompat;
            } else if (context instanceof Activity activity) {
                return activity.onCreateView(name, context, attrs);
            }
            return null;
        }

        @Nullable
        @Override
        public View onCreateView(@Nullable View parent, @NonNull String name, @NonNull Context context, @NonNull AttributeSet attrs) {
            View view = redirectView(name, context, attrs);
            return view != null ? view : delegate.onCreateView(parent, name, context, attrs);
        }

        @Nullable
        @Override
        public View onCreateView(@NonNull String name, @NonNull Context context, @NonNull AttributeSet attrs) {
            View view = redirectView(name, context, attrs);
            return view != null ? view : delegate.onCreateView(name, context, attrs);
        }
    }

    static class ActivityLifecycleCallback implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
            printLifecycle(activity, "onActivityCreated", true);
            activityRefs.push(new WeakReference<>(activity));
            if (activity instanceof BiliPreferencesActivity
                    || activity instanceof MessageTipItemActivity
                    || (Utils.isHd() && activity instanceof MainActivityV2)) {
                LayoutInflater layoutInflater = activity.getLayoutInflater();
                LayoutInflater.Factory2 factory2 = layoutInflater.getFactory2();
                Reflex.setObjectField(layoutInflater, "mFactory2", new SettingsLayoutFactory(factory2));
            }
        }

        @Override
        public void onActivityDestroyed(@NonNull Activity activity) {
            printLifecycle(activity, "onActivityDestroyed", true);
            var iterator = activityRefs.iterator();
            while (iterator.hasNext()) {
                var activityRef = iterator.next();
                if (activityRef.get() == activity) {
                    iterator.remove();
                    break;
                }
            }
            VideoInfoHolder.removeCache(activity);
        }

        @Override
        public void onActivityStarted(@NonNull Activity activity) {
            printLifecycle(activity, "onActivityStarted", false);
        }

        @Override
        public void onActivityResumed(@NonNull Activity activity) {
            printLifecycle(activity, "onActivityResumed", false);
            if (activity instanceof MainActivityV2)
                VideoInfoHolder.clearCache();
        }

        @Override
        public void onActivityPaused(@NonNull Activity activity) {
            printLifecycle(activity, "onActivityPaused", false);
        }

        @Override
        public void onActivityStopped(@NonNull Activity activity) {
            printLifecycle(activity, "onActivityStopped", false);
        }

        @Override
        public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
            printLifecycle(activity, "onActivitySaveInstanceState", false);
        }

        static void printLifecycle(Activity activity, String name, boolean printIntent) {
            if (!Settings.Debug.get()) return;
            Logger.debug(() -> name + ", activity: " + activity.getClass().getName());
            if (!printIntent) return;
            var extras = activity.getIntent().getExtras();
            if (extras == null) return;
            for (String key : extras.keySet()) {
                var value = extras.get(key);
                Logger.debug(() -> "intent.extra, " + key + ": " + value + ", type: " + (value != null ? value.getClass().getName() : null));
            }
        }
    }

    static class ComponentCallbacks implements android.content.ComponentCallbacks {

        @Override
        public void onConfigurationChanged(@NonNull Configuration newConfig) {
            updateBitmapDefaultDensity();
        }

        @Override
        public void onLowMemory() {
        }
    }
}
