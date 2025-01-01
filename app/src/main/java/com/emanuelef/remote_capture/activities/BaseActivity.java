package com.emanuelef.remote_capture.activities;

import android.content.Context;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;

import java.util.List;

public class BaseActivity extends AppCompatActivity {
    private boolean mBackAction = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Utils.enableEdgeToEdge(this);
        super.onCreate(savedInstanceState);
    }

    @Override
    public void setContentView(int res)  {
        super.setContentView(res);

        View toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            // Fix padding of content below the toolbar
            ViewCompat.setOnApplyWindowInsetsListener(toolbar, (view, insets) -> {
                int topInset = insets.getInsets(WindowInsetsCompat.Type.statusBars() |
                        WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()).top;
                if (topInset > 0)
                    view.setPadding(0, topInset, 0, 0); // Shift the toolbar down if needed

                return insets;
            });
        }
    }

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
