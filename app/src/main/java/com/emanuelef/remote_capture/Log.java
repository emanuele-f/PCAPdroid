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

package com.emanuelef.remote_capture;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class Log {
    public static final int LOG_LEVEL_INFO = 4;
    public static final String DEFAULT_LOGGER_PATH = "pcapdroid.log";
    public static final String ROOT_LOGGER_PATH = "pcapd.log";
    public static final String MITM_LOGGER_PATH = "mitmaddon.log";
    public static int DEFAULT_LOGGER;
    public static int MITMADDON_LOGGER;

    public static void init(String cachedir) {
        DEFAULT_LOGGER = CaptureService.initLogger(cachedir + "/" + DEFAULT_LOGGER_PATH, LOG_LEVEL_INFO);
        MITMADDON_LOGGER = CaptureService.initLogger(cachedir + "/" + MITM_LOGGER_PATH, LOG_LEVEL_INFO);
    }

    public static void writeLog(int logger, int level, @Nullable String tag, @NonNull String message) {
        if(!PCAPdroid.isUnderTest())
            CaptureService.writeLog(logger, level, ((tag != null) ? ("[" + tag + "] ") : "") + message);
    }

    public static void d(@Nullable String tag, @NonNull String message) {
        android.util.Log.d(tag, message);
    }

    public static void i(@Nullable String tag, @NonNull String message) {
        android.util.Log.i(tag, message);
        writeLog(DEFAULT_LOGGER, android.util.Log.INFO, tag, message);
    }

    public static void i(int logger, @NonNull String message) {
        writeLog(logger, android.util.Log.INFO, null, message);
    }

    public static void w(@Nullable String tag, @NonNull String message) {
        android.util.Log.w(tag, message);
        writeLog(DEFAULT_LOGGER, android.util.Log.WARN, tag, message);
    }

    public static void w(int logger, @NonNull String message) {
        writeLog(logger, android.util.Log.WARN, null, message);
    }

    public static void e(@Nullable String tag, @NonNull String message) {
        android.util.Log.e(tag, message);
        writeLog(DEFAULT_LOGGER, android.util.Log.ERROR, tag, message);
    }

    public static void e(int logger, @NonNull String message) {
        writeLog(logger, android.util.Log.ERROR, null, message);
    }

    public static void wtf(@Nullable String tag, @NonNull String message) {
        android.util.Log.wtf(tag, message);
        writeLog(DEFAULT_LOGGER, android.util.Log.ASSERT, tag, message); // ANDROID_LOG_FATAL
    }

    public static void level(int logger, int level, @NonNull String message) {
        switch (level) {
            case android.util.Log.INFO:
                Log.i(logger, message);
                break;
            case android.util.Log.WARN:
                Log.w(logger, message);
                break;
            case android.util.Log.ERROR:
                Log.e(logger, message);
                break;
        }
    }
}