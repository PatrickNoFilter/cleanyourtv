package com.gathin.tvhome;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Remote-friendly settings owned by the launcher; system settings stay one row away. */
public final class LauncherSettingsActivity extends Activity {
    private static final int IMAGE_PERMISSION_REQUEST = 701;

    private SharedPreferences choices;
    private final TextView[] columnChoices = new TextView[4];
    private final TextView[] rowChoices = new TextView[4];
    private TextView themeChoice;
    private TextView wallpaperChoice;
    private TextView animationsChoice;
    private TextView advancedChoice;
    private TextView followThemeChoice;
    private int columns;
    private int rowsBelowDock;
    private boolean animations;
    private boolean advancedEffects;
    private boolean pendingCustomPicker;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        loadState();

        themeChoice = findViewById(R.id.themeChoice);
        themeChoice.setOnClickListener(v -> showThemePicker());
        wallpaperChoice = findViewById(R.id.wallpaperChoice);
        wallpaperChoice.setOnClickListener(v -> showWallpaperPicker());

        int[] columnIds = {R.id.columns4, R.id.columns5, R.id.columns6, R.id.columns7};
        for (int i = 0; i < columnIds.length; i++) {
            final int value = i + 4;
            columnChoices[i] = findViewById(columnIds[i]);
            columnChoices[i].setOnClickListener(v -> {
                if (columns == value) return;
                columns = value;
                choices.edit().putInt("columns", value).apply();
                setResult(RESULT_OK);
                refresh();
            });
        }
        int[] rowIds = {R.id.rows0, R.id.rows1, R.id.rows2, R.id.rows3};
        for (int i = 0; i < rowIds.length; i++) {
            final int value = i;
            rowChoices[i] = findViewById(rowIds[i]);
            rowChoices[i].setOnClickListener(v -> {
                if (rowsBelowDock == value) return;
                rowsBelowDock = value;
                choices.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, value).apply();
                setResult(RESULT_OK);
                refresh();
            });
        }
        // Keep the numeric choices increasing in the Hebrew reading direction. Reorder the
        // actual views so D-pad focus follows their positions without changing the saved values.
        if (getResources().getBoolean(R.bool.rtl_app_grid)) {
            reverseRow(columnChoices);
            reverseRow(rowChoices);
        }
        animationsChoice = findViewById(R.id.animationsChoice);
        animationsChoice.setOnClickListener(v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            setResult(RESULT_OK);
            refresh();
        });
        advancedChoice = findViewById(R.id.advancedChoice);
        advancedChoice.setOnClickListener(v -> {
            if (advancedEffects) {
                setAdvanced(false);
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.advanced_effects)
                        .setMessage(R.string.advanced_effects_warning)
                        .setPositiveButton(R.string.enable, (dialog, which) -> setAdvanced(true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
        findViewById(R.id.resetIconOrderChoice).setOnClickListener(v ->
                new AlertDialog.Builder(this)
                        .setTitle(R.string.reset_icon_order)
                        .setMessage(R.string.reset_icon_order_message)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                            choices.edit().remove(Config.APP_ORDER_KEY)
                                    .remove(Config.DOCK_ORDER_KEY).apply();
                            setResult(RESULT_OK);
                            Toast.makeText(this, R.string.icon_order_reset, Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show());
        followThemeChoice = findViewById(R.id.followThemeChoice);
        followThemeChoice.setOnClickListener(v -> {
            if (!hasOnTvChoices()) return;
            choices.edit().remove("columns").remove("animations").remove("advanced_effects")
                    .remove(Config.ROWS_BELOW_DOCK_KEY).commit();
            loadState();
            setResult(RESULT_OK);
            refresh();
        });
        findViewById(R.id.defaultHomeChoice).setOnClickListener(v -> {
            Toast.makeText(this, R.string.home_settings_help, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_HOME_SETTINGS));
        });
        findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(new Intent(Settings.ACTION_SETTINGS)));
        refresh();
        columnChoices[Math.max(0, Math.min(columns - 4, 3))].requestFocus();
    }

    private void loadState() {
        Config config = Config.load(this);
        columns = config.columns;
        rowsBelowDock = config.rowsBelowDock;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;
    }

    private static void reverseRow(TextView[] views) {
        LinearLayout row = (LinearLayout) views[0].getParent();
        row.removeAllViews();
        for (int i = views.length - 1; i >= 0; i--) row.addView(views[i]);
    }

    private void showThemePicker() {
        String current = ThemeManager.currentTheme(this);
        // A pushed (theme.sh) overlay survives as a restorable Custom entry: it is backed up
        // before a bundled theme overwrites it, so picking Aurora never loses it for good.
        boolean showCustom = ThemeManager.CUSTOM.equals(current) || ThemeManager.hasCustomBackup(this);
        String[] ids = showCustom
                ? new String[]{ThemeManager.BUILTIN, ThemeManager.AURORA,
                        ThemeManager.DUSSEHRA, ThemeManager.SIX_COLUMNS, ThemeManager.CUSTOM}
                : new String[]{ThemeManager.BUILTIN, ThemeManager.AURORA,
                        ThemeManager.DUSSEHRA, ThemeManager.SIX_COLUMNS};
        String[] labels = showCustom
                ? new String[]{getString(R.string.theme_builtin), getString(R.string.theme_aurora),
                        getString(R.string.theme_dussehra), getString(R.string.theme_six_columns),
                        getString(R.string.theme_custom)}
                : new String[]{getString(R.string.theme_builtin), getString(R.string.theme_aurora),
                        getString(R.string.theme_dussehra), getString(R.string.theme_six_columns)};
        int checked = -1;
        for (int i = 0; i < ids.length; i++) if (ids[i].equals(current)) checked = i;
        new AlertDialog.Builder(this)
                .setTitle(R.string.theme_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    boolean ok = ThemeManager.CUSTOM.equals(ids[which])
                            ? ThemeManager.restoreCustomTheme(this)
                            : ThemeManager.applyTheme(this, ids[which]);
                    if (ok) {
                        setResult(RESULT_OK);
                        loadState();
                        Toast.makeText(this, R.string.theme_applied, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, R.string.change_failed, Toast.LENGTH_SHORT).show();
                    }
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showWallpaperPicker() {
        String[] labels = {getString(R.string.theme_aurora), getString(R.string.theme_dussehra),
                getString(R.string.wallpaper_none), getString(R.string.wallpaper_custom)};
        String current = ThemeManager.wallpaperChoice(this);
        int checked = ThemeManager.AURORA.equals(current) ? 0
                : ThemeManager.DUSSEHRA.equals(current) ? 1
                : ThemeManager.NONE.equals(current) ? 2 : 3;
        new AlertDialog.Builder(this)
                .setTitle(R.string.wallpaper_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == 3) {
                        showCustomWallpaperPicker();
                        return;
                    }
                    boolean ok = which == 0
                            ? ThemeManager.applyBundledWallpaper(this, ThemeManager.AURORA)
                            : which == 1
                            ? ThemeManager.applyBundledWallpaper(this, ThemeManager.DUSSEHRA)
                            : ThemeManager.applyNoWallpaper(this);
                    if (ok) {
                        setResult(RESULT_OK);
                        Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, R.string.change_failed, Toast.LENGTH_SHORT).show();
                    }
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showCustomWallpaperPicker() {
        List<ThemeManager.WallpaperOption> options = ThemeManager.customWallpapers(this);
        boolean granted = ThemeManager.hasImagePermission(this);
        if (options.isEmpty()) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this)
                    .setTitle(R.string.wallpaper_custom_title)
                    .setMessage(R.string.wallpaper_no_images)
                    .setNegativeButton(android.R.string.cancel, null);
            if (!granted) {
                builder.setPositiveButton(R.string.wallpaper_grant_access, (dialog, which) -> {
                    pendingCustomPicker = true;
                    requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                            IMAGE_PERMISSION_REQUEST);
                });
            }
            builder.show();
            return;
        }
        List<String> labels = new ArrayList<>();
        for (ThemeManager.WallpaperOption option : options) labels.add(option.label);
        if (!granted) labels.add(getString(R.string.wallpaper_grant_access));
        new AlertDialog.Builder(this)
                .setTitle(R.string.wallpaper_custom_title)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    if (which >= options.size()) {
                        pendingCustomPicker = true;
                        requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                                IMAGE_PERMISSION_REQUEST);
                        return;
                    }
                    if (ThemeManager.applyCustomWallpaper(this, options.get(which))) {
                        setResult(RESULT_OK);
                        Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, R.string.change_failed, Toast.LENGTH_SHORT).show();
                    }
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != IMAGE_PERMISSION_REQUEST || !pendingCustomPicker) return;
        pendingCustomPicker = false;
        // Granted: the library is now in the list. Denied: the drop-folder choices still are.
        showCustomWallpaperPicker();
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        setResult(RESULT_OK);
        refresh();
    }

    private void refresh() {
        for (int i = 0; i < columnChoices.length; i++) {
            columnChoices[i].setSelected(columns == i + 4);
        }
        for (int i = 0; i < rowChoices.length; i++) {
            rowChoices[i].setSelected(rowsBelowDock == i);
        }
        themeChoice.setText(getString(R.string.theme_state,
                themeLabel(ThemeManager.currentTheme(this))));
        wallpaperChoice.setText(getString(R.string.wallpaper_state, wallpaperLabel()));
        animationsChoice.setText(getString(R.string.animations_state,
                getString(animations ? R.string.on : R.string.off)));
        advancedChoice.setText(getString(R.string.advanced_effects_state,
                getString(advancedEffects ? R.string.on : R.string.off)));
        advancedChoice.setEnabled(animations);
        advancedChoice.setAlpha(animations ? 1f : 0.5f);
        followThemeChoice.setSelected(!hasOnTvChoices());
    }

    private String themeLabel(String theme) {
        if (ThemeManager.AURORA.equals(theme)) return getString(R.string.theme_aurora);
        if (ThemeManager.DUSSEHRA.equals(theme)) return getString(R.string.theme_dussehra);
        if (ThemeManager.SIX_COLUMNS.equals(theme)) return getString(R.string.theme_six_columns);
        if (ThemeManager.BUILTIN.equals(theme)) return getString(R.string.theme_builtin);
        return getString(R.string.theme_custom);
    }

    private String wallpaperLabel() {
        String choice = ThemeManager.wallpaperChoice(this);
        if (ThemeManager.AURORA.equals(choice)) return getString(R.string.theme_aurora);
        if (ThemeManager.DUSSEHRA.equals(choice)) return getString(R.string.theme_dussehra);
        if (ThemeManager.NONE.equals(choice)) return getString(R.string.wallpaper_none);
        String customName = ThemeManager.wallpaperCustomName(this);
        return customName.isEmpty() ? getString(R.string.wallpaper_custom) : customName;
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains("animations")
                || choices.contains("advanced_effects")
                || choices.contains(Config.ROWS_BELOW_DOCK_KEY);
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
