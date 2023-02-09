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
import com.emanuelef.remote_capture.ReversedLinesFileReader;
import com.emanuelef.remote_capture.Utils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class LogviewFragment extends Fragment {
    private static final String TAG = "LogviewFragment";
    public static final int MAX_LINES = 512;
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

        if(Utils.isTv(view.getContext())) {
            // necessary to make scroll work on TV
            // but disables ability to select and copy the textview contents
            ViewGroup layout = view.findViewById(R.id.scrollView);
            layout.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }

        mLogView = view.findViewById(R.id.log);
        reloadLog();
    }

    public void reloadLog() {
        try {
            ReversedLinesFileReader reader = new ReversedLinesFileReader(new File(mLogPath), StandardCharsets.US_ASCII);
            StringBuilder builder = new StringBuilder();
            String line;
            int count = 0;

            while(((line = reader.readLine()) != null) && (count < MAX_LINES)) {
                builder.insert(0, "\n");
                builder.insert(0, line);
                count += 1;
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
