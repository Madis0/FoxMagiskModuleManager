package com.fox2code.mmm.compat;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.CallSuper;
import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.annotation.StyleRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.fox2code.mmm.Constants;
import com.fox2code.mmm.R;

import java.util.Locale;
import java.util.Objects;

/**
 * I will probably outsource this to a separate library later
 */
public class CompatActivity extends AppCompatActivity {
    public static final int INTENT_ACTIVITY_REQUEST_CODE = 0x01000000;
    private static final String TAG = "CompatActivity";
    public static final CompatActivity.OnBackPressedCallback DISABLE_BACK_BUTTON =
            new CompatActivity.OnBackPressedCallback() {
        @Override
        public boolean onBackPressed(CompatActivity compatActivity) {
            compatActivity.setOnBackPressedCallback(this);
            return true;
        }
    };

    private final CompatConfigHelper compatConfigHelper = new CompatConfigHelper(this);
    private CompatActivity.OnActivityResultCallback onActivityResultCallback;
    private CompatActivity.OnBackPressedCallback onBackPressedCallback;
    private MenuItem.OnMenuItemClickListener menuClickListener;
    @StyleRes private int setThemeDynamic = 0;
    private boolean onCreateCalled = false;
    private boolean isRefreshUi = false;
    private int drawableResId;
    private MenuItem menuItem;
    // CompatConfigHelper
    private boolean forceEnglish;
    private Boolean nightModeOverride;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        Application application = this.getApplication();
        if (application instanceof ApplicationCallbacks) {
            ((ApplicationCallbacks) application).onCreateCompatActivity(this);
        }
        super.onCreate(savedInstanceState);
        this.onCreateCalled = true;
        this.checkResourcesOverrides(
                this.forceEnglish, this.nightModeOverride);
    }

    @Override
    protected void onResume() {
        super.onResume();
        this.refreshUI();
    }

    @Override
    public void finish() {
        this.onActivityResultCallback = null;
        boolean fadeOut = this.onCreateCalled && this.getIntent()
                .getBooleanExtra(Constants.EXTRA_FADE_OUT, false);
        super.finish();
        if (fadeOut) {
            super.overridePendingTransition(
                    android.R.anim.fade_in, android.R.anim.fade_out);
        }
    }

    @CallSuper
    public void refreshUI() {
        // Avoid recursive calls
        if (this.isRefreshUi) return;
        Application application = this.getApplication();
        if (application instanceof ApplicationCallbacks) {
            this.isRefreshUi = true;
            try {
                ((ApplicationCallbacks) application)
                        .onRefreshUI(this);
            } finally {
                this.isRefreshUi = false;
            }
            this.checkResourcesOverrides(
                    this.forceEnglish, this.nightModeOverride);
        }
    }

    public final void forceBackPressed() {
        if (!this.isFinishing())
            super.onBackPressed();
    }

    @Override
    public void onBackPressed() {
        if (this.isFinishing()) return;
        OnBackPressedCallback onBackPressedCallback = this.onBackPressedCallback;
        this.onBackPressedCallback = null;
        if (onBackPressedCallback == null ||
                !onBackPressedCallback.onBackPressed(this)) {
            super.onBackPressed();
        }
    }

    public void setDisplayHomeAsUpEnabled(boolean showHomeAsUp) {
        androidx.appcompat.app.ActionBar compatActionBar;
        try {
            compatActionBar = this.getSupportActionBar();
        } catch (Exception e) {
            Log.e(TAG, "Failed to call getSupportActionBar", e);
            compatActionBar = null; // Allow fallback to builtin actionBar.
        }
        if (compatActionBar != null) {
            compatActionBar.setDisplayHomeAsUpEnabled(showHomeAsUp);
        } else {
            android.app.ActionBar actionBar = this.getActionBar();
            if (actionBar != null)
                actionBar.setDisplayHomeAsUpEnabled(showHomeAsUp);
        }
    }

    public void hideActionBar() {
        androidx.appcompat.app.ActionBar compatActionBar;
        try {
            compatActionBar = this.getSupportActionBar();
        } catch (Exception e) {
            Log.e(TAG, "Failed to call getSupportActionBar", e);
            compatActionBar = null; // Allow fallback to builtin actionBar.
        }
        if (compatActionBar != null) {
            compatActionBar.hide();
        } else {
            android.app.ActionBar actionBar = this.getActionBar();
            if (actionBar != null)
                actionBar.hide();
        }
    }

    public void setActionBarExtraMenuButton(@DrawableRes int drawableResId,
                                            MenuItem.OnMenuItemClickListener menuClickListener) {
        Objects.requireNonNull(menuClickListener);
        this.drawableResId = drawableResId;
        this.menuClickListener = menuClickListener;
        if (this.menuItem != null) {
            this.menuItem.setOnMenuItemClickListener(this.menuClickListener);
            this.menuItem.setIcon(this.drawableResId);
            this.menuItem.setEnabled(true);
            this.menuItem.setVisible(true);
        }
    }

    public void removeActionBarExtraMenuButton() {
        this.drawableResId = 0;
        this.menuClickListener = null;
        if (this.menuItem != null) {
            this.menuItem.setOnMenuItemClickListener(null);
            this.menuItem.setIcon(null);
            this.menuItem.setEnabled(false);
            this.menuItem.setVisible(false);
        }
    }

    // like setTheme but recreate the activity if needed
    public void setThemeRecreate(@StyleRes int resId) {
        if (!this.onCreateCalled) {
            this.setTheme(resId);
            return;
        }
        if (this.setThemeDynamic == resId)
            return;
        if (this.setThemeDynamic != 0)
            throw new IllegalStateException("setThemeDynamic called recursively");
        this.setThemeDynamic = resId;
        try {
            super.setTheme(resId);
        } finally {
            this.setThemeDynamic = 0;
        }
    }

    @Override
    protected void onApplyThemeResource(Resources.Theme theme, int resid, boolean first) {
        if (resid != 0 && this.setThemeDynamic == resid) {
            super.onApplyThemeResource(theme, resid, first);
            Activity parent = this.getParent();
            (parent == null ? this : parent).recreate();
            super.overridePendingTransition(
                    android.R.anim.fade_in, android.R.anim.fade_out);
        } else {
            this.compatConfigHelper.checkResourcesOverrides(theme,
                    this.forceEnglish, this.nightModeOverride);
            super.onApplyThemeResource(theme, resid, first);
        }
    }

    public void setOnBackPressedCallback(OnBackPressedCallback onBackPressedCallback) {
        this.onBackPressedCallback = onBackPressedCallback;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            androidx.appcompat.app.ActionBar compatActionBar;
            try {
                compatActionBar = this.getSupportActionBar();
            } catch (Exception e) {
                Log.e(TAG, "Failed to call getSupportActionBar", e);
                compatActionBar = null; // Allow fallback to builtin actionBar.
            }
            android.app.ActionBar actionBar = this.getActionBar();
            if (compatActionBar != null ? (compatActionBar.getDisplayOptions() &
                    androidx.appcompat.app.ActionBar.DISPLAY_HOME_AS_UP) != 0 :
                    actionBar != null && (actionBar.getDisplayOptions() &
                            android.app.ActionBar.DISPLAY_HOME_AS_UP) != 0) {
                this.onBackPressed();
                return true;
            }
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        this.getMenuInflater().inflate(R.menu.compat_menu, menu);
        this.menuItem = menu.findItem(R.id.compat_menu_item);
        if (this.menuClickListener != null) {
            this.menuItem.setOnMenuItemClickListener(this.menuClickListener);
            this.menuItem.setIcon(this.drawableResId);
            this.menuItem.setEnabled(true);
            this.menuItem.setVisible(true);
        }
        return super.onCreateOptionsMenu(menu);
    }

    public void startActivityForResult(Intent intent,
                                       OnActivityResultCallback onActivityResultCallback) {
        this.startActivityForResult(intent, null, onActivityResultCallback);
    }

    @SuppressWarnings("deprecation")
    public void startActivityForResult(Intent intent, @Nullable Bundle options,
                                       OnActivityResultCallback onActivityResultCallback) {
        super.startActivityForResult(intent, INTENT_ACTIVITY_REQUEST_CODE, options);
        this.onActivityResultCallback = onActivityResultCallback;
    }

    @Override
    @CallSuper
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == INTENT_ACTIVITY_REQUEST_CODE) {
            OnActivityResultCallback callback = this.onActivityResultCallback;
            if (callback != null) {
                this.onActivityResultCallback = null;
                callback.onActivityResult(resultCode, data);
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    public void setForceEnglish(boolean forceEnglish) {
        if (this.forceEnglish == forceEnglish) return;
        this.forceEnglish = forceEnglish;
        this.checkResourcesOverrides(forceEnglish, this.nightModeOverride);
    }

    public void setNightModeOverride(Boolean nightModeOverride) {
        if (this.nightModeOverride == nightModeOverride) return;
        this.nightModeOverride = nightModeOverride;
        this.checkResourcesOverrides(this.forceEnglish, nightModeOverride);
    }

    private void checkResourcesOverrides(boolean forceEnglish,Boolean nightModeOverride) {
        if (this.isRefreshUi || !this.onCreateCalled) return; // Wait before reload
        this.compatConfigHelper.checkResourcesOverrides(forceEnglish, nightModeOverride);
    }

    public Locale getUserLocale() {
        return this.compatConfigHelper.getUserLocale();
    }

    public static CompatActivity getCompatActivity(View view) {
        return getCompatActivity(view.getContext());
    }

    public static CompatActivity getCompatActivity(Fragment fragment) {
        return getCompatActivity(fragment.getContext());
    }

    public static CompatActivity getCompatActivity(Context context) {
        while (!(context instanceof CompatActivity)) {
            if (context instanceof ContextWrapper) {
                context = ((ContextWrapper) context).getBaseContext();
            } else return null;
        }
        return (CompatActivity) context;
    }

    @FunctionalInterface
    public interface OnActivityResultCallback {
        void onActivityResult(int resultCode, @Nullable Intent data);
    }

    @FunctionalInterface
    public interface OnBackPressedCallback {
        boolean onBackPressed(CompatActivity compatActivity);
    }

    public interface ApplicationCallbacks {
        void onCreateCompatActivity(CompatActivity compatActivity);

        void onRefreshUI(CompatActivity compatActivity);
    }
}
