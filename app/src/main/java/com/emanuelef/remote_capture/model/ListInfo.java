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

package com.emanuelef.remote_capture.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.collection.ArraySet;

import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.PCAPdroid;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.fragments.EditListFragment;
import com.emanuelef.remote_capture.model.MatchList.RuleType;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;


public class ListInfo {
    private final Type mType;
    private final String mAppPackage;

    public enum Type {
        VISUALIZATION_MASK,
        MALWARE_WHITELIST,
        BLOCKLIST,
        FIREWALL_WHITELIST,
        DECRYPTION_LIST,
        APP_ALLOWLIST,
    }

    public ListInfo(Type tp) {
        this(tp, null);
    }

    public ListInfo(Type tp, @Nullable String appPackage) {
        mType = tp;
        mAppPackage = appPackage;
    }

    public Type getType() {
        return mType;
    }

    public @Nullable String getAppPackage() {
        return mAppPackage;
    }

    public @NonNull MatchList getList() {
        switch(mType) {
            case VISUALIZATION_MASK:
                return PCAPdroid.getInstance().getVisualizationMask();
            case MALWARE_WHITELIST:
                return PCAPdroid.getInstance().getMalwareWhitelist();
            case BLOCKLIST:
                return PCAPdroid.getInstance().getBlocklist();
            case FIREWALL_WHITELIST:
                return PCAPdroid.getInstance().getFirewallWhitelist();
            case DECRYPTION_LIST:
                return PCAPdroid.getInstance().getDecryptionList();
            case APP_ALLOWLIST:
                assert mAppPackage != null;
                return PCAPdroid.getInstance().getBlocklist().getAppAllowlist(mAppPackage);
        }

        assert false;
        return null;
    }

    public int getTitle() {
        switch(mType) {
            case VISUALIZATION_MASK:
                return R.string.hidden_connections_rules;
            case MALWARE_WHITELIST:
                return R.string.malware_whitelist_rules;
            case BLOCKLIST:
                return R.string.firewall_rules;
            case FIREWALL_WHITELIST:
                return R.string.whitelist;
            case DECRYPTION_LIST:
                return R.string.decryption_rules;
            case APP_ALLOWLIST:
                return R.string.allowlist;
        }

        assert false;
        return 0;
    }

    public int getHelpString() {
        switch(mType) {
            case VISUALIZATION_MASK:
                return R.string.hidden_connections_help;
            case MALWARE_WHITELIST:
                return R.string.malware_whitelist_help;
            case BLOCKLIST:
                return 0;
            case FIREWALL_WHITELIST:
                return R.string.firewall_whitelist_help;
            case DECRYPTION_LIST:
                return R.string.decryption_rules_help;
            case APP_ALLOWLIST:
                return R.string.app_allowlist_help;
        }

        assert false;
        return 0;
    }

    public Set<RuleType> getSupportedRules() {
        switch(mType) {
            case VISUALIZATION_MASK:
                return new ArraySet<>(Arrays.asList(RuleType.APP, RuleType.IP, RuleType.HOST, RuleType.COUNTRY, RuleType.PROTOCOL));
            case MALWARE_WHITELIST:
            case DECRYPTION_LIST:
            case BLOCKLIST:
                return new ArraySet<>(Arrays.asList(RuleType.APP, RuleType.IP, RuleType.HOST, RuleType.COUNTRY));
            case FIREWALL_WHITELIST:
                return new ArraySet<>(Collections.singletonList(RuleType.APP));
            case APP_ALLOWLIST:
                return new ArraySet<>(Arrays.asList(RuleType.HOST, RuleType.IP));
        }

        assert false;
        return null;
    }

    public void save() {
        // The per-app allowlist has no preference of its own; it is persisted as part of the
        // owning blocklist.
        if(mType == Type.APP_ALLOWLIST)
            PCAPdroid.getInstance().getBlocklist().save();
        else
            getList().save();
    }

    public void reloadRules() {
        switch(mType) {
            case MALWARE_WHITELIST:
                CaptureService.reloadMalwareWhitelist();
                break;
            case BLOCKLIST, APP_ALLOWLIST:
                if(CaptureService.isServiceActive())
                    CaptureService.requireInstance().reloadBlocklist();
                break;
            case FIREWALL_WHITELIST:
                if(CaptureService.isServiceActive())
                    CaptureService.requireInstance().reloadFirewallWhitelist();
                break;
            case DECRYPTION_LIST:
                CaptureService.reloadDecryptionList();
                break;
        }
    }

    public EditListFragment newFragment() {
        return EditListFragment.newInstance(mType, mAppPackage);
    }
}
