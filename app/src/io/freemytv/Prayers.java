package io.freemytv;

import java.util.Calendar;

/**
 * Today's five prayer times for the weather screensaver, each with when its time (waqt) ends: Fajr at sunrise, Dhuhr
 * at Asr's shadow, Asr at sunset, Maghrib at Isha, Isha at the next day's dawn; and today's sunrise and sunset. Worked
 * out here, from the sun's position (NOAA / Meeus) at the location in the config, with nothing fetched: each event is
 * solved at its own moment, refined until it settles (within 4 s of NASA's DE421 ephemeris, Asr 9 s, 2026–2027).
 * Sunrise and sunset when the sun's upper edge meets the horizon (0.833° below it, refraction included).
 *
 * Fajr at dawn and Isha at dusk with the sun 18° below the horizon; Asr when a shadow is once (Shafi'i) or twice
 * (Hanafi) the thing plus its noon shadow (config `prayer_school`). Local timetables add a few minutes after noon,
 * Asr, sunset and dusk: config `prayer_minutes` (e.g. 15,2,7,15 matches a south Indian district timetable within a
 * minute on 85–94 of 95 days). Starts are rounded up and ends down, so the screen is never early for a prayer nor
 * late for its end.
 */
final class Prayers {
    static final String[] NAMES = {"FJR", "DHR", "ASR", "MGB", "ISH"};
    private static final int SCHOOL = Config.PRAYER_SCHOOL;   // 1 = Hanafi Asr (a shadow twice as long); 0 = Shafi'i
    private static final double DHUHR_AFTER_NOON = Config.PRAYER_MINUTES[0], ASR_MARGIN = Config.PRAYER_MINUTES[1],
            MAGHRIB_AFTER_SUNSET = Config.PRAYER_MINUTES[2], ISHA_AFTER_DUSK = Config.PRAYER_MINUTES[3];   // minutes

    static final class Day {
        final String key;
        final long[] start = new long[5], end = new long[5];
        long sunrise, sunset;
        final boolean friday;
        Day(String key, boolean friday) { this.key = key; this.friday = friday; }
        String name(int i) { return i == 1 && friday ? "JUM" : NAMES[i]; }
    }

    private static final Prayers INSTANCE = new Prayers();

    static Prayers get(android.content.Context c) {
        return INSTANCE;
    }

    volatile Day today;

    /** Works out today's times when the day (or the location) changes. The screen calls this every minute. */
    void refreshIfStale() {
        Calendar day = midnight(System.currentTimeMillis());
        String key = key(day) + "|" + Prefs.latitude() + "," + Prefs.longitude();
        Day d = today;
        if (d == null || !d.key.equals(key)) today = build(day, key);
    }

    private static Calendar midnight(long at) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(at);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }

    private static String key(Calendar day) {
        return day.get(Calendar.YEAR) + "-" + (day.get(Calendar.MONTH) + 1) + "-" + day.get(Calendar.DAY_OF_MONTH);
    }

    private static Day build(Calendar day, String key) {
        Calendar next = (Calendar) day.clone();
        next.add(Calendar.DAY_OF_MONTH, 1);
        double[] t = times(day), tomorrow = times(next);   // fajr, sunrise, noon, asr, sunset, dusk (exact, epoch ms)
        Day d = new Day(key, day.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY);
        final double min = 60_000;
        double[][] span = {   // each prayer's start (with the timetable's margin) and the true end of its time
                {t[0], t[1]}, {t[2] + DHUHR_AFTER_NOON * min, t[3]}, {t[3] + ASR_MARGIN * min, t[4]},
                {t[4] + MAGHRIB_AFTER_SUNSET * min, t[5] + ISHA_AFTER_DUSK * min}, {t[5] + ISHA_AFTER_DUSK * min, tomorrow[0]},
        };
        for (int i = 0; i < 5; i++) { d.start[i] = up(span[i][0]); d.end[i] = down(span[i][1]); }
        d.sunrise = down(t[1]);
        d.sunset = down(t[4]);
        return d;
    }

    // 10 s more than the maths can be off (9 s at worst against the ephemeris), so the rounding is always safe.
    private static long up(double ms) { return (long) Math.ceil((ms + 10_000) / 60_000) * 60_000L; }
    private static long down(double ms) { return (long) Math.floor((ms - 10_000) / 60_000) * 60_000L; }

    /** fajr, sunrise, noon, asr, sunset, dusk (18° below) on that local day, in epoch ms, unrounded. */
    static double[] times(Calendar day) {
        double lat = Double.parseDouble(Prefs.latitude()), lon = Double.parseDouble(Prefs.longitude());
        int y = day.get(Calendar.YEAR), m = day.get(Calendar.MONTH) + 1, dd = day.get(Calendar.DAY_OF_MONTH);
        double jd0 = julian(y, m, dd);
        long utcMidnight = java.util.Date.UTC(y - 1900, m - 1, dd, 0, 0, 0);
        double noon = 720 - 4 * lon;
        for (int i = 0; i < 4; i++) noon = 720 - 4 * lon - sun(jd0 + noon / 1440)[1];
        double[] minutes = {
                event(jd0, lat, lon, noon, -18, true, false), event(jd0, lat, lon, noon, -0.833, true, false), noon,
                event(jd0, lat, lon, noon, 0, false, true), event(jd0, lat, lon, noon, -0.833, false, false),
                event(jd0, lat, lon, noon, -18, false, false),
        };
        double[] out = new double[6];
        for (int i = 0; i < 6; i++) out[i] = utcMidnight + minutes[i] * 60_000;
        return out;
    }

    /** Minutes after 00:00 UT when the sun is at `altitude` (or, for Asr, at its shadow altitude) before or after noon. */
    private static double event(double jd0, double lat, double lon, double noon, double altitude, boolean before, boolean asr) {
        double t = noon, la = Math.toRadians(lat);
        for (int i = 0; i < 6; i++) {
            double[] s = sun(jd0 + t / 1440);
            double h = asr ? Math.toDegrees(Math.atan(1 / (1 + SCHOOL + Math.tan(Math.abs(la - s[0]))))) : altitude;
            double cosH = (Math.sin(Math.toRadians(h)) - Math.sin(la) * Math.sin(s[0])) / (Math.cos(la) * Math.cos(s[0]));
            double hourAngle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cosH))));
            double solarNoon = 720 - 4 * lon - s[1];
            t = before ? solarNoon - 4 * hourAngle : solarNoon + 4 * hourAngle;
        }
        return t;
    }

    /** {declination (radians), equation of time (minutes)} at a Julian day (UT). */
    private static double[] sun(double jd) {
        double T = (jd - 2451545) / 36525;
        double l0 = ((280.46646 + T * (36000.76983 + T * 0.0003032)) % 360 + 360) % 360;
        double m = 357.52911 + T * (35999.05029 - 0.0001537 * T);
        double e = 0.016708634 - T * (0.000042037 + 0.0000001267 * T);
        double c = Math.sin(Math.toRadians(m)) * (1.914602 - T * (0.004817 + 0.000014 * T))
                + Math.sin(Math.toRadians(2 * m)) * (0.019993 - 0.000101 * T) + Math.sin(Math.toRadians(3 * m)) * 0.000289;
        double omega = 125.04 - 1934.136 * T;
        double lambda = l0 + c - 0.00569 - 0.00478 * Math.sin(Math.toRadians(omega));
        double eps = 23 + (26 + (21.448 - T * (46.815 + T * (0.00059 - T * 0.001813))) / 60) / 60 + 0.00256 * Math.cos(Math.toRadians(omega));
        double decl = Math.asin(Math.sin(Math.toRadians(eps)) * Math.sin(Math.toRadians(lambda)));
        double y = Math.pow(Math.tan(Math.toRadians(eps / 2)), 2), L = Math.toRadians(l0), M = Math.toRadians(m);
        double eqt = 4 * Math.toDegrees(y * Math.sin(2 * L) - 2 * e * Math.sin(M) + 4 * e * y * Math.sin(M) * Math.cos(2 * L)
                - 0.5 * y * y * Math.sin(4 * L) - 1.25 * e * e * Math.sin(2 * M));
        return new double[] {decl, eqt};
    }

    private static double julian(int y, int m, int d) {
        if (m <= 2) { y--; m += 12; }
        double a = Math.floor(y / 100.0), b = 2 - a + Math.floor(a / 4);
        return Math.floor(365.25 * (y + 4716)) + Math.floor(30.6001 * (m + 1)) + d + b - 1524.5;
    }

}
