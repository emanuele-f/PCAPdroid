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

package com.emanuelef.remote_capture.activities;

import android.content.Intent;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.model.Prefs;
import com.github.appintro.AppIntro;
import com.github.appintro.AppIntroBaseFragment;
import com.github.appintro.model.SliderPagerBuilder;

import org.jetbrains.annotations.Nullable;

public class OnBoardingActivity extends AppIntro {
    private static final String TAG = "OnBoardingActivity";

    public static class OnBoardingFragment extends AppIntroBaseFragment {
        @Override
        protected int getLayoutId() {
            return R.layout.appintro_fragment_intro;
        }

        public static OnBoardingFragment createInstance(CharSequence title, CharSequence description, int imageRes, int imageTint) {
            OnBoardingFragment fragment = new OnBoardingFragment();
            Bundle args = new SliderPagerBuilder()
                    .title(title)
                    //.description(description) see below
                    .imageDrawable(imageRes)
                    .backgroundColorRes(R.color.backgroundColor)
                    .titleColorRes(R.color.colorAccent)
                    .descriptionColorRes(R.color.colorTabText)
                    .build().toBundle();

            args.putCharSequence("pd_descr", description);
            args.putInt("pd_image_tint", imageTint);
            fragment.setArguments(args);

            return fragment;
        }

        @Nullable
        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
            View view = super.onCreateView(inflater, container, savedInstanceState);
            if(view == null)
                return null;

            Bundle args = getArguments();
            assert args != null;

            // fixes links from Utils.getText not clickable
            TextView tv = view.findViewById(R.id.description);
            tv.setAutoLinkMask(0);
            tv.setMovementMethod(LinkMovementMethod.getInstance());
            tv.setText(args.getCharSequence("pd_descr"));
            tv.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);

            // fix drawable tint and size
            ImageView image = view.findViewById(R.id.image);
            int tint = args.getInt("pd_image_tint");
            if(tint > 0)
                image.setColorFilter(ContextCompat.getColor(view.getContext(), tint));
            image.setAdjustViewBounds(true);
            ViewGroup.LayoutParams params = image.getLayoutParams();
            params.height = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 120, getResources().getDisplayMetrics());

            return view;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addSlide(OnBoardingFragment.createInstance(getString(R.string.welcome_to_pcapdroid),
                getText(R.string.app_intro_welcome_msg),
                R.drawable.ic_logo, R.color.colorAccent));

        addSlide(OnBoardingFragment.createInstance(getString(R.string.privacy_first),
                Utils.getText(this, R.string.app_intro_privacy_msg, MainActivity.PRIVACY_POLICY_URL,
                        MainActivity.GITHUB_PROJECT_URL),
                R.drawable.ic_shield, R.color.colorAccent));

        addSlide(OnBoardingFragment.createInstance(getString(R.string.country_and_asn),
                getText(R.string.app_intro_geolocation_msg),
                R.drawable.ic_location_dot, R.color.colorAccent));

        addSlide(OnBoardingFragment.createInstance(getString(R.string.additional_features),
                Utils.getText(this, R.string.app_intro_additional_features_msg, MainActivity.FIREWALL_DOCS_URL,
                        MainActivity.MALWARE_DETECTION_DOCS_URL, MainActivity.TLS_DECRYPTION_DOCS_URL),
                R.drawable.ic_rocket_launch, R.color.colorAccent));

        showStatusBar(true);
        setSkipButtonEnabled(false);
        setIndicatorEnabled(true);
        setSystemBackButtonLocked(true);

        // Theme
        int colorAccent = ContextCompat.getColor(this, R.color.colorAccent);
        setIndicatorColor(colorAccent, ContextCompat.getColor(this, R.color.colorAccentLight));
        setBackArrowColor(colorAccent);
        setColorSkipButton(colorAccent);
        setNextArrowColor(colorAccent);
        setBackArrowColor(colorAccent);
        setColorDoneText(colorAccent);
    }

    @Override
    protected void onSkipPressed(@Nullable Fragment currentFragment) {
        Log.d(TAG, "onSkipPressed");
        super.onSkipPressed(currentFragment);
        runMainActivity();
    }

    @Override
    protected void onDonePressed(@Nullable Fragment currentFragment) {
        Log.d(TAG, "onDonePressed");
        super.onDonePressed(currentFragment);
        runMainActivity();
    }

    private void runMainActivity() {
        Prefs.refreshAppVersion(PreferenceManager.getDefaultSharedPreferences(this));

        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}