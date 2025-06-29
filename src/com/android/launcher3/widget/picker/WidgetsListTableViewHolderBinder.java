/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.launcher3.widget.picker;

import static com.android.launcher3.widget.picker.WidgetsListItemAnimator.WIDGET_LIST_ITEM_APPEARANCE_START_DELAY;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;
import android.util.Pair;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;
import android.view.ViewGroup;
import android.widget.TableLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Px;

import com.android.launcher3.R;
import com.android.launcher3.model.WidgetItem;
import com.android.launcher3.recyclerview.ViewHolderBinder;
import com.android.launcher3.views.ActivityContext;
import com.android.launcher3.widget.WidgetCell;
import com.android.launcher3.widget.WidgetTableRow;
import com.android.launcher3.widget.model.WidgetsListContentEntry;
import com.android.launcher3.widget.util.WidgetsTableUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Binds data from {@link WidgetsListContentEntry} to UI elements in {@link WidgetsRowViewHolder}.
 */
public final class WidgetsListTableViewHolderBinder
        implements ViewHolderBinder<WidgetsListContentEntry, WidgetsRowViewHolder> {
    private static final boolean DEBUG = false;
    private static final String TAG = "WidgetsListRowViewHolderBinder";

    private final LayoutInflater mLayoutInflater;
    private final OnClickListener mIconClickListener;
    private @NonNull final Context mContext;
    private @NonNull final ActivityContext mActivityContext;
    @Px private final int mCellPadding;
    private final OnLongClickListener mIconLongClickListener;

    public WidgetsListTableViewHolderBinder(
            @NonNull Context context,
            LayoutInflater layoutInflater,
            OnClickListener iconClickListener,
            OnLongClickListener iconLongClickListener) {
        mLayoutInflater = layoutInflater;
        mContext = context;
        mActivityContext = ActivityContext.lookupContext(context);
        mCellPadding = context.getResources().getDimensionPixelSize(
                R.dimen.widget_cell_horizontal_padding);
        mIconClickListener = iconClickListener;
        mIconLongClickListener = iconLongClickListener;
    }

    @Override
    public WidgetsRowViewHolder newViewHolder(ViewGroup parent) {
        if (DEBUG) {
            Log.v(TAG, "\nonCreateViewHolder");
        }

        return new WidgetsRowViewHolder(mLayoutInflater.inflate(
                        R.layout.widgets_table_container, parent, false));
    }

    @Override
    public void bindViewHolder(WidgetsRowViewHolder holder, WidgetsListContentEntry entry,
            @ListPosition int position, List<Object> payloads) {
        for (Object payload : payloads) {
            Pair<WidgetItem, Bitmap> pair = (Pair) payload;
            holder.previewCache.put(pair.first, pair.second);
        }

        WidgetsListTableView table = holder.tableContainer;
        if (DEBUG) {
            Log.d(TAG, String.format("onBindViewHolder [widget#=%d, table.getChildCount=%d]",
                    entry.mWidgets.size(), table.getChildCount()));
        }
        table.setListDrawableState(
                WidgetsListDrawableState.obtain(
                        (position & POSITION_FIRST) != 0,
                        (position & POSITION_LAST) != 0));

        List<ArrayList<WidgetItem>> widgetItemsTable =
                WidgetsTableUtils.groupWidgetItemsUsingRowPxWithReordering(entry.mWidgets,
                        mContext,
                        mActivityContext.getDeviceProfile(),
                        entry.getMaxSpanSize(),
                        mCellPadding);
        recycleTableBeforeBinding(table, widgetItemsTable);

        // Bind the widget items.
        for (int i = 0; i < widgetItemsTable.size(); i++) {
            List<WidgetItem> widgetItemsPerRow = widgetItemsTable.get(i);
            WidgetTableRow row = (WidgetTableRow) table.getChildAt(i);

            if (areRowItemsUnchanged(row, widgetItemsPerRow)) {  // Just show widgets in row as is
                row.setVisibility(View.VISIBLE);
                for (int j = 0; j < widgetItemsPerRow.size(); j++) {
                    WidgetCell widget = (WidgetCell) row.getChildAt(j);
                    widget.setVisibility(View.VISIBLE);
                }
            } else {
                for (int j = 0; j < widgetItemsPerRow.size(); j++) {
                    row.setVisibility(View.VISIBLE);
                    WidgetCell widget = (WidgetCell) row.getChildAt(j);
                    widget.clear();
                    WidgetItem widgetItem = widgetItemsPerRow.get(j);
                    widget.addPreviewReadyListener(row);
                    widget.setVisibility(View.VISIBLE);

                    widget.applyFromCellItem(widgetItem);
                    widget.requestLayout();
                }
            }
        }
    }

    private boolean areRowItemsUnchanged(WidgetTableRow row, List<WidgetItem> widgetItemsPerRow) {
        // NOTE: on rotation or fold / unfold, we bind different view holders
        // so, we don't any special handling for that case.
        if (row.getChildCount() != widgetItemsPerRow.size()) { // Items not equal
            return false;
        }

        for (int j = 0; j < widgetItemsPerRow.size(); j++) {
            WidgetCell widgetCell = (WidgetCell) row.getChildAt(j);
            WidgetItem widgetItem = widgetItemsPerRow.get(j);
            if (widgetCell.getWidgetItem() == null
                    || !widgetCell.getWidgetItem().equals(widgetItem)) {
                return false; // Items at given position in row aren't same.
            }
        }
        return true;
    }

    /**
     * Adds and hides table rows and columns from {@code table} to ensure there is sufficient room
     * to display {@code widgetItemsTable}.
     *
     * <p>Instead of recreating all UI elements in {@code table}, this function recycles all
     * existing UI elements. Instead of deleting excessive elements, it hides them.
     */
    private void recycleTableBeforeBinding(TableLayout table,
            List<ArrayList<WidgetItem>> widgetItemsTable) {
        // Hide extra table rows.
        for (int i = widgetItemsTable.size(); i < table.getChildCount(); i++) {
            table.getChildAt(i).setVisibility(View.GONE);
        }

        for (int i = 0; i < widgetItemsTable.size(); i++) {
            List<WidgetItem> widgetItems = widgetItemsTable.get(i);
            WidgetTableRow tableRow;
            if (i < table.getChildCount()) {
                tableRow = (WidgetTableRow) table.getChildAt(i);
            } else {
                tableRow = new WidgetTableRow(table.getContext());
                tableRow.setGravity(Gravity.TOP);
                table.addView(tableRow);
            }

            // If the row items are unchanged, we don't need to re-setup the row or the items;
            // we can just show the row as is.
            if (!areRowItemsUnchanged(tableRow, widgetItems)) {
                // Pass resize delay to let the "move" and "change" animations run before resizing
                // the row.
                tableRow.setupRow(widgetItems.size(),
                        /*resizeDelayMs=*/ WIDGET_LIST_ITEM_APPEARANCE_START_DELAY);
                if (tableRow.getChildCount() > widgetItems.size()) {
                    for (int j = widgetItems.size(); j < tableRow.getChildCount(); j++) {
                        tableRow.getChildAt(j).setVisibility(View.GONE);
                    }
                } else {
                    for (int j = tableRow.getChildCount(); j < widgetItems.size(); j++) {
                        WidgetCell widget = (WidgetCell) mLayoutInflater.inflate(
                                R.layout.widget_cell, tableRow, false);
                        // set up touch.
                        widget.setOnClickListener(mIconClickListener);
                        widget.addPreviewReadyListener(tableRow);
                        View preview = widget.findViewById(R.id.widget_preview_container);
                        preview.setOnClickListener(mIconClickListener);
                        preview.setOnLongClickListener(mIconLongClickListener);
                        widget.setAnimatePreview(false);
                        tableRow.addView(widget);
                    }
                }
            }
        }
    }

    @Override
    public void unbindViewHolder(WidgetsRowViewHolder holder) {
        int numOfRows = holder.tableContainer.getChildCount();
        holder.previewCache.clear();
        for (int i = 0; i < numOfRows; i++) {
            WidgetTableRow tableRow = (WidgetTableRow) holder.tableContainer.getChildAt(i);
            int numOfCols = tableRow.getChildCount();
            for (int j = 0; j < numOfCols; j++) {
                WidgetCell widget = (WidgetCell) tableRow.getChildAt(j);
                widget.clear();
            }
        }
    }
}
