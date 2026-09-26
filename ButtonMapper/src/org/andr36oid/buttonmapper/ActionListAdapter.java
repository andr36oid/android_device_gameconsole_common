package org.andr36oid.buttonmapper;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Single choice list of actions, with group headers the D-pad skips over and commands that
 * aren't choices.
 */
final class ActionListAdapter extends BaseAdapter {
    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ACTION = 1;
    private static final int TYPE_COMMAND = 2;

    private final LayoutInflater mInflater;
    private final List<String> mTitles = new ArrayList<>();
    // Action of each row, null for headers.
    private final List<String> mValues = new ArrayList<>();
    private final List<Integer> mTypes = new ArrayList<>();

    ActionListAdapter(Context context) {
        mInflater = LayoutInflater.from(context);
    }

    void addHeader(String title) {
        add(TYPE_HEADER, title, null);
    }

    void addAction(String title, String value) {
        add(TYPE_ACTION, title, value);
    }

    void addCommand(String title, String value) {
        add(TYPE_COMMAND, title, value);
    }

    private void add(int type, String title, String value) {
        mTypes.add(type);
        mTitles.add(title);
        mValues.add(value);
    }

    String getValue(int position) {
        return mValues.get(position);
    }

    /** Returns the first row at or after {@code start} with the action, or -1. */
    int findPosition(String value, int start) {
        for (int i = start; i < mValues.size(); i++) {
            if (value.equals(mValues.get(i))) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public int getCount() {
        return mTitles.size();
    }

    @Override
    public String getItem(int position) {
        return mTitles.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public int getViewTypeCount() {
        return 3;
    }

    @Override
    public int getItemViewType(int position) {
        return mTypes.get(position);
    }

    @Override
    public boolean areAllItemsEnabled() {
        return false;
    }

    @Override
    public boolean isEnabled(int position) {
        return mValues.get(position) != null;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        TextView view = (TextView) convertView;
        if (view == null) {
            final int type = getItemViewType(position);
            view = (TextView) mInflater.inflate(type == TYPE_HEADER ? R.layout.action_list_header
                    : type == TYPE_COMMAND ? R.layout.action_list_command
                    : R.layout.action_list_item, parent, false);
        }
        view.setText(mTitles.get(position));
        return view;
    }
}
