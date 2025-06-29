/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.launcher3.provider;

import static android.os.Process.myUserHandle;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.launcher3.LauncherPrefs.APP_WIDGET_IDS;
import static com.android.launcher3.LauncherPrefs.OLD_APP_WIDGET_IDS;
import static com.android.launcher3.LauncherPrefs.RESTORE_DEVICE;
import static com.android.launcher3.LauncherSettings.Favorites.CONTAINER_DESKTOP;
import static com.android.launcher3.LauncherSettings.Favorites.ITEM_TYPE_APPLICATION;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.backup.BackupManager;
import android.appwidget.AppWidgetHost;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.UserHandle;
import android.os.UserManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.launcher3.LauncherAppState;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.LauncherSettings;
import com.android.launcher3.LauncherSettings.Favorites;
import com.android.launcher3.backuprestore.LauncherRestoreEventLogger;
import com.android.launcher3.dagger.LauncherAppComponent;
import com.android.launcher3.dagger.LauncherAppSingleton;
import com.android.launcher3.model.ModelDbController;
import com.android.launcher3.pm.UserCache;
import com.android.launcher3.util.AllModulesForTest;
import com.android.launcher3.util.IntArray;
import com.android.launcher3.util.LauncherModelHelper;
import com.android.launcher3.util.LauncherModelHelper.SandboxModelContext;

import dagger.BindsInstance;
import dagger.Component;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * Tests for {@link RestoreDbTask}
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class RestoreDbTaskTest {

    private static final int PER_USER_RANGE = 200000;

    private final UserHandle mWorkUser = UserHandle.getUserHandleForUid(PER_USER_RANGE);

    private LauncherModelHelper mModelHelper;
    private SandboxModelContext mContext;
    private UserCache mUserCacheSpy;

    private RestoreDbTask mTask;
    private ModelDbController mMockController;
    private SQLiteDatabase mMockDb;
    private Cursor mMockCursor;
    private LauncherPrefs mPrefs;
    private LauncherRestoreEventLogger mMockRestoreEventLogger;
    private SQLiteDatabase mDb;

    private AppWidgetHost mWidgetHost;

    @Before
    public void setup() {
        mModelHelper = new LauncherModelHelper();
        mContext = mModelHelper.sandboxContext;
        mUserCacheSpy = spy(UserCache.getInstance(getInstrumentation().getTargetContext()));

        mContext.initDaggerComponent(
                DaggerRestoreDbTaskTest_TestComponent.builder().bindUserCache(mUserCacheSpy));

        mTask = new RestoreDbTask();
        mMockController = Mockito.mock(ModelDbController.class);
        mMockDb = mock(SQLiteDatabase.class);
        mMockCursor = mock(Cursor.class);
        mPrefs = LauncherPrefs.get(mContext);
        mMockRestoreEventLogger = mock(LauncherRestoreEventLogger.class);
    }

    private synchronized AppWidgetHost getWidgetHostLazy() {
        if (mWidgetHost == null) {
            mWidgetHost = new AppWidgetHost(mContext, 1012);
        }
        return mWidgetHost;
    }

    @After
    public void teardown() {
        if (mDb != null) {
            mDb.close();
        }
        if (mWidgetHost != null) {
            mWidgetHost.deleteHost();
        }
        mModelHelper.destroy();
        LauncherPrefs.get(mContext).removeSync(RESTORE_DEVICE);
    }

    @Test
    public void testGetProfileId() throws Exception {
        mDb = getModelDbController(23).getDb();
        assertEquals(23, new RestoreDbTask().getDefaultProfileId(mDb));
    }

    @Test
    public void testMigrateProfileId() throws Exception {
        mDb = getModelDbController(42).getDb();
        // Add some mock data
        for (int i = 0; i < 5; i++) {
            ContentValues values = new ContentValues();
            values.put(Favorites._ID, i);
            values.put(Favorites.TITLE, "item " + i);
            mDb.insert(Favorites.TABLE_NAME, null, values);
        }
        // Verify item add
        assertEquals(5, getCount(mDb, "select * from favorites where profileId = 42"));

        new RestoreDbTask().migrateProfileId(mDb, 42, 33);

        // verify data migrated
        assertEquals(0, getCount(mDb, "select * from favorites where profileId = 42"));
        assertEquals(5, getCount(mDb, "select * from favorites where profileId = 33"));
    }

    @Test
    public void testChangeDefaultColumn() throws Exception {
        mDb = getModelDbController(42).getDb();
        // Add some mock data
        for (int i = 0; i < 5; i++) {
            ContentValues values = new ContentValues();
            values.put(Favorites._ID, i);
            values.put(Favorites.TITLE, "item " + i);
            mDb.insert(Favorites.TABLE_NAME, null, values);
        }
        // Verify default column is 42
        assertEquals(5, getCount(mDb, "select * from favorites where profileId = 42"));

        new RestoreDbTask().changeDefaultColumn(mDb, 33);

        // Verify default value changed
        ContentValues values = new ContentValues();
        values.put(Favorites._ID, 100);
        values.put(Favorites.TITLE, "item 100");
        mDb.insert(Favorites.TABLE_NAME, null, values);
        assertEquals(1, getCount(mDb, "select * from favorites where profileId = 33"));
    }

    @Test
    public void testSanitizeDB_bothProfiles() throws Exception {
        UserHandle myUser = myUserHandle();
        long myProfileId = mContext.getSystemService(UserManager.class)
                .getSerialNumberForUser(myUser);
        long myProfileId_old = myProfileId + 1;
        long workProfileId = myProfileId + 2;
        long workProfileId_old = myProfileId + 3;

        ModelDbController controller = getModelDbController(myProfileId);
        mDb = controller.getDb();
        BackupManager bm = spy(new BackupManager(mContext));
        doReturn(myUserHandle()).when(bm).getUserForAncestralSerialNumber(eq(myProfileId_old));
        doReturn(mWorkUser).when(bm).getUserForAncestralSerialNumber(eq(workProfileId_old));
        doReturn(workProfileId).when(mUserCacheSpy).getSerialNumberForUser(mWorkUser);

        addIconsBulk(controller, 10, 1, myProfileId_old);
        addIconsBulk(controller, 6, 2, workProfileId_old);
        assertEquals(10, getItemCountForProfile(mDb, myProfileId_old));
        assertEquals(6, getItemCountForProfile(mDb, workProfileId_old));

        mTask.sanitizeDB(mContext, controller, controller.getDb(), bm, mMockRestoreEventLogger);

        // All the data has been migrated to the new user ids
        assertEquals(0, getItemCountForProfile(mDb, myProfileId_old));
        assertEquals(0, getItemCountForProfile(mDb, workProfileId_old));
        assertEquals(10, getItemCountForProfile(mDb, myProfileId));
        assertEquals(6, getItemCountForProfile(mDb, workProfileId));
    }

    @Test
    public void testSanitizeDB_workItemsRemoved() throws Exception {
        UserHandle myUser = myUserHandle();
        long myProfileId = mContext.getSystemService(UserManager.class)
                .getSerialNumberForUser(myUser);
        long myProfileId_old = myProfileId + 1;
        long workProfileId_old = myProfileId + 3;

        ModelDbController controller = getModelDbController(myProfileId);
        mDb = controller.getDb();
        BackupManager bm = spy(new BackupManager(mContext));
        doReturn(myUserHandle()).when(bm).getUserForAncestralSerialNumber(eq(myProfileId_old));
        // Work profile is not migrated
        doReturn(null).when(bm).getUserForAncestralSerialNumber(eq(workProfileId_old));

        addIconsBulk(controller, 10, 1, myProfileId_old);
        addIconsBulk(controller, 6, 2, workProfileId_old);
        assertEquals(10, getItemCountForProfile(mDb, myProfileId_old));
        assertEquals(6, getItemCountForProfile(mDb, workProfileId_old));

        mTask.sanitizeDB(mContext, controller, controller.getDb(), bm, mMockRestoreEventLogger);

        // All the data has been migrated to the new user ids
        assertEquals(0, getItemCountForProfile(mDb, myProfileId_old));
        assertEquals(0, getItemCountForProfile(mDb, workProfileId_old));
        assertEquals(10, getItemCountForProfile(mDb, myProfileId));
        assertEquals(10, getCount(mDb, "select * from favorites"));
    }

    @Test
    public void givenLauncherPrefsHasNoIds_whenRestoreAppWidgetIdsIfExists_thenIdsAreRemoved() {
        // When
        mTask.restoreAppWidgetIdsIfExists(mContext, mMockController, mMockRestoreEventLogger,
                this::getWidgetHostLazy);
        // Then
        assertThat(mPrefs.has(OLD_APP_WIDGET_IDS, APP_WIDGET_IDS)).isFalse();
    }

    @Test
    public void givenNoPendingRestore_WhenRestoreAppWidgetIds_ThenRemoveNewWidgetIds() {
        // Given
        AppWidgetHost expectedHost = getWidgetHostLazy();
        int[] expectedOldIds = generateOldWidgetIds(expectedHost);
        int[] expectedNewIds = generateNewWidgetIds(expectedHost, expectedOldIds);
        when(mMockController.getDb()).thenReturn(mMockDb);
        mPrefs.remove(RESTORE_DEVICE);

        // When
        setRestoredAppWidgetIds(mContext, expectedOldIds, expectedNewIds);
        mTask.restoreAppWidgetIdsIfExists(mContext, mMockController, mMockRestoreEventLogger,
                this::getWidgetHostLazy);

        // Then
        assertThat(expectedHost.getAppWidgetIds()).isEqualTo(expectedOldIds);
        assertThat(mPrefs.has(OLD_APP_WIDGET_IDS, APP_WIDGET_IDS)).isFalse();
        // b/343530737
        verifyNoMoreInteractions(mMockController);
    }

    @Test
    public void givenRestoreWithNonExistingWidgets_WhenRestoreAppWidgetIds_ThenRemoveNewIds() {
        // Given
        AppWidgetHost expectedHost = getWidgetHostLazy();
        int[] expectedOldIds = generateOldWidgetIds(expectedHost);
        int[] expectedNewIds = generateNewWidgetIds(expectedHost, expectedOldIds);
        when(mMockController.getDb()).thenReturn(mMockDb);
        when(mMockDb.query(any(), any(), any(), any(), any(), any(), any())).thenReturn(
                mMockCursor);
        when(mMockCursor.moveToFirst()).thenReturn(false);
        RestoreDbTask.setPending(mContext);

        // When
        setRestoredAppWidgetIds(mContext, expectedOldIds, expectedNewIds);
        mTask.restoreAppWidgetIdsIfExists(mContext, mMockController, mMockRestoreEventLogger,
                this::getWidgetHostLazy);

        // Then
        assertThat(expectedHost.getAppWidgetIds()).isEqualTo(expectedOldIds);
        assertThat(mPrefs.has(OLD_APP_WIDGET_IDS, APP_WIDGET_IDS)).isFalse();
        verify(mMockController, times(expectedOldIds.length)).update(any(), any(), any());
    }

    @Test
    public void givenRestore_WhenRestoreAppWidgetIds_ThenAddNewIds() {
        // Given
        AppWidgetHost expectedHost = getWidgetHostLazy();
        int[] expectedOldIds = generateOldWidgetIds(expectedHost);
        int[] expectedNewIds = generateNewWidgetIds(expectedHost, expectedOldIds);
        int[] allExpectedIds = IntStream.concat(
                Arrays.stream(expectedOldIds),
                Arrays.stream(expectedNewIds)
        ).toArray();

        when(mMockController.getDb()).thenReturn(mMockDb);
        when(mMockDb.query(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mMockCursor);
        when(mMockCursor.moveToFirst()).thenReturn(true);
        when(mMockCursor.getColumnNames()).thenReturn(new String[] {});
        when(mMockCursor.isAfterLast()).thenReturn(true);
        RestoreDbTask.setPending(mContext);

        // When
        setRestoredAppWidgetIds(mContext, expectedOldIds, expectedNewIds);
        mTask.restoreAppWidgetIdsIfExists(mContext, mMockController, mMockRestoreEventLogger,
                this::getWidgetHostLazy);

        // Then
        assertThat(expectedHost.getAppWidgetIds()).isEqualTo(allExpectedIds);
        assertThat(mPrefs.has(OLD_APP_WIDGET_IDS, APP_WIDGET_IDS)).isFalse();
        verify(mMockController, times(expectedOldIds.length)).update(any(), any(), any());
    }

    private void addIconsBulk(ModelDbController controller,
            int count, int screen, long profileId) {
        int columns = LauncherAppState.getIDP(mContext).numColumns;
        String packageName = getInstrumentation().getContext().getPackageName();
        for (int i = 0; i < count; i++) {
            ContentValues values = new ContentValues();
            values.put(LauncherSettings.Favorites._ID, controller.generateNewItemId());
            values.put(LauncherSettings.Favorites.CONTAINER, CONTAINER_DESKTOP);
            values.put(LauncherSettings.Favorites.SCREEN, screen);
            values.put(LauncherSettings.Favorites.CELLX, i % columns);
            values.put(LauncherSettings.Favorites.CELLY, i / columns);
            values.put(LauncherSettings.Favorites.SPANX, 1);
            values.put(LauncherSettings.Favorites.SPANY, 1);
            values.put(LauncherSettings.Favorites.PROFILE_ID, profileId);
            values.put(LauncherSettings.Favorites.ITEM_TYPE, ITEM_TYPE_APPLICATION);
            values.put(LauncherSettings.Favorites.INTENT,
                    new Intent(Intent.ACTION_MAIN).setPackage(packageName).toUri(0));

            controller.insert(values);
        }
    }

    @Test
    public void testRemoveScreenIdGaps_firstScreenEmpty() {
        runRemoveScreenIdGapsTest(
                new int[]{1, 2, 5, 6, 6, 7, 9, 9},
                new int[]{1, 2, 3, 4, 4, 5, 6, 6});
    }

    @Test
    public void testRemoveScreenIdGaps_firstScreenOccupied() {
        runRemoveScreenIdGapsTest(
                new int[]{0, 2, 5, 6, 6, 7, 9, 9},
                new int[]{0, 1, 2, 3, 3, 4, 5, 5});
    }

    @Test
    public void testRemoveScreenIdGaps_noGap() {
        runRemoveScreenIdGapsTest(
                new int[]{0, 1, 1, 2, 3, 3, 4, 5},
                new int[]{0, 1, 1, 2, 3, 3, 4, 5});
    }

    private void runRemoveScreenIdGapsTest(int[] screenIds, int[] expectedScreenIds) {
        mDb = getModelDbController(42).getDb();
        // Add some mock data
        for (int i = 0; i < screenIds.length; i++) {
            ContentValues values = new ContentValues();
            values.put(Favorites._ID, i);
            values.put(Favorites.SCREEN, screenIds[i]);
            values.put(Favorites.CONTAINER, CONTAINER_DESKTOP);
            mDb.insert(Favorites.TABLE_NAME, null, values);
        }
        // Verify items are added
        assertEquals(screenIds.length,
                getCount(mDb, "select * from favorites where container = -100"));

        new RestoreDbTask().removeScreenIdGaps(mDb);

        // verify screenId gaps removed
        int[] resultScreenIds = new int[screenIds.length];
        try (Cursor c = mDb.rawQuery(
                "select screen from favorites where container = -100 order by screen", null)) {
            int i = 0;
            while (c.moveToNext()) {
                resultScreenIds[i++] = c.getInt(0);
            }
        }

        assertArrayEquals(expectedScreenIds, resultScreenIds);
    }

    public int getItemCountForProfile(SQLiteDatabase db, long profileId) {
        return getCount(db, "select * from favorites where profileId = " + profileId);
    }

    private int getCount(SQLiteDatabase db, String sql) {
        try (Cursor c = db.rawQuery(sql, null)) {
            return c.getCount();
        }
    }

    private int[] generateOldWidgetIds(AppWidgetHost host) {
        // generate some widget ids in case there are none
        host.allocateAppWidgetId();
        host.allocateAppWidgetId();
        return host.getAppWidgetIds();
    }

    private int[] generateNewWidgetIds(AppWidgetHost host, int[] oldWidgetIds) {
        // map as many new ids as old ids
        return Arrays.stream(oldWidgetIds)
                .map(id -> host.allocateAppWidgetId()).toArray();
    }

    private void setRestoredAppWidgetIds(Context context, int[] oldIds, int[] newIds) {
        LauncherPrefs.get(context).putSync(
                OLD_APP_WIDGET_IDS.to(IntArray.wrap(oldIds).toConcatString()),
                APP_WIDGET_IDS.to(IntArray.wrap(newIds).toConcatString()));
    }

    private ModelDbController getModelDbController(long profileId) {
        doReturn(profileId).when(mUserCacheSpy).getSerialNumberForUser(myUserHandle());
        return ((TestComponent) mContext.getAppComponent()).getDbController();
    }

    @LauncherAppSingleton
    @Component(modules = AllModulesForTest.class)
    public interface TestComponent extends LauncherAppComponent {

        ModelDbController getDbController();

        @Component.Builder
        interface Builder extends LauncherAppComponent.Builder {

            @BindsInstance Builder bindUserCache(UserCache userCache);

            TestComponent build();
        }
    }
}
