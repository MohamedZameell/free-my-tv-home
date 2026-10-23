package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Settings changed on the TV itself (Free My TV > Settings). config.properties only gives the first
 * values; anything the user changes here wins. Read from the :home process (home screen, screensaver, settings).
 */
final class Prefs {
    private Prefs() {}

    /** Remote keys we handle, with the app each opens until the user picks another. Codes read from the TCL's log. */
    static final int KEY_NETFLIX = 4062, KEY_YOUTUBE = 190, KEY_PRIME = 4099, KEY_TCL_CHANNEL = 4020;
    /** Sony LIV (TCL_SONYLIV in /tclconfig/.../input_key/shortcutkey.tkl). */
    static final int KEY_SONYLIV = 4166;
    static final int[] KEYS = {KEY_NETFLIX, KEY_YOUTUBE, KEY_PRIME, KEY_TCL_CHANNEL, KEY_SONYLIV};
    /** A remote button's value that opens the CCTV's HDMI input instead of an app. */
    static final String CCTV = "input:cctv";
    /** SmartTube, the ad-free YouTube app: the remote's YouTube button opens it (falls back to YouTube without it). */
    static final String SMARTTUBE = "org.smarttube.stable";

    /** Bumped by every change: screens compare it to know when to redraw. */
    static volatile int version;

    private static Context app;

    static void init(Context c) {
        app = c.getApplicationContext();
        Ui.applyLanguage();   // the language picked on the TV, now that it can be read
    }

    private static SharedPreferences sp() {
        return app.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    private static final String KEY_GOOD_CLOCK = "goodClockAt";

    /**
     * Whether the TV's clock can be believed. TVs have no battery clock: after a power cut it restarts at the
     * firmware's build date (TCL Q6C: 12 April 2026) and only the internet sets it. So a time near the build date, or
     * before the last time the clock was right, is wrong; the date, time and prayers hide until it's set.
     */
    static boolean clockTrusted(long now) {
        long minute = now / 60_000;   // asked every frame by the screensaver: work it out once a minute
        if (minute == trustedMinute) return trusted;
        long good = sp().getLong(KEY_GOOD_CLOCK, 0);
        boolean ok = now >= android.os.Build.TIME + 86_400_000L && now >= good - 3_600_000L;
        if (ok && now - good > 600_000L) sp().edit().putLong(KEY_GOOD_CLOCK, now).apply();
        trusted = ok;
        trustedMinute = minute;
        return ok;
    }

    private static volatile long trustedMinute = Long.MIN_VALUE;
    private static volatile boolean trusted;

    private static void put(String key, String value) {
        sp().edit().putString(key, value).apply();
        version++;
    }

    private static void put(String key, boolean value) {
        sp().edit().putBoolean(key, value).apply();
        version++;
    }

    // MARK: - Language and units

    /** "auto", "pt" or "en". */
    static String language() {
        String v = app == null ? Config.LANGUAGE : sp().getString("language", Config.LANGUAGE);
        v = v.trim().toLowerCase(Locale.ROOT);
        return v.startsWith("pt") ? "pt" : v.startsWith("es") ? "es" : v.startsWith("en") ? "en" : "auto";
    }

    static void setLanguage(String v) { put("language", v); Ui.applyLanguage(); }

    /** "auto", "C" or "F". */
    static String temperature() {
        String v = sp().getString("temperature", Config.TEMPERATURE).trim().toUpperCase(Locale.ROOT);
        return v.startsWith("F") ? "F" : v.startsWith("C") ? "C" : "auto";
    }

    static void setTemperature(String v) { put("temperature", v); }

    /** "auto" (dark from sunset to sunrise), "light" or "dark". */
    static String theme() {
        String v = sp().getString("theme", "auto");
        return v.equals("light") || v.equals("dark") ? v : "auto";
    }

    static void setTheme(String v) { put("theme", v); }

    // MARK: - City (from config.properties)

    static String city() { return Config.CITY.trim(); }
    static String latitude() { return Config.LATITUDE; }
    static String longitude() { return Config.LONGITUDE; }

    // MARK: - Widgets and coins

    static boolean weather() { return sp().getBoolean("weather", true); }
    static void setWeather(boolean on) { put("weather", on); }

    static boolean quotes() { return sp().getBoolean("quotes", Config.SHOW_CRYPTO); }
    static void setQuotes(boolean on) { put("quotes", on); }

    static boolean coin(int i) { return sp().getBoolean("coin" + i, true); }
    static void setCoin(int i, boolean on) { put("coin" + i, on); }

    // MARK: - Shelf

    /** Packages on the home shelf, in order; null until the user edits it (then the built-in list applies). */
    static List<String> shelf() {
        String v = sp().getString("shelf", null);
        if (v == null) return null;
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    static void setShelf(List<String> packages) {
        StringBuilder sb = new StringBuilder();
        for (String p : packages) sb.append(sb.length() == 0 ? "" : ",").append(p);
        put("shelf", sb.toString());
    }

    // MARK: - Remote buttons

    private static String defaultApp(int keyCode) {
        switch (keyCode) {
            case KEY_NETFLIX: return "com.netflix.ninja";
            case KEY_YOUTUBE: return SMARTTUBE;
            case KEY_PRIME: return "com.amazon.amazonvideo.livingroom";
            case KEY_TCL_CHANNEL: return CCTV;
            case KEY_SONYLIV: return "com.suntv.sunnxt";
            default: return "";
        }
    }

    /** Package the key opens ("" = nothing, CCTV = the CCTV input). */
    static String keyApp(int keyCode) {
        // Once: the TCL Channel button becomes the CCTV button (it used to default to nothing, and an input couldn't be
        // picked in Settings).
        if (keyCode == KEY_TCL_CHANNEL && !sp().getBoolean("tclChannelCctv", false)) {
            sp().edit().putString("key" + keyCode, CCTV).putBoolean("tclChannelCctv", true).apply();
        }
        // Once: the YouTube button moves to SmartTube (it opened the official app before).
        if (keyCode == KEY_YOUTUBE && !sp().getBoolean("youtubeSmartTube", false)) {
            sp().edit().putString("key" + keyCode, SMARTTUBE).putBoolean("youtubeSmartTube", true).apply();
        }
        return sp().getString("key" + keyCode, defaultApp(keyCode));
    }
    static void setKeyApp(int keyCode, String pkg) { put("key" + keyCode, pkg); }
}
