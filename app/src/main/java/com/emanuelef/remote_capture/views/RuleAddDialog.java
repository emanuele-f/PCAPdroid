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
 * Copyright 2022 - Emanuele Faranda
 */

package com.emanuelef.remote_capture.views;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.emanuelef.remote_capture.R;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.Objects;

public class RuleAddDialog implements View.OnClickListener {
    private final Context mContext;
    private final AlertDialog mDialog;
    private final TextInputEditText mEditText;
    private final TextInputLayout mEditTextLayout;
    private final AutoCompleteTextView mComboText;
    private final TextInputLayout mComboLayout;
    private final ViewMode mViewMode;
    private final RuleAddListener mAdapter;
    private ArrayAdapter<String> mComboAdapter;

    private enum ViewMode {
        RULE_DIALOG_SIMPLE_TEXT,
        RULE_DIALOG_COMBO
    }

    public interface RuleAddListener {
        boolean addRule(String value, TextView field);
    }

    private RuleAddDialog(ViewMode viewMode, Context ctx, int title_res, RuleAddListener adapter) {
        mContext = ctx;
        mViewMode = viewMode;
        mAdapter = adapter;

        LayoutInflater inflater = LayoutInflater.from(ctx);
        View view = inflater.inflate(R.layout.add_rule_dialog, null);

        mComboLayout = view.findViewById(R.id.combo_field);
        mEditTextLayout = view.findViewById(R.id.text_field);
        mComboText = view.findViewById(R.id.combo_text);
        mEditText = view.findViewById(R.id.text_value);

        mDialog = new AlertDialog.Builder(ctx)
                .setView(view)
                .setTitle(title_res)
                .setPositiveButton(R.string.add_action, (dialogInterface, i) -> {})
                .setNegativeButton(R.string.cancel_action, (dialogInterface, i) -> {})
                .show();
        mDialog.setCanceledOnTouchOutside(false);
        mDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(this);
    }

    public static RuleAddDialog showText(Context ctx, int title_res, RuleAddListener adapter) {
        RuleAddDialog dialog = new RuleAddDialog(ViewMode.RULE_DIALOG_SIMPLE_TEXT, ctx, title_res, adapter);
        dialog.mEditTextLayout.setVisibility(View.VISIBLE);
        return dialog;
    }

    public static RuleAddDialog showCombo(Context ctx, int title_res, String[] values, RuleAddListener adapter) {
        RuleAddDialog dialog = new RuleAddDialog(ViewMode.RULE_DIALOG_COMBO, ctx, title_res, adapter);
        dialog.mComboLayout.setVisibility(View.VISIBLE);

        dialog.mComboAdapter = new ArrayAdapter<>(ctx, R.layout.dropdown_item, values);
        if(values.length > 0)
            dialog.mComboText.setText(values[0]);
        dialog.mComboText.setAdapter(dialog.mComboAdapter);

        return dialog;
    }

    @Override
    public void onClick(View v) {
        TextView field = getField();
        String text = Objects.requireNonNull(field.getText()).toString();

        if(text.isEmpty()) {
            field.setError(mContext.getString(R.string.required));
            return;
        }

        if(mComboAdapter != null) {
            // ensure that the value is in the selection list
            boolean found = false;

            for(int i=0; i<mComboAdapter.getCount(); i++) {
                String item = mComboAdapter.getItem(i);
                if(item.equals(text)) {
                    found = true;
                    break;
                }
            }

            if(!found) {
                field.setError(mContext.getString(R.string.invalid));
                return;
            }
        }

        if(!mAdapter.addRule(text, field))
            return;

        mDialog.dismiss();
    }

    public TextView getField() {
        if(mViewMode == ViewMode.RULE_DIALOG_SIMPLE_TEXT)
            return mEditText;
        else
            return mComboText;
    }
}
