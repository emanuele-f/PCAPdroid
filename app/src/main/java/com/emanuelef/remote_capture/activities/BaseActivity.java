package com.emanuelef.remote_capture.activities;

import android.content.Context;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.emanuelef.remote_capture.Utils;

import java.util.List;

public class BaseActivity extends AppCompatActivity {
    private boolean mBackAction = false;

    @Override
    protected void attachBaseContext(Context base) {
        // Ensure that the selected locale is used
        applyOverrideConfiguration(Utils.getLocalizedConfig(base));
        super.attachBaseContext(base);
    }

    protected void displayBackAction() {
        ActionBar actionBar = getSupportActionBar();

        if(actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            mBackAction = true;
        }
    }

    protected Fragment getFragment(Class targetClass) {
        List<Fragment> fragments = getSupportFragmentManager().getFragments();

        for(Fragment fragment : fragments) {
            if(targetClass.isInstance(fragment))
                return fragment;
        }

        return null;
    }

    protected Fragment getFragmentAtPos(int pos) {
        return getSupportFragmentManager().findFragmentByTag("f" + pos);
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if(mBackAction && (item.getItemId() == android.R.id.home)) {
            onBackPressed();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
}
