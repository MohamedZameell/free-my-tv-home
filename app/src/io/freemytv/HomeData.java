package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Optional crypto prices (Binance, every 5 min) and weather/sunrise/sunset (Open-Meteo, every 30 min), cached
 * so the screen opens with numbers. Only fetched while the home screen or the screensaver is visible (the screen asks).
 * Neither source needs a key. Location and currency come from config.properties.
 */
final class HomeData {
    static final String[] COINS = {"BTC", "ETH", "SOL", "USDT"};
    static final String[] COIN_NAMES = {"Bitcoin", "Ethereum", "Solana", "Dollar"};
    private static final String CURRENCY = Config.CURRENCY.trim().toUpperCase(Locale.ROOT);
    /** Binance quotes dollars as USDT. */
    private static final String QUOTE = CURRENCY.equals("USD") ? "USDT" : CURRENCY;
    private static final String WEATHER_QUERY = "&current=temperature_2m,weather_code,is_day,apparent_temperature,relative_humidity_2m,dew_point_2m,"
            + "cloud_cover,wind_speed_10m,wind_direction_10m,precipitation"
            + "&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset,uv_index_max"
            + "&hourly=temperature_2m,weather_code,is_day,cloud_cover,precipitation_probability,visibility&timezone=auto&forecast_days=2";

    private static String weatherPlace() {
        return Prefs.latitude() + "," + Prefs.longitude();
    }

    private static String weatherUrl() {
        return "https://api.open-meteo.com/v1/forecast?latitude=" + Prefs.latitude() + "&longitude=" + Prefs.longitude() + WEATHER_QUERY;
    }
    private static final long PRICES_EVERY = 5 * 60_000L, WEATHER_EVERY = 15 * 60_000L;
    interface Listener { void onData(); }

    /** BTC, ETH and SOL; in Brazilian reais also the dollar (USDT/BRL), which Binance doesn't list for most currencies. */
    static int coinCount() {
        return Prefs.quotes() ? active().length : 0;
    }

    private static volatile int[] activeCoins;
    private static volatile int activeVersion = -1;

    /** Indexes in COINS of the coins the user has on (and that Binance lists in this currency). */
    static int[] active() {
        int[] a = activeCoins;
        if (a != null && activeVersion == Prefs.version) return a;
        int n = 0;
        int[] tmp = new int[COINS.length];
        for (int i = 0; i < COINS.length; i++) if (Prefs.coin(i) && (i < 3 || CURRENCY.equals("BRL"))) tmp[n++] = i;
        a = java.util.Arrays.copyOf(tmp, n);
        activeCoins = a;
        activeVersion = Prefs.version;
        return a;
    }

    /** Settings changed (city, coins): fetch again now. */
    void settingsChanged() {
        pricesAt = 0;
        weatherAt = 0;
    }

    static String coinName(int i) {
        return i == 3 ? Ui.tr("Dólar", "Dollar") : COIN_NAMES[i];
    }

    private static String pricesUrl() {
        StringBuilder symbols = new StringBuilder();
        int[] coins = active();
        for (int i = 0; i < coins.length; i++) symbols.append(i == 0 ? "" : ",").append("%22").append(COINS[coins[i]]).append(QUOTE).append("%22");
        return "https://api.binance.com/api/v3/ticker/24hr?type=MINI&symbols=%5B" + symbols + "%5D";
    }

    static final class Weather {
        double temp = Double.NaN, max = Double.NaN, min = Double.NaN;
        int code = -1;
        boolean isDay = true;
        long sunrise, sunset;   // today's, in epoch ms
        // For the weather screensaver (NaN when the cached forecast predates them).
        double feels = Double.NaN, humidity = Double.NaN, dew = Double.NaN, cover = Double.NaN, wind = Double.NaN, windDir = Double.NaN;
        double rain = Double.NaN, uv = Double.NaN, visibility = Double.NaN;   // rain in mm, visibility in km
        // Next hours (starting at the next full hour): time, temperature, code and day/night, cloud cover and chance of rain (%).
        final List<Long> hours = new ArrayList<>();
        final List<Double> hourTemp = new ArrayList<>();
        final List<Integer> hourCode = new ArrayList<>();
        final List<Boolean> hourDay = new ArrayList<>();
        final List<Double> hourCover = new ArrayList<>();
        final List<Double> hourChance = new ArrayList<>();
    }

    private static HomeData instance;

    static synchronized HomeData get(Context c) {
        if (instance == null) instance = new HomeData(c.getApplicationContext());
        return instance;
    }

    final double[] prices = {Double.NaN, Double.NaN, Double.NaN, Double.NaN};
    /** 24 h change (%), in the same order. */
    final double[] changes = {Double.NaN, Double.NaN, Double.NaN, Double.NaN};
    volatile long pricesAt, weatherAt;
    volatile Weather weather = new Weather();
    /** The weather to show now (see {@link #current}): what the screens draw. It changes at most once an hour offline. */
    volatile Weather shown = new Weather();
    private int shownHour = -2;   // which forecast hour `shown` came from (-1: the fetched reading itself)

    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private volatile boolean fetchingPrices, fetchingWeather;

    private HomeData(Context c) {
        cache = c.getSharedPreferences("homedata", Context.MODE_PRIVATE);
        try {
            if (Prefs.quotes()) {
                String p = cache.getString("prices", null);
                if (p != null) parsePrices(p);
                pricesAt = cache.getLong("pricesAt", 0);
            }
            String w = cache.getString("weather", null);
            // The cached forecast is for the city it was fetched for; another city means fetch again.
            if (w != null && weatherPlace().equals(cache.getString("weatherPlace", ""))) {
                weather = parseWeather(w);
                // Saved before the screensaver needed the extra readings: fetch again at once.
                weatherAt = Double.isNaN(weather.feels) ? 0 : cache.getLong("weatherAt", 0);
            }
        } catch (Exception ignored) {}
        updateShown(System.currentTimeMillis());
    }

    private static boolean sameDay(long a, long b) {
        java.util.Calendar x = java.util.Calendar.getInstance(), y = java.util.Calendar.getInstance();
        x.setTimeInMillis(a);
        y.setTimeInMillis(b);
        return x.get(java.util.Calendar.YEAR) == y.get(java.util.Calendar.YEAR) && x.get(java.util.Calendar.DAY_OF_YEAR) == y.get(java.util.Calendar.DAY_OF_YEAR);
    }

    private static final long FRESH = 90 * 60_000L;   // a reading older than this (no internet) gives way to the forecast

    /**
     * Without internet the last reading gets old, so the forecast fetched with it stands in: each hour's forecast
     * becomes "now" when its hour comes, for as long as it lasts (24 h). After that, nothing (shown as "—") rather
     * than a stale number. Sunrise and sunset don't need this: Prayers works them out on the TV.
     */
    private synchronized void updateShown(long now) {
        Weather w = weather;
        int hour;
        if (now >= weatherAt && now - weatherAt < FRESH) hour = -1;
        else {
            hour = 0;
            while (hour < w.hours.size() && w.hours.get(hour) <= now - 30 * 60_000L) hour++;   // the nearest hour
            if (hour >= w.hours.size() || w.hours.get(hour) - now > 30 * 60_000L) hour = w.hours.size();   // ran out
        }
        if (hour == shownHour && (hour != -1 || shown == w)) return;
        shownHour = hour;
        if (hour == -1) { shown = w; return; }
        Weather f = new Weather();
        f.sunrise = w.sunrise;
        f.sunset = w.sunset;
        if (sameDay(now, weatherAt)) { f.max = w.max; f.min = w.min; f.uv = w.uv; }   // the day's high, low and UV still hold
        if (hour < w.hours.size()) {
            f.temp = w.hourTemp.get(hour);
            f.code = w.hourCode.get(hour);
            f.isDay = w.hourDay.get(hour);
            f.cover = w.hourCover.get(hour);
            for (int i = hour + 1; i < w.hours.size(); i++) {
                f.hours.add(w.hours.get(i));
                f.hourTemp.add(w.hourTemp.get(i));
                f.hourCode.add(w.hourCode.get(i));
                f.hourDay.add(w.hourDay.get(i));
                f.hourCover.add(w.hourCover.get(i));
                f.hourChance.add(w.hourChance.get(i));
            }
        }
        shown = f;
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    /** Fetches whatever is stale. The screen calls this when it appears and every minute while it's visible. */
    void refreshIfStale() {
        long now = System.currentTimeMillis();
        Weather before = shown;
        updateShown(now);
        if (shown != before) notifyListeners();
        // A negative age (the TV's clock went back) counts as stale: otherwise the data would freeze.
        if (!fetchingPrices && coinCount() > 0 && (now - pricesAt > PRICES_EVERY || now < pricesAt)) {
            fetchingPrices = true;
            io.execute(() -> {
                try {
                    String body = get(pricesUrl());
                    parsePrices(body);
                    pricesAt = System.currentTimeMillis();
                    cache.edit().putString("prices", body).putLong("pricesAt", pricesAt).apply();
                    notifyListeners();
                } catch (Exception e) {
                    AppLog.w("prices: " + e.getMessage());
                } finally {
                    fetchingPrices = false;
                }
            });
        }
        if (!fetchingWeather && (now - weatherAt > WEATHER_EVERY || now < weatherAt)) {
            fetchingWeather = true;
            io.execute(() -> {
                try {
                    String body = get(weatherUrl());
                    weather = parseWeather(body);
                    weatherAt = System.currentTimeMillis();
                    cache.edit().putString("weather", body).putLong("weatherAt", weatherAt).putString("weatherPlace", weatherPlace()).apply();
                    updateShown(weatherAt);
                    notifyListeners();
                } catch (Exception e) {
                    AppLog.w("weather: " + e.getMessage());
                } finally {
                    fetchingWeather = false;
                }
            });
        }
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onData(); });
    }

    private void parsePrices(String body) throws Exception {
        JSONArray list = new JSONArray(body);
        String quote = QUOTE;
        int found = 0;
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.getJSONObject(i);
            String symbol = o.getString("symbol");
            for (int k = 0; k < COINS.length; k++) {
                if (!symbol.equals(COINS[k] + quote)) continue;
                found++;
                double last = Double.parseDouble(o.optString("lastPrice", o.optString("price", "NaN")));
                double open = Double.parseDouble(o.optString("openPrice", "NaN"));
                prices[k] = last;
                changes[k] = open > 0 ? (last - open) / open * 100 : Double.NaN;
            }
        }
        if (found == 0) pricesAt = 0;   // cached in another currency (config changed): fetch again
    }

    private static Weather parseWeather(String body) throws Exception {
        JSONObject o = new JSONObject(body);
        TimeZone zone = TimeZone.getTimeZone(o.optString("timezone", TimeZone.getDefault().getID()));
        Weather w = new Weather();
        JSONObject current = o.getJSONObject("current");
        w.temp = current.getDouble("temperature_2m");
        w.code = current.getInt("weather_code");
        w.isDay = current.optInt("is_day", 1) == 1;
        w.feels = current.optDouble("apparent_temperature");
        w.humidity = current.optDouble("relative_humidity_2m");
        w.dew = current.optDouble("dew_point_2m");
        w.cover = current.optDouble("cloud_cover");
        w.wind = current.optDouble("wind_speed_10m");
        w.windDir = current.optDouble("wind_direction_10m");
        w.rain = current.optDouble("precipitation");
        JSONObject daily = o.getJSONObject("daily");
        JSONArray uv = daily.optJSONArray("uv_index_max");
        if (uv != null) w.uv = uv.optDouble(0);
        w.max = daily.getJSONArray("temperature_2m_max").getDouble(0);
        w.min = daily.getJSONArray("temperature_2m_min").getDouble(0);
        w.sunrise = localTime(daily.getJSONArray("sunrise").getString(0), zone);
        w.sunset = localTime(daily.getJSONArray("sunset").getString(0), zone);
        JSONObject hourly = o.optJSONObject("hourly");
        if (hourly != null) {
            JSONArray times = hourly.getJSONArray("time");
            JSONArray temps = hourly.getJSONArray("temperature_2m");
            JSONArray codes = hourly.getJSONArray("weather_code");
            JSONArray days = hourly.optJSONArray("is_day");
            JSONArray covers = hourly.optJSONArray("cloud_cover");
            JSONArray chances = hourly.optJSONArray("precipitation_probability");
            JSONArray sight = hourly.optJSONArray("visibility");
            long now = System.currentTimeMillis();
            for (int i = 0; i < times.length() && w.hours.size() < 24; i++) {
                long at = localTime(times.getString(i), zone);
                if (at <= now) {
                    if (at > now - 3_600_000L && sight != null) w.visibility = sight.optDouble(i) / 1000;   // this hour's, in km
                    continue;
                }
                w.hours.add(at);
                w.hourTemp.add(temps.getDouble(i));
                w.hourCode.add(codes.getInt(i));
                w.hourDay.add(days == null || days.optInt(i, 1) == 1);
                w.hourCover.add(covers == null ? Double.NaN : covers.optDouble(i));
                w.hourChance.add(chances == null ? 0 : chances.optDouble(i, 0));
            }
        }
        return w;
    }

    private static long localTime(String iso, TimeZone zone) throws ParseException {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
        f.setTimeZone(zone);
        return f.parse(iso).getTime();
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(6000);
        c.setRequestProperty("User-Agent", "FreeMyTV");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }

    /**
     * Night at your location: from sunset to sunrise (like Apple's "Automatic" appearance: the light design darkens).
     * Uses the last weather fetched (it moves ~1 min a day); without data, 6:00 and 18:00.
     */
    boolean isNight(long now) {
        String theme = Prefs.theme();
        if (theme.equals("dark")) return true;
        if (theme.equals("light")) return false;
        long t = Shaders.timeOfDay(now, 0);
        long rise = Shaders.timeOfDay(weather.sunrise, 6 * 60 * 60_000L);
        long set = Shaders.timeOfDay(weather.sunset, 18 * 60 * 60_000L);
        return t < rise || t >= set;
    }

    // MARK: - Formatting

    private static Locale locale() {
        return Ui.locale();
    }

    /** 416.3k · 12.2k · 1.04k · 531 · 5.05: abbreviated, for the price row on the home screen. */
    static String compact(double v) {
        if (Double.isNaN(v)) return "—";
        if (v >= 10_000) return String.format(locale(), "%.1fk", v / 1000);
        if (v >= 1000) return String.format(locale(), "%.2fk", v / 1000);
        if (v >= 100) return String.format(locale(), "%.0f", v);
        return String.format(locale(), "%.2f", v);
    }

    /** $108,234 · $185.20 · R$ 581.234 · R$ 5,61 (cents only below a thousand), in the configured currency. */
    static String brl(double v) {
        String symbol = currencySymbol();
        if (Double.isNaN(v)) return symbol + "—";
        return Ui.once("brl", v, () -> v >= 1000 ? String.format(locale(), symbol + "%,.0f", v)
                : String.format(locale(), symbol + "%.2f", v));
    }

    /** The weather in words, from Open-Meteo's WMO code. */
    static String condition(int code, boolean day) {
        if (code < 0) return "";
        if (Ui.en) {
            if (code == 0) return "Clear";
            if (code == 1) return day ? "Mostly Sunny" : "Mostly Clear";
            if (code == 2) return "Partly Cloudy";
            if (code == 3) return "Cloudy";
            if (code == 45 || code == 48) return "Fog";
            if (code >= 51 && code <= 57) return "Drizzle";
            if (code == 65 || code == 67 || code == 82) return "Heavy Rain";
            if (code >= 71 && code <= 77 || code == 85 || code == 86) return "Snow";
            if (code >= 95) return "Thunderstorms";
            return "Rain";
        }
        if (Ui.es) {
            if (code == 0) return day ? "Despejado" : "Noche despejada";
            if (code == 1) return "Mayormente despejado";
            if (code == 2) return "Parcialmente nublado";
            if (code == 3) return "Nublado";
            if (code == 45 || code == 48) return "Niebla";
            if (code >= 51 && code <= 57) return "Llovizna";
            if (code == 65 || code == 67 || code == 82) return "Lluvia fuerte";
            if (code >= 71 && code <= 77 || code == 85 || code == 86) return "Nieve";
            if (code >= 95) return "Tormenta";
            return "Lluvia";
        }
        if (code == 0) return day ? "Céu limpo" : "Noite limpa";
        if (code == 1) return "Poucas nuvens";
        if (code == 2) return "Parcialmente nublado";
        if (code == 3) return "Nublado";
        if (code == 45 || code == 48) return "Neblina";
        if (code >= 51 && code <= 57) return "Garoa";
        if (code == 65 || code == 67 || code == 82) return "Chuva forte";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "Neve";
        if (code >= 95) return "Tempestade";
        return "Chuva";
    }

    private static String currencySymbol() {
        switch (CURRENCY) {
            case "USD": return "$";
            case "BRL": return "R$ ";
            case "EUR": return "€";
            case "GBP": return "£";
            case "JPY": return "¥";
            default: return CURRENCY + " ";
        }
    }

    /** Celsius or Fahrenheit (config.properties "temperature": C, F, or auto = Fahrenheit in the US). */
    static String degrees(double v) {
        if (Double.isNaN(v)) return "—";
        return Math.round(fahrenheit() ? v * 9 / 5 + 32 : v) + "°";
    }

    static boolean fahrenheit() {
        String unit = Prefs.temperature();
        if (unit.equals("F")) return true;
        if (unit.equals("C")) return false;
        return "US".equals(Locale.getDefault().getCountry());
    }

    /** "+1.2%" / "−0.8%" (a real minus sign). */
    static String change(double v) {
        if (Double.isNaN(v)) return "";
        return Ui.once("change", v, () -> (v >= 0 ? "+" : "−") + String.format(locale(), "%.1f%%", Math.abs(v)));
    }

    static String hour(long at) {
        return Ui.once("hour", at, () -> Ui.en ? new SimpleDateFormat("h a", Locale.US).format(new java.util.Date(at))
                : new SimpleDateFormat(Ui.es ? "HH:mm" : "HH'h'", Ui.locale()).format(new java.util.Date(at)));
    }

    /** How long ago the data was fetched, if it's stale (no internet): "12 min ago". */
    static String staleness(long at, long every) {
        if (at == 0) return "";
        long age = System.currentTimeMillis() - at;
        if (age < every * 2) return "";
        long minutes = age / 60_000;
        if (Ui.en) return minutes < 60 ? minutes + " min ago" : (minutes / 60) + " h ago";
        if (Ui.es) return minutes < 60 ? "hace " + minutes + " min" : "hace " + (minutes / 60) + " h";
        return minutes < 60 ? "há " + minutes + " min" : "há " + (minutes / 60) + " h";
    }
}
