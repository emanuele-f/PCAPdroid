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
 * Copyright 2020-26 - Emanuele Faranda
 */

package com.emanuelef.remote_capture;

import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import com.emanuelef.remote_capture.model.AppDescriptor;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AppIconLoader {
    private static final ExecutorService sExecutor = Executors.newSingleThreadExecutor();
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    public static void setIcon(ImageView imageView, @Nullable AppDescriptor app, @Nullable Drawable placeholder) {
        if (app == null) {
            imageView.setImageDrawable(placeholder);
            imageView.setTag(null);
            return;
        }

        Drawable cached = app.getCachedIcon();
        if (cached != null) {
            imageView.setImageDrawable(cached);
            imageView.setTag(null);
            return;
        }

        imageView.setImageDrawable(placeholder);
        String pkgName = app.getPackageName();
        imageView.setTag(pkgName);

        sExecutor.execute(() -> {
            Drawable icon = app.loadIcon();

            sMainHandler.post(() -> {
                // ensure that the imageView still points to this app (e.g. due to possible recycle)
                if (pkgName.equals(imageView.getTag())) {
                    app.setLoadedIcon(icon);

                    if (icon != null)
                        imageView.setImageDrawable(icon);

                    imageView.setTag(null);
                }
            });
        });
    }
}
