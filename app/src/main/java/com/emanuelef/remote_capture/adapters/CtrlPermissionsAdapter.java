/*
 * This file is part of PCAPdroid.
 *
 * PCAPdroid is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * PCAPdroid is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with PCAPdroid.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright 2020-21 - Emanuele Faranda
 */

package com.emanuelef.remote_capture.adapters;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.ArrayMap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.emanuelef.remote_capture.AppsResolver;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.interfaces.TextAdapter;
import com.emanuelef.remote_capture.model.AppDescriptor;
import com.emanuelef.remote_capture.model.CtrlPermissions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public class CtrlPermissionsAdapter extends ArrayAdapter<CtrlPermissions.Rule> implements TextAdapter {
    private final LayoutInflater mLayoutInflater;
    private final CtrlPermissions mPermissions;
    private final Context mContext;
    private final ArrayMap<String, AppDescriptor> mPkgToApp = new ArrayMap<>();

    public CtrlPermissionsAdapter(Context context, CtrlPermissions perms) {
        super(context, R.layout.rule_item);
        mLayoutInflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mPermissions = perms;
        mContext = context;
        load();
    }

    private void load() {
        PackageManager pm = mContext.getPackageManager();
        Iterator<CtrlPermissions.Rule> it = mPermissions.iterRules();
        List<CtrlPermissions.Rule> sorted = new ArrayList<>();

        while(it.hasNext()) {
            CtrlPermissions.Rule rule = it.next();
            AppDescriptor app = AppsResolver.resolveInstalledApp(pm, rule.package_name, 0);
            if(app != null)
                mPkgToApp.put(rule.package_name, app);

            sorted.add(rule);
        }

        // sort by package name. It would be better to sort them via AppDescriptor.compareTo but
        // some apps may be null.
        Collections.sort(sorted, (o1, o2) -> {
            return o1.package_name.compareTo(o2.package_name);
        });
        addAll(sorted);
    }

    @NonNull
    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        if(convertView == null)
            convertView = mLayoutInflater.inflate(R.layout.rule_item, parent, false);

        CtrlPermissions.Rule rule = getItem(position);
        AppDescriptor app = mPkgToApp.get(rule.package_name); // may be null
        String text = String.format(mContext.getString(R.string.control_permissions_item),
                (app == null) ? rule.package_name : String.format("%s (%s)", app.getName(), app.getPackageName()),
                mContext.getString((rule.consent == CtrlPermissions.ConsentType.ALLOW) ? R.string.allow : R.string.deny));

        ((TextView)convertView.findViewById(R.id.item_label)).setText(text);

        return convertView;
    }

    @Override
    public String getItemText(int pos) {
        return getItem(pos).package_name;
    }
}
