/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.quickstep.logging;

import static com.android.launcher3.LauncherPrefs.getDevicePrefs;
import static com.android.launcher3.LauncherPrefs.getPrefs;
import static com.android.launcher3.graphics.ThemeManager.KEY_THEMED_ICONS;
import static com.android.launcher3.graphics.ThemeManager.THEMED_ICONS;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_HOME_SCREEN_SUGGESTIONS_DISABLED;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_HOME_SCREEN_SUGGESTIONS_ENABLED;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_NOTIFICATION_DOT_DISABLED;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_NOTIFICATION_DOT_ENABLED;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_THEMED_ICON_DISABLED;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_THEMED_ICON_ENABLED;
import static com.android.launcher3.model.DeviceGridState.KEY_WORKSPACE_SIZE;
import static com.android.launcher3.model.PredictionUpdateTask.LAST_PREDICTION_ENABLED;
import static com.android.launcher3.util.DisplayController.CHANGE_NAVIGATION_MODE;
import static com.android.launcher3.util.SettingsCache.NOTIFICATION_BADGING_URI;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.res.TypedArray;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Xml;

import androidx.annotation.VisibleForTesting;

import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.R;
import com.android.launcher3.dagger.ApplicationContext;
import com.android.launcher3.dagger.LauncherAppSingleton;
import com.android.launcher3.logging.InstanceId;
import com.android.launcher3.logging.StatsLogManager;
import com.android.launcher3.logging.StatsLogManager.StatsLogger;
import com.android.launcher3.model.DeviceGridState;
import com.android.launcher3.util.DaggerSingletonObject;
import com.android.launcher3.util.DaggerSingletonTracker;
import com.android.launcher3.util.DisplayController;
import com.android.launcher3.util.DisplayController.Info;
import com.android.launcher3.util.NavigationMode;
import com.android.launcher3.util.SettingsCache;
import com.android.quickstep.dagger.QuickstepBaseAppComponent;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.Optional;

import javax.inject.Inject;

/**
 * Utility class to log launcher settings changes
 */
@LauncherAppSingleton
public class SettingsChangeLogger implements
        DisplayController.DisplayInfoChangeListener, OnSharedPreferenceChangeListener {

    /**
     * Singleton instance
     */
    public static DaggerSingletonObject<SettingsChangeLogger> INSTANCE =
            new DaggerSingletonObject<>(QuickstepBaseAppComponent::getSettingsChangeLogger);

    private static final String TAG = "SettingsChangeLogger";
    private static final String BOOLEAN_PREF = "SwitchPreference";

    private final Context mContext;
    private final ArrayMap<String, LoggablePref> mLoggablePrefs;
    private final StatsLogManager mStatsLogManager;

    private NavigationMode mNavMode;
    private StatsLogManager.LauncherEvent mNotificationDotsEvent;
    private StatsLogManager.LauncherEvent mHomeScreenSuggestionEvent;

    private final SettingsCache.OnChangeListener mListener = this::onNotificationDotsChanged;

    @Inject
    SettingsChangeLogger(@ApplicationContext Context context,
            DaggerSingletonTracker tracker,
            DisplayController displayController,
            SettingsCache settingsCache) {
        this(context, StatsLogManager.newInstance(context), tracker, displayController,
                settingsCache);
    }

    @VisibleForTesting
    SettingsChangeLogger(@ApplicationContext Context context,
            StatsLogManager statsLogManager,
            DaggerSingletonTracker tracker,
            DisplayController displayController,
            SettingsCache settingsCache) {
        mContext = context;
        mStatsLogManager = statsLogManager;
        mLoggablePrefs = loadPrefKeys(context);

        displayController.addChangeListener(this);
        mNavMode = displayController.getInfo().getNavigationMode();
        tracker.addCloseable(() -> displayController.removeChangeListener(this));

        getPrefs(context).registerOnSharedPreferenceChangeListener(this);
        getDevicePrefs(context).registerOnSharedPreferenceChangeListener(this);
        tracker.addCloseable(() -> {
            getPrefs(mContext).unregisterOnSharedPreferenceChangeListener(this);
            getDevicePrefs(mContext).unregisterOnSharedPreferenceChangeListener(this);
        });

        settingsCache.register(NOTIFICATION_BADGING_URI, mListener);
        onNotificationDotsChanged(settingsCache.getValue(NOTIFICATION_BADGING_URI));
        tracker.addCloseable(() -> {
            settingsCache.unregister(NOTIFICATION_BADGING_URI, mListener);
        });
    }

    private static ArrayMap<String, LoggablePref> loadPrefKeys(Context context) {
        XmlPullParser parser = context.getResources().getXml(R.xml.launcher_preferences);
        ArrayMap<String, LoggablePref> result = new ArrayMap<>();

        try {
            // Move cursor to first tag because it could be
            // androidx.preference.PreferenceScreen or PreferenceScreen
            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.START_TAG
                    && eventType != XmlPullParser.END_DOCUMENT) {
                eventType = parser.next();
            }
            final int depth = parser.getDepth();
            int type;
            while (((type = parser.next()) != XmlPullParser.END_TAG
                    || parser.getDepth() > depth) && type != XmlPullParser.END_DOCUMENT) {
                if (type != XmlPullParser.START_TAG) {
                    continue;
                }
                if (BOOLEAN_PREF.equals(parser.getName())) {
                    TypedArray a = context.obtainStyledAttributes(
                            Xml.asAttributeSet(parser), R.styleable.LoggablePref);
                    String key = a.getString(R.styleable.LoggablePref_android_key);
                    LoggablePref pref = new LoggablePref();
                    pref.defaultValue =
                            a.getBoolean(R.styleable.LoggablePref_android_defaultValue, true);
                    pref.eventIdOn = a.getInt(R.styleable.LoggablePref_logIdOn, 0);
                    pref.eventIdOff = a.getInt(R.styleable.LoggablePref_logIdOff, 0);
                    if (pref.eventIdOff > 0 && pref.eventIdOn > 0) {
                        result.put(key, pref);
                    }
                }
            }
        } catch (XmlPullParserException | IOException e) {
            Log.e(TAG, "Error parsing preference xml", e);
        }
        return result;
    }

    private void onNotificationDotsChanged(boolean isDotsEnabled) {
        StatsLogManager.LauncherEvent mEvent =
                isDotsEnabled ? LAUNCHER_NOTIFICATION_DOT_ENABLED
                        : LAUNCHER_NOTIFICATION_DOT_DISABLED;

        // Log only when the setting is actually changed and not during initialization.
        if (mNotificationDotsEvent != null && mNotificationDotsEvent != mEvent) {
            mStatsLogManager.logger().log(mNotificationDotsEvent);
        }
        mNotificationDotsEvent = mEvent;
    }

    @Override
    public void onDisplayInfoChanged(Context context, Info info, int flags) {
        if ((flags & CHANGE_NAVIGATION_MODE) != 0) {
            mNavMode = info.getNavigationMode();
            mStatsLogManager.logger().log(mNavMode.launcherEvent);
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (LAST_PREDICTION_ENABLED.getSharedPrefKey().equals(key)
                || KEY_WORKSPACE_SIZE.equals(key)
                || KEY_THEMED_ICONS.equals(key)
                || mLoggablePrefs.containsKey(key)) {

            mHomeScreenSuggestionEvent = LauncherPrefs.get(mContext).get(LAST_PREDICTION_ENABLED)
                    ? LAUNCHER_HOME_SCREEN_SUGGESTIONS_ENABLED
                    : LAUNCHER_HOME_SCREEN_SUGGESTIONS_DISABLED;

            mStatsLogManager.logger().log(mHomeScreenSuggestionEvent);
        }
    }

    /**
     * Takes snapshot of all eligible launcher settings and log them with the provided instance ID.
     */
    public void logSnapshot(InstanceId snapshotInstanceId) {
        StatsLogger logger = mStatsLogManager.logger().withInstanceId(snapshotInstanceId);

        Optional.ofNullable(mNotificationDotsEvent).ifPresent(logger::log);
        Optional.ofNullable(mNavMode).map(mode -> mode.launcherEvent).ifPresent(logger::log);
        Optional.ofNullable(mHomeScreenSuggestionEvent).ifPresent(logger::log);
        Optional.ofNullable(new DeviceGridState(mContext).getWorkspaceSizeEvent()).ifPresent(
                logger::log);

        SharedPreferences prefs = getPrefs(mContext);
        logger.log(LauncherPrefs.get(mContext).get(THEMED_ICONS)
                ? LAUNCHER_THEMED_ICON_ENABLED
                : LAUNCHER_THEMED_ICON_DISABLED);

        mLoggablePrefs.forEach((key, lp) -> logger.log(() ->
                prefs.getBoolean(key, lp.defaultValue) ? lp.eventIdOn : lp.eventIdOff));
    }

    @VisibleForTesting
    ArrayMap<String, LoggablePref> getLoggingPrefs() {
        return mLoggablePrefs;
    }

    @VisibleForTesting
    static class LoggablePref {
        public boolean defaultValue;
        public int eventIdOn;
        public int eventIdOff;
    }
}
