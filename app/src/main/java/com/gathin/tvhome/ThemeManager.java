package com.gathin.tvhome;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Applies the launcher's bundled themes and wallpapers from the TV itself.
 *
 * A full theme is written to the same external-files overlay that tools/theme.sh pushes:
 * config.json, wallpaper.jpg and a fresh theme_revision. Config.load therefore treats it exactly
 * like an ADB-pushed theme, including clearing layout and motion choices that belonged to the old
 * theme. A wallpaper-only change is different: it preserves the current theme signature, so it
 * does not reset the owner's layout or motion choices.
 */
final class ThemeManager {
    static final String BUILTIN = "builtin";
    static final String AURORA = "aurora";
    static final String DUSSEHRA = "dussehra";
    static final String SIX_COLUMNS = "six-columns";
    static final String CUSTOM = "custom";
    /** A theme.sh-pushed overlay is backed up here before a bundled theme overwrites it. */
    private static final String CUSTOM_BACKUP_DIR = "custom_backup";
    static final String NONE = "none";

    private static final String WALLPAPER_CHOICE_KEY = "wallpaper_choice";
    private static final String WALLPAPER_CUSTOM_NAME_KEY = "wallpaper_custom_name";

    private ThemeManager() { }

    static final class WallpaperOption {
        final String label;
        final File file;
        final Uri uri;

        WallpaperOption(String label, File file) {
            this.label = label;
            this.file = file;
            this.uri = null;
        }

        WallpaperOption(String label, Uri uri) {
            this.label = label;
            this.file = null;
            this.uri = uri;
        }
    }

    static boolean applyTheme(Context ctx, String theme) {
        File dir = Config.dir(ctx);
        if (dir == null || (!dir.exists() && !dir.mkdirs())) return false;
        // A pushed (theme.sh) overlay would be lost by the overwrite below: keep a copy
        // so the picker's Custom entry can bring it back.
        if (CUSTOM.equals(currentTheme(ctx)) && !backupCustomTheme(ctx)) return false;
        try {
            File config = new File(dir, Config.FILE_NAME);
            File wallpaper = new File(dir, Config.WALLPAPER_NAME);
            if (BUILTIN.equals(theme)) {
                if (!deleteIfPresent(config) || !deleteIfPresent(wallpaper)) return false;
            } else {
                String base = "themes/" + theme + "/";
                if (!copyAsset(ctx, base + "config.json", config)) return false;
                if (assetExists(ctx, base + Config.WALLPAPER_NAME)) {
                    if (!copyAsset(ctx, base + Config.WALLPAPER_NAME, wallpaper)) return false;
                } else if (!deleteIfPresent(wallpaper)) {
                    return false;
                }
            }
            writeText(new File(dir, Config.THEME_REVISION_NAME),
                    UUID.randomUUID().toString().replace("-", ""));
            SharedPreferences.Editor prefs = preferences(ctx).edit();
            if (AURORA.equals(theme) || DUSSEHRA.equals(theme)) {
                prefs.putString(WALLPAPER_CHOICE_KEY, theme).remove(WALLPAPER_CUSTOM_NAME_KEY);
            } else {
                prefs.putString(WALLPAPER_CHOICE_KEY, NONE).remove(WALLPAPER_CUSTOM_NAME_KEY);
            }
            prefs.apply();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    static String currentTheme(Context ctx) {
        File dir = Config.dir(ctx);
        if (dir == null) return CUSTOM;
        File config = new File(dir, Config.FILE_NAME);
        if (!config.isFile()) return BUILTIN;
        try {
            JSONObject json = new JSONObject(readText(config));
            if (DUSSEHRA.equalsIgnoreCase(json.optString("festival", ""))) return DUSSEHRA;
            if ("#070A14".equalsIgnoreCase(json.optString("background_color", ""))) return AURORA;
            if (json.optInt("columns", -1) == 6 && json.optInt("rows_below_dock", -1) == 1) {
                return SIX_COLUMNS;
            }
        } catch (Exception ignored) {
            // An unreadable overlay is still an overlay, just not one of the bundled themes.
        }
        return CUSTOM;
    }

    static boolean applyBundledWallpaper(Context ctx, String theme) {
        Config.load(ctx);   // settle any pending external theme change before preserving choices
        File dir = Config.dir(ctx);
        if (dir == null || (!dir.exists() && !dir.mkdirs())) return false;
        try (InputStream in = ctx.getAssets().open("themes/" + theme + "/" + Config.WALLPAPER_NAME)) {
            if (!copyStream(in, new File(dir, Config.WALLPAPER_NAME))) return false;
        } catch (Exception ignored) {
            return false;
        }
        if (!setWallpaperEnabled(ctx, true)) return false;
        preferences(ctx).edit().putString(WALLPAPER_CHOICE_KEY, theme)
                .remove(WALLPAPER_CUSTOM_NAME_KEY).apply();
        return true;
    }

    static boolean applyNoWallpaper(Context ctx) {
        Config.load(ctx);
        File dir = Config.dir(ctx);
        if (dir == null) return false;
        try {
            if (!deleteIfPresent(new File(dir, Config.WALLPAPER_NAME))) return false;
        } catch (Exception ignored) {
            return false;
        }
        if (!setWallpaperEnabled(ctx, false)) return false;
        preferences(ctx).edit().putString(WALLPAPER_CHOICE_KEY, NONE)
                .remove(WALLPAPER_CUSTOM_NAME_KEY).apply();
        return true;
    }

    static boolean applyCustomWallpaper(Context ctx, WallpaperOption option) {
        if (option == null) return false;
        Config.load(ctx);
        File dir = Config.dir(ctx);
        if (dir == null || (!dir.exists() && !dir.mkdirs())) return false;
        InputStream in = null;
        try {
            if (option.file != null) {
                if (!option.file.isFile() || option.file.length() == 0) return false;
                in = new FileInputStream(option.file);
            } else if (option.uri != null) {
                in = ctx.getContentResolver().openInputStream(option.uri);
                if (in == null) return false;
            } else {
                return false;
            }
            if (!copyStream(in, new File(dir, Config.WALLPAPER_NAME))) return false;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (in != null) {
                try { in.close(); } catch (IOException ignored) { }
            }
        }
        if (!setWallpaperEnabled(ctx, true)) return false;
        preferences(ctx).edit().putString(WALLPAPER_CHOICE_KEY, CUSTOM)
                .putString(WALLPAPER_CUSTOM_NAME_KEY, option.label).apply();
        return true;
    }

    static String wallpaperChoice(Context ctx) {
        File dir = Config.dir(ctx);
        File wallpaper = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        if (wallpaper == null || !wallpaper.isFile() || wallpaper.length() == 0) return NONE;
        String chosen = preferences(ctx).getString(WALLPAPER_CHOICE_KEY, "");
        if (chosen != null && !chosen.isEmpty()) return chosen;
        String theme = currentTheme(ctx);
        return AURORA.equals(theme) || DUSSEHRA.equals(theme) ? theme : CUSTOM;
    }

    static String wallpaperCustomName(Context ctx) {
        String name = preferences(ctx).getString(WALLPAPER_CUSTOM_NAME_KEY, "");
        return name == null ? "" : name;
    }

    static boolean hasImagePermission(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Images from the permission-free drop folder first, then the TV's photo library when allowed. */
    static List<WallpaperOption> customWallpapers(Context ctx) {
        List<WallpaperOption> out = new ArrayList<>();
        File dir = Config.dir(ctx);
        File drop = dir == null ? null : new File(dir, "wallpapers");
        if (drop != null && drop.isDirectory()) {
            File[] files = drop.listFiles((d, name) -> isImageName(name));
            if (files != null) {
                Arrays.sort(files, Comparator.comparing(f -> f.getName().toLowerCase(Locale.ROOT)));
                for (File file : files) {
                    if (file.isFile() && file.length() > 0) {
                        out.add(new WallpaperOption(file.getName(), file));
                    }
                }
            }
        }
        if (hasImagePermission(ctx)) addMediaStoreImages(ctx, out);
        return out;
    }

    private static void addMediaStoreImages(Context ctx, List<WallpaperOption> out) {
        ContentResolver resolver = ctx.getContentResolver();
        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME};
        try (Cursor cursor = resolver.query(collection, projection, null, null,
                MediaStore.Images.Media.DATE_MODIFIED + " DESC")) {
            if (cursor == null) return;
            int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
            int added = 0;
            while (cursor.moveToNext() && added < 80) {
                long id = cursor.getLong(idColumn);
                String name = cursor.getString(nameColumn);
                if (name == null || name.isEmpty()) name = "Photo " + id;
                out.add(new WallpaperOption(name, ContentUris.withAppendedId(collection, id)));
                added++;
            }
        } catch (Exception ignored) {
            // The drop folder above still works when the media library cannot be read.
        }
    }

    private static boolean setWallpaperEnabled(Context ctx, boolean enabled) {
        File dir = Config.dir(ctx);
        if (dir == null || (!dir.exists() && !dir.mkdirs())) return false;
        File configFile = new File(dir, Config.FILE_NAME);
        if (!configFile.isFile() && !enabled) return true;
        try {
            JSONObject json;
            if (configFile.isFile()) {
                try {
                    json = new JSONObject(readText(configFile));
                } catch (Exception badJson) {
                    if (!enabled) return false;
                    json = new JSONObject();
                }
            } else {
                json = new JSONObject();
            }
            json.put("wallpaper", enabled);
            writeText(configFile, json.toString());
            Config.preserveCurrentSignature(ctx);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static SharedPreferences preferences(Context ctx) {
        return ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
    }

    /** Copies the current pushed overlay aside; true when there was nothing to save or it worked. */
    static boolean backupCustomTheme(Context ctx) {
        File dir = Config.dir(ctx);
        if (dir == null) return false;
        File config = new File(dir, Config.FILE_NAME);
        if (!config.isFile()) return true;
        File backup = new File(dir, CUSTOM_BACKUP_DIR);
        if (!backup.exists() && !backup.mkdirs()) return false;
        try (InputStream in = new FileInputStream(config)) {
            if (!copyStream(in, new File(backup, Config.FILE_NAME))) return false;
        } catch (Exception ignored) {
            return false;
        }
        File wallpaper = new File(dir, Config.WALLPAPER_NAME);
        try (InputStream in = new FileInputStream(wallpaper)) {
            if (!copyStream(in, new File(backup, Config.WALLPAPER_NAME))) return false;
        } catch (Exception ignored) {
            deleteIfPresent(new File(backup, Config.WALLPAPER_NAME));
        }
        return true;
    }

    /** Whether a pushed overlay was backed up and can be restored from the picker. */
    static boolean hasCustomBackup(Context ctx) {
        File dir = Config.dir(ctx);
        return dir != null && new File(new File(dir, CUSTOM_BACKUP_DIR), Config.FILE_NAME).isFile();
    }

    /** Restores the backed-up pushed overlay; a no-op when it is already the current theme. */
    static boolean restoreCustomTheme(Context ctx) {
        File dir = Config.dir(ctx);
        if (dir == null || (!dir.exists() && !dir.mkdirs())) return false;
        if (CUSTOM.equals(currentTheme(ctx))) return true;
        File backup = new File(dir, CUSTOM_BACKUP_DIR);
        try (InputStream in = new FileInputStream(new File(backup, Config.FILE_NAME))) {
            if (!copyStream(in, new File(dir, Config.FILE_NAME))) return false;
        } catch (Exception ignored) {
            return false;
        }
        File backupWallpaper = new File(backup, Config.WALLPAPER_NAME);
        if (backupWallpaper.isFile()) {
            try (InputStream in = new FileInputStream(backupWallpaper)) {
                if (!copyStream(in, new File(dir, Config.WALLPAPER_NAME))) return false;
            } catch (Exception ignored) {
                return false;
            }
        } else if (!deleteIfPresent(new File(dir, Config.WALLPAPER_NAME))) {
            return false;
        }
        try {
            writeText(new File(dir, Config.THEME_REVISION_NAME),
                    UUID.randomUUID().toString().replace("-", ""));
        } catch (Exception ignored) {
            return false;
        }
        return true;
    }

    private static boolean assetExists(Context ctx, String path) {
        try {
            String folder = path.substring(0, path.lastIndexOf('/'));
            String name = path.substring(path.lastIndexOf('/') + 1);
            String[] names = ctx.getAssets().list(folder);
            if (names == null) return false;
            for (String item : names) if (name.equals(item)) return true;
        } catch (Exception ignored) {
        }
        return false;
    }

    private static boolean copyAsset(Context ctx, String asset, File dest) {
        try (InputStream in = ctx.getAssets().open(asset)) {
            return copyStream(in, dest);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean copyStream(InputStream in, File dest) {
        File tmp = new File(dest.getParentFile(), dest.getName() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            out.flush();
        } catch (Exception ignored) {
            deleteIfPresent(tmp);
            return false;
        }
        if (dest.exists() && !dest.delete()) {
            deleteIfPresent(tmp);
            return false;
        }
        if (tmp.renameTo(dest)) return true;
        try (InputStream fin = new FileInputStream(tmp); OutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = fin.read(buffer)) > 0) out.write(buffer, 0, n);
            out.flush();
            return true;
        } catch (Exception ignored) {
            return false;
        } finally {
            deleteIfPresent(tmp);
        }
    }

    private static boolean deleteIfPresent(File file) {
        return file == null || !file.exists() || file.delete();
    }

    private static void writeText(File file, String text) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String readText(File file) throws IOException {
        if (file.length() > 1024 * 1024) throw new IOException("file too large");
        try (InputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int n = 0, r;
            while (n < bytes.length && (r = in.read(bytes, n, bytes.length - n)) > 0) n += r;
            String text = new String(bytes, 0, n, StandardCharsets.UTF_8);
            return text.startsWith("\uFEFF") ? text.substring(1) : text;
        }
    }

    private static boolean isImageName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".png") || lower.endsWith(".webp");
    }
}
