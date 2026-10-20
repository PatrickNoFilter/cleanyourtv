package com.gathin.tvhome;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * Reads the theme pushed via tools/theme.sh from the TV itself.
 *
 * A pushed theme is an overlay in the app's external files directory: config.json,
 * wallpaper.jpg and a theme_revision marker. This class only reads that overlay and
 * can remove it (back to the built-in config.xml); it bundles no theme copies, so it
 * adds no APK size. Picking a theme happens with theme.sh from a computer.
 */
final class ThemeManager {
    static final String BUILTIN = "builtin";
    static final String CUSTOM = "custom";

    private static final String REVISION_NAME = "theme_revision";

    private ThemeManager() { }

    /** "custom" when a theme.sh overlay is present, "builtin" otherwise. */
    static String currentTheme(Context ctx) {
        File dir = Config.dir(ctx);
        return dir != null && new File(dir, Config.FILE_NAME).isFile() ? CUSTOM : BUILTIN;
    }

    /** Removes the pushed overlay, back to the built-in theme. False on I/O failure. */
    static boolean resetToBuiltin(Context ctx) {
        File dir = Config.dir(ctx);
        if (dir == null) return false;
        boolean ok = true;
        ok &= delete(new File(dir, Config.FILE_NAME));
        ok &= delete(new File(dir, Config.WALLPAPER_NAME));
        File wallpapers = new File(dir, "wallpapers");
        File[] entries = wallpapers.listFiles();
        if (entries != null) for (File entry : entries) ok &= delete(entry);
        // A fresh revision makes Config treat this like theme.sh --reset and clear
        // on-TV choices that belonged to the pushed theme.
        try (FileOutputStream out = new FileOutputStream(new File(dir, REVISION_NAME))) {
            out.write(UUID.randomUUID().toString().replace("-", "").getBytes("UTF-8"));
        } catch (IOException e) {
            ok = false;
        }
        return ok;
    }

    private static boolean delete(File file) {
        return !file.exists() || file.delete();
    }
}
