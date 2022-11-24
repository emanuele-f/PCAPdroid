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
 * Copyright 2020-22 - Emanuele Faranda
 */

package com.emanuelef.remote_capture.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.emanuelef.remote_capture.R;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

public class LogviewFragment extends Fragment {
    private static final String TAG = "LogviewFragment";
    private String mLogPath;
    private String mLogText;
    private TextView mLogView;

    public static LogviewFragment newInstance(String path) {
        LogviewFragment fragment = new LogviewFragment();
        Bundle args = new Bundle();
        args.putSerializable("path", path);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public View onCreateView(LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.logview_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Bundle args = getArguments();
        assert args != null;
        mLogPath = args.getString("path");
        assert(mLogPath != null);

        mLogView = view.findViewById(R.id.log);
        reloadLog();
    }

    public void reloadLog() {
        try {
            BufferedReader reader = new BufferedReader(new FileReader(mLogPath));

            StringBuilder builder = new StringBuilder();
            String line;

            while((line = reader.readLine()) != null) {
                builder.append(line);
                builder.append("\n");
            }

            mLogText = builder.toString();
        } catch (IOException e) {
            e.printStackTrace();
            mLogText = "";
        }

        mLogView.setText(!mLogText.isEmpty() ? mLogText : getString(R.string.no_data));
    }

    public String getLog() {
        return mLogText;
    }
}
