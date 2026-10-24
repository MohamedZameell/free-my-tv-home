package io.freemytv;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The weather screensaver (after the Not Boring Weather app), on pitch black for the TV: clouds of matte, grainy
 * spheres (white by day, black at night) as many as the cloud cover, centred; the temperature as a big bevelled
 * number with the sun or the moon (in its real phase) orbiting it; rain, snow, fog and lightning from the forecast;
 * the readings on the right, today's prayer times on the left, the next 21 hours along the bottom, and a faint
 * eight-point-star lattice. Weather from HomeData (Open-Meteo), prayer times from Prayers (Aladhan).
 *
 * At 60 frames a second on the TV's GPU: every image is made once, off the UI thread, read-only (the GPU keeps its
 * texture; a mutable bitmap is uploaded again on every draw) and mipmapped where it's drawn smaller. The background
 * (black and the lattice) is one opaque image, drawn without blending; the cloud textures share one atlas so their
 * ~90 draws batch; stars and the dotted orbit are pre-drawn (no path work per frame); the number is cropped to its
 * glyphs. Drawn in a 1920×1080 design space; nothing allocated per frame.
 */
final class WeatherView extends View implements Choreographer.FrameCallback, HomeData.Listener {
    private static final float W = 1920, H = 1080, TAU = (float) (Math.PI * 2);
    private static final float F = 1600, CX = 960, CY = 350;
    private static final int BLACK = 0xFF000000, INK = 0xFFF2F2F4, RED = 0xFFFF2B45, BLUE = 0xFF1EA7FF, NIGHT_BLUE = 0xFF7CC8FF;
    private static final int DAY_CLOUD = 0xFFF4F4F6, NIGHT_CLOUD = 0xFF2A2C32;
    private static final float LX, LY, LZ;   // the key light, from the top left and in front
    static {
        float l = (float) Math.sqrt(0.55 * 0.55 + 0.75 * 0.75 + 0.7 * 0.7);
        LX = -0.55f / l; LY = -0.75f / l; LZ = 0.7f / l;
    }

    // Weather kinds (from the WMO code).
    private static final int CLEAR = 0, MOSTLY = 1, PARTLY = 2, CLOUDY = 3, FOG = 4, DRIZZLE = 5, RAIN = 6, HEAVY = 7, SNOW = 8, HEAVY_SNOW = 9, STORM = 10;
    private static final float[] KIND_COVER = {5, 25, 50, 95, 70, 80, 90, 100, 90, 100, 100};

    private final HomeData data;
    private final Prayers prayers;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private boolean running;
    private long lastDraw;
    private float T, reveal;

    // What the scene shows now; it eases toward the weather.
    private float cover, rain, snow, fog, thunder, night;

    // MARK: - Images, made once off the UI thread

    private interface Shade { void at(float px, float py, float nz, float[] o); }   // o = r, g, b, alpha (0..1), grain

    /** Everything drawn as an image. */
    private static final class Art {
        Bitmap cloudDay, cloudNight, rimSun, rimMoon;   // the parts; the clouds drawn are made from them (litDay, litNight)
        Bitmap sun, shadow, flake, halo, fogDay, fogNight, grainTile, starFill, starLine, ringFront, ringBack;
        Bitmap lattice;      // alpha only (a quarter of the bytes for the GPU to read each frame), faded toward the middle
        LinearGradient band; // its brightness: 7%, more in the band of light (moved each frame)
    }

    // The clouds as drawn: with the sun's (or the moon's) rim at its current strength baked in, so each sphere is one draw.
    private volatile Bitmap litDay, litNight;
    private int litFor = -1;
    private boolean litPending;
    // The band of light: across its width (along its normal) the lattice goes from 7% to 21% in the middle, soft edges.
    private static final int SWEEP_W = 290;
    private static final float SWEEP_NX = 1080 / (float) Math.hypot(1080, 500), SWEEP_NY = 500 / (float) Math.hypot(1080, 500);

    // Kept between screensaver runs (the :home process stays): every start after the first is instant.
    private static volatile Art cachedArt;
    private static volatile Bitmap moon;
    private static float moonFor = -1;
    private volatile Art art;
    private boolean moonPending;

    /** The number for a value and glow: cropped to its glyphs. */
    private static final class Numeral {
        final Bitmap image;
        final int left, top;
        Numeral(Bitmap image, int left, int top) { this.image = image; this.left = left; this.top = top; }
    }

    private static final HashMap<String, Numeral> numerals = new HashMap<>();   // UI thread only
    private final HashSet<String> numeralsPending = new HashSet<>();
    private Numeral lastDay, lastNight;   // shown while the next one is made

    // MARK: - Clouds: clusters of spheres; how many show depends on the cloud cover, the visible ones centred

    private static final class Cluster {
        float x, pres;
        final float y, z, w;
        final int i;
        final float[] sx, sy, sz, sr, sph;
        Cluster(int i, float x, float y, float z, float w, int seed) {
            this.i = i; this.x = x; this.y = y; this.z = z; this.w = w;
            Mulberry r = new Mulberry(seed);
            int n = 5 + (int) (r.next() * 2), m = 3 + (int) (r.next() * 2), total = n + m + 1;
            sx = new float[total]; sy = new float[total]; sz = new float[total]; sr = new float[total]; sph = new float[total];
            int k = 0;
            for (int j = 0; j < n; j++, k++) {
                float t = j / (float) (n - 1), edge = 1 - (float) Math.pow(Math.abs(t - 0.5f) * 2, 1.6);
                float rad = w * 0.1f + edge * w * 0.15f * (0.75f + r.next() * 0.5f);
                sx[k] = (t - 0.5f) * w * 0.8f + (r.next() - 0.5f) * 30; sy[k] = -edge * w * 0.12f - rad * 0.3f + (r.next() - 0.5f) * 20;
                sz[k] = (r.next() - 0.5f) * 90; sr[k] = rad; sph[k] = r.next() * TAU;
            }
            for (int j = 0; j < m; j++, k++) {
                float t = (j + 0.5f) / m;
                sx[k] = (t - 0.5f) * w * 0.85f; sy[k] = w * 0.06f + (r.next() - 0.5f) * 14; sz[k] = -40 + (r.next() - 0.5f) * 80;
                sr[k] = w * (0.07f + r.next() * 0.04f); sph[k] = r.next() * TAU;
            }
            sx[k] = (r.next() - 0.5f) * w * 0.5f; sy[k] = -w * 0.02f + (r.next() - 0.5f) * 30; sz[k] = -80 - r.next() * 40;
            sr[k] = w * (0.08f + r.next() * 0.05f); sph[k] = r.next() * TAU;
        }
    }

    /** The same small random sequence every time (the clouds keep their shapes). */
    private static final class Mulberry {
        private int a;
        Mulberry(int seed) { a = seed; }
        float next() {
            a += 0x6D2B79F5;
            int t = (a ^ (a >>> 15)) * (1 | a);
            t = (t + ((t ^ (t >>> 7)) * (61 | t))) ^ t;
            return ((t ^ (t >>> 14)) & 0xFFFFFFFFL) / 4294967296f;
        }
    }

    // In the order they appear (the big one first) and where each sits for 1, 2, 3 or 4 visible.
    private final Cluster[] clusters = {
            new Cluster(0, 0, 0, 0, 560, 3), new Cluster(1, 330, 30, -90, 420, 7),
            new Cluster(2, -390, -20, 150, 330, 11), new Cluster(3, 560, -90, 460, 340, 19),
    };
    // 3 and 4 stay between x≈414 and ≈1490 through the camera sway: clear of the quote and the readings at x 1540.
    private static final float[][] LAYOUT = {{0}, {-180, 300}, {-40, 290, -380}, {-60, 270, -380, 400}};
    // Small eight-point stars drifting around the clouds, and specks of dust (some of them star outlines).
    private static final float[][] STARS = {{0, -150, -150, -60}, {0, 110, -40, -120}, {1, 120, -90, -100}, {2, -60, 40, -110}};
    private static final int SPECKS = 34;
    private final float[] spX = new float[SPECKS], spY = new float[SPECKS], spZ = new float[SPECKS], spS = new float[SPECKS], spPh = new float[SPECKS], spRot = new float[SPECKS];
    private final boolean[] spStar = new boolean[SPECKS];

    // Everything in the sky, sorted back to front each frame.
    private static final int ITEMS = 120;
    private final float[] itZ = new float[ITEMS], itX = new float[ITEMS], itY = new float[ITEMS], itR = new float[ITEMS], itA = new float[ITEMS];
    private final int[] itKind = new int[ITEMS], itRef = new int[ITEMS], sortOrder = new int[ITEMS];
    private int items;

    // Rain and snow.
    private static final int DROPS = 900;
    private final float[] dX = new float[DROPS], dY = new float[DROPS], dZ = new float[DROPS], dVx = new float[DROPS], dVy = new float[DROPS];
    private final float[] dAge = new float[DROPS], dLen = new float[DROPS], dW = new float[DROPS], dPh = new float[DROPS];
    private final byte[] dKind = new byte[DROPS];   // 1 rain streak, 2 rain dot (drizzle), 3 snow
    private int drops;
    private float spawnRain, spawnSnow;
    // Rain is drawn in batches: drops grouped by how faded (8 steps) and how thick (4 steps), one draw call per group.
    private static final int FADES = 8, WIDTHS = 4, GROUPS = FADES * WIDTHS;
    private final float[][] groupPts = new float[GROUPS][DROPS * 4];
    private final int[] groupLen = new int[GROUPS];

    // Lightning.
    private boolean boltOn, boltPending;
    private volatile Bolt nextBolt0;
    private Bolt boltNow;
    private float flashLight;   // this frame's flash (0..0.3): the sky drawn as if under a white veil of that strength
    private final android.graphics.ColorFilter[] flashFilters = new android.graphics.ColorFilter[65];

    /** A bolt of lightning, drawn ahead into an image (with its grey offset shadow), and where it goes. */
    private static final class Bolt {
        final Bitmap image;
        final float x, y;
        Bolt(Bitmap image, float x, float y) { this.image = image; this.x = x; this.y = y; }
    }
    private float boltAge, nextBolt = 2, flash;

    // The camera's slow sway (its sines worked out once a frame), and the last projection.
    private float yawCos = 1, yawSin, pitchCos = 1, pitchSin, pX, pY, pS, pZ;

    // The sun or moon on its tilted ring around the number.
    private static final float ORB_CY = 640, ORB_RX = 420, ORB_RY = 118, ORB_TILT = -0.13f, ORB_PERIOD = 42, ORB_R = 78;
    private float orbX, orbY, orbR;
    private boolean orbFront;

    // The number's box (for the unit and the readings' pointer lines).
    private float numWidth, numRight, numTop, numMid;

    // MARK: - Text, rebuilt when the data or the minute changes

    private final Paint mono, top, label, unit, prayer, slotTime, slotTemp, slotRain, measure, quoteRef;
    private final android.text.TextPaint quote;
    private final Typeface numeralFace;
    // Reused drawing objects.
    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG), tint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG),
            fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG), grain = new Paint(), latticePaint = new Paint();
    private final Matrix grainMatrix = new Matrix(), sweepMatrix = new Matrix();
    private BitmapShader grainShader;
    private final RectF rect = new RectF();
    private final Path path = new Path();

    private final Runnable minute = new Runnable() {
        @Override public void run() {
            data.refreshIfStale();
            prayers.refreshIfStale();
            refreshText();
            handler.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000);
        }
    };

    WeatherView(Context c) {
        super(c);
        data = HomeData.get(c);
        prayers = Prayers.get(c);
        AssetManager assets = c.getAssets();
        Typeface antonio700 = font(assets, "fonts/Antonio.ttf", 700), antonio600 = font(assets, "fonts/Antonio.ttf", 600);
        Typeface antonio500 = font(assets, "fonts/Antonio.ttf", 500), antonio200 = font(assets, "fonts/Antonio.ttf", 200);
        Typeface jet = font(assets, "fonts/JetBrainsMono.ttf", 500);
        mono = text(jet, 24, 1);
        top = text(jet, 24, 4);
        top.setTextAlign(Paint.Align.CENTER);
        label = text(antonio600, 54, 4);
        unit = text(antonio500, 64, 0);
        prayer = text(antonio200, 30, 0.5f);
        slotTime = text(jet, 18, 0);
        slotTime.setTextAlign(Paint.Align.CENTER);
        slotTemp = text(antonio500, 32, 0);
        slotTemp.setTextAlign(Paint.Align.CENTER);
        slotRain = text(jet, 18, 0);
        slotRain.setTextAlign(Paint.Align.CENTER);
        slotRain.setColor(BLUE);
        measure = text(antonio700, 520, 0);
        quote = new android.text.TextPaint(text(font(assets, "fonts/Antonio.ttf", 300), 25, 0.3f));
        quote.setAlpha(222);
        quoteRef = text(jet, 15, 1.5f);
        quoteRef.setAlpha(128);
        numeralFace = antonio700;

        Random r = new Random(42);
        for (int i = 0; i < SPECKS; i++) {
            spX[i] = -760 + r.nextFloat() * 1520; spY[i] = -300 + r.nextFloat() * 560; spZ[i] = -300 + r.nextFloat() * 900;
            spStar[i] = r.nextFloat() >= 0.7f; spS[i] = 3 + r.nextFloat() * 4; spPh[i] = r.nextFloat() * TAU; spRot[i] = r.nextFloat() * TAU;
        }
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);

        long t0 = SystemClock.uptimeMillis();
        worker.execute(() -> {
            Art a = cachedArt;
            if (a == null) {
                a = makeArt();
                cachedArt = a;
                AppLog.w("weather screensaver: images made in " + (SystemClock.uptimeMillis() - t0) + " ms");
            }
            Bitmap day = lit(a.cloudDay, a.rimSun, 1), nightCloud = lit(a.cloudNight, a.rimMoon, 1);
            Art made = a;
            handler.post(() -> {
                litDay = day; litNight = nightCloud; litFor = 32;
                art = made;
                grainShader = new BitmapShader(made.grainTile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
                grain.setShader(grainShader);
                latticePaint.setShader(made.band);   // an alpha-only image is drawn in its paint's shader
            });
        });
    }

    /** Draws every pixel itself (the window's background would be one more full-screen fill a frame). */
    @Override public boolean isOpaque() {
        return true;
    }

    private static Typeface font(AssetManager a, String file, int weight) {
        try {
            Typeface t = new Typeface.Builder(a, file).setFontVariationSettings("'wght' " + weight).build();
            if (t != null) return t;
        } catch (RuntimeException ignored) {
        }
        return Typeface.create(Typeface.DEFAULT, weight, false);
    }

    /** letter spacing in design pixels, like CSS. */
    private static Paint text(Typeface face, float size, float spacing) {
        Paint p = Ui.text(face, size, INK);
        if (spacing != 0) p.setLetterSpacing(spacing / size);
        return p;
    }

    // MARK: - Start and stop

    void start() {
        if (running) return;
        running = true;
        readTestWeather();
        lastDraw = 0;
        data.addListener(this);
        handler.post(minute);
        snapToWeather();
        Choreographer.getInstance().postFrameCallback(this);
    }

    void stop() {
        if (!running) return;
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacksAndMessages(null);
        data.removeListener(this);
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        worker.shutdownNow();
    }

    @Override public void onData() {
        refreshText();
    }

    /** Every frame of the TV's 60. */
    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        invalidate();
        Choreographer.getInstance().postFrameCallback(this);
    }

    // For testing on the TV: `adb shell setprop debug.freemytv.wx 65,1` shows weather code 65 at night ("none" = real).
    private int testCode = -2;
    private boolean testNight;

    private void readTestWeather() {
        testCode = -2;
        try {
            String v = (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, "debug.freemytv.wx");
            if (v != null && v.matches("\\d+,[01]")) {
                testCode = Integer.parseInt(v.split(",")[0]);
                testNight = v.endsWith(",1");
            }
        } catch (Exception ignored) {
        }
    }

    /** At the start: show the weather as it is (no clouds popping in on an empty sky). */
    private void snapToWeather() {
        refreshText();
        float[] tg = targets(data.shown);
        cover = tg[0]; rain = tg[1]; snow = tg[2]; fog = tg[3]; thunder = tg[4]; night = tg[5];
        int shown = 0;
        for (Cluster c : clusters) if (wants(c)) shown++;
        for (Cluster c : clusters) {
            c.pres = wants(c) ? 1 : 0;
            if (wants(c)) c.x = LAYOUT[shown - 1][c.i];
        }
    }

    // MARK: - The weather

    private static int kind(int code) {
        if (code == 0) return CLEAR;
        if (code == 1) return MOSTLY;
        if (code == 2 || code < 0) return PARTLY;
        if (code == 3) return CLOUDY;
        if (code == 45 || code == 48) return FOG;
        if (code >= 51 && code <= 57) return DRIZZLE;
        if (code == 65 || code == 67 || code == 82) return HEAVY;
        if (code == 75 || code == 86) return HEAVY_SNOW;
        if ((code >= 71 && code <= 77) || code == 85) return SNOW;
        if (code >= 95) return STORM;
        return RAIN;
    }

    private final float[] targetBuf = new float[6];

    /** cover, rain, snow, fog, thunder, night (0..1) for the weather now. */
    private float[] targets(HomeData.Weather w) {
        int k = kind(testCode >= 0 ? testCode : w.code);
        float[] t = targetBuf;
        t[0] = clamp((Double.isNaN(w.cover) || testCode >= 0 ? KIND_COVER[k] : (float) w.cover) / 100f);
        t[1] = k == DRIZZLE ? 0.25f : k == RAIN ? 0.55f : k == HEAVY ? 1 : k == STORM ? 0.75f : 0;
        t[2] = k == SNOW ? 0.45f : k == HEAVY_SNOW ? 1 : 0;
        t[3] = k == FOG ? 1 : 0;
        t[4] = k == STORM ? 1 : 0;
        // Night from the sunrise and sunset worked out on the TV (right without internet too); the forecast's otherwise.
        Prayers.Day d = prayers.today;
        long now = System.currentTimeMillis();
        t[5] = testCode >= 0 ? (testNight ? 1 : 0) : d != null && clockSet(now) ? (now < d.sunrise || now >= d.sunset ? 1 : 0) : w.isDay ? 0 : 1;
        return t;
    }

    private boolean wants(Cluster c) {
        return cover * 4.4f - c.i * 1.05f > 0.35f;
    }

    private void update(float dt) {
        HomeData.Weather w = data.shown;
        float yaw = 0.2f * sin(T * 0.12f), pitch = 0.05f * sin(T * 0.085f);
        yawCos = cos(yaw); yawSin = sin(yaw); pitchCos = cos(pitch); pitchSin = sin(pitch);
        float[] tg = targets(w);
        float k = Math.min(1, dt * 1.4f);
        cover += (tg[0] - cover) * k; rain += (tg[1] - rain) * k; snow += (tg[2] - snow) * k;
        fog += (tg[3] - fog) * k; thunder += (tg[4] - thunder) * k; night += (tg[5] - night) * k;

        int shown = 0;
        for (Cluster c : clusters) if (wants(c)) shown++;
        for (Cluster c : clusters) {
            boolean want = wants(c);
            c.pres += ((want ? 1 : 0) - c.pres) * Math.min(1, dt * 2.2f);
            if (want) {
                float tx = LAYOUT[shown - 1][c.i];
                c.x = c.pres < 0.05f ? tx : c.x + (tx - c.x) * Math.min(1, dt * 1.2f);   // glide to stay centred
            }
        }

        // Rain and snow come out of the visible clouds: light rain slow and short, heavy rain fast and long.
        float wind = Double.isNaN(w.wind) ? 0 : (float) w.wind * 7;
        int emitters = 0;
        for (Cluster c : clusters) if (c.pres > 0.5f && c.i < 3) emitters++;
        if (emitters > 0) {
            spawnRain += rain * 190 * dt;
            spawnSnow += snow * 46 * dt;
            while (spawnRain >= 1) {
                spawnRain--;
                Cluster c = emitter(emitters);
                if (drops >= DROPS) continue;
                int i = drops++;
                dX[i] = c.x + rnd(-0.38f, 0.38f) * c.w; dY[i] = c.y + c.w * 0.1f; dZ[i] = c.z + rnd(-60, 60);
                dVx[i] = wind + rain * 80; dVy[i] = lerp(250, 1900, (float) Math.pow(rain, 1.3)) * rnd(0.85f, 1.15f);
                dAge[i] = 0; dLen[i] = lerp(10, 110, rain) * rnd(0.8f, 1.2f); dW[i] = lerp(2.5f, 4.5f, rain);
                dKind[i] = (byte) (rain < 0.4f ? 2 : 1);
            }
            while (spawnSnow >= 1) {
                spawnSnow--;
                Cluster c = emitter(emitters);
                if (drops >= DROPS) continue;
                int i = drops++;
                dX[i] = c.x + rnd(-0.38f, 0.38f) * c.w; dY[i] = c.y + c.w * 0.08f; dZ[i] = c.z + rnd(-60, 60);
                dVx[i] = wind * 0.3f; dVy[i] = rnd(80, 140); dAge[i] = 0; dW[i] = rnd(5, 11); dPh[i] = rnd(0, TAU); dKind[i] = 3;
            }
        } else {
            spawnRain = spawnSnow = 0;
        }
        for (int i = drops - 1; i >= 0; i--) {
            dAge[i] += dt;
            dX[i] += (dVx[i] + (dKind[i] == 3 ? sin(T * 1.3f + dPh[i]) * 40 : 0)) * dt;
            dY[i] += dVy[i] * dt;
            if (dY[i] > 470) removeDrop(i);
        }

        if (thunder > 0.5f) {
            if (nextBolt0 == null && !boltPending) prepareBolt();
            if (!boltOn && T > nextBolt && nextBolt0 != null) { boltNow = nextBolt0; nextBolt0 = null; boltOn = true; boltAge = 0; flash = 1; }
            if (boltOn) { boltAge += dt; if (boltAge > 0.65f) { boltOn = false; nextBolt = T + rnd(2.5f, 6); } }
        } else boltOn = false;
        flash = Math.max(0, flash - dt / 0.4f);

        float a = T * TAU / ORB_PERIOD, ox = cos(a) * ORB_RX, oy = sin(a) * ORB_RY, ct = cos(ORB_TILT), st = sin(ORB_TILT), depth = sin(a);
        orbX = CX + ox * ct - oy * st;
        orbY = ORB_CY + ox * st + oy * ct + sin(T * 0.6f) * 6;
        orbR = ORB_R * (1 + 0.24f * depth);
        orbFront = depth > 0;
    }

    private Cluster emitter(int emitters) {
        int pick = random.nextInt(emitters);
        for (Cluster c : clusters) if (c.pres > 0.5f && c.i < 3 && pick-- == 0) return c;
        return clusters[0];
    }

    private void removeDrop(int i) {
        int j = --drops;
        dX[i] = dX[j]; dY[i] = dY[j]; dZ[i] = dZ[j]; dVx[i] = dVx[j]; dVy[i] = dVy[j];
        dAge[i] = dAge[j]; dLen[i] = dLen[j]; dW[i] = dW[j]; dPh[i] = dPh[j]; dKind[i] = dKind[j];
    }

    /** The next bolt, made on the worker while the sky waits for it (its shape from the big cloud, seen straight on). */
    private void prepareBolt() {
        if (worker.isShutdown()) return;
        boltPending = true;
        Cluster c = clusters[0];
        float cx = c.x, cy = c.y + c.w * 0.08f, cz = c.z - 60;
        long seed = random.nextLong();
        worker.execute(() -> {
            Random r = new Random(seed);
            float[][] main = new float[6][2], branch = new float[3][2];
            main[0][0] = cx + (r.nextFloat() - 0.5f) * 240; main[0][1] = cy;
            for (int i = 1; i < 6; i++) { main[i][0] = main[i - 1][0] + (r.nextFloat() - 0.5f) * 140; main[i][1] = main[i - 1][1] + 60 + r.nextFloat() * 35; }
            branch[0] = main[2].clone();
            branch[1][0] = branch[0][0] + (50 + r.nextFloat() * 40) * (r.nextBoolean() ? -1 : 1); branch[1][1] = branch[0][1] + 50 + r.nextFloat() * 30;
            branch[2][0] = branch[1][0] + (r.nextFloat() - 0.5f) * 60; branch[2][1] = branch[1][1] + 40 + r.nextFloat() * 20;
            // To the screen (the camera's sway is a few pixels over the bolt's half second: drawn without it).
            float s = F / (F + cz);
            for (float[][] pts : new float[][][] {main, branch}) for (float[] q : pts) { q[0] = CX + q[0] * s; q[1] = CY + q[1] * s; }
            float left = Float.MAX_VALUE, top = Float.MAX_VALUE, right = -Float.MAX_VALUE, bottom = -Float.MAX_VALUE;
            for (float[][] pts : new float[][][] {main, branch}) for (float[] q : pts) {
                left = Math.min(left, q[0]); right = Math.max(right, q[0]); top = Math.min(top, q[1]); bottom = Math.max(bottom, q[1]);
            }
            left -= 24; top -= 24; right += 30; bottom += 30;
            Bitmap b = Bitmap.createBitmap((int) (right - left), (int) (bottom - top), Bitmap.Config.ARGB_8888);
            Canvas bc = new Canvas(b);
            bc.translate(-left, -top);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeJoin(Paint.Join.MITER);
            p.setStrokeCap(Paint.Cap.BUTT);
            Path path = new Path();
            for (int layer = 0; layer < 2; layer++) {   // a grey shadow offset down-right, then the white bolt
                for (int pass = 0; pass < 2; pass++) {
                    float[][] pts = pass == 0 ? main : branch;
                    float width = (pass == 0 ? 16 : 9) * (layer == 0 ? 1 : 0.8f);
                    path.rewind();
                    for (int i = 0; i < pts.length; i++) {
                        float x = pts[i][0] + (layer == 0 ? 5 : 0), y = pts[i][1] + (layer == 0 ? 4 : 0);
                        if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
                    }
                    p.setColor(layer == 0 ? 0xFF6A6C74 : 0xFFFFFFFF);
                    p.setStrokeWidth(width);
                    bc.drawPath(path, p);
                }
            }
            Bolt made = new Bolt(Ui.frozen(b), left, top);
            handler.post(() -> { nextBolt0 = made; boltPending = false; });
        });
    }

    private void proj(float x, float y, float z) {
        float x1 = x * yawCos + z * yawSin, z1 = -x * yawSin + z * yawCos;
        float y2 = y * pitchCos - z1 * pitchSin, z2 = y * pitchSin + z1 * pitchCos;
        float s = F / (F + z2);
        pX = CX + x1 * s; pY = CY + y2 * s; pS = s; pZ = z2;
    }

    // MARK: - Drawing

    @Override protected void onDraw(Canvas c) {
        Art a = art;
        if (!running || a == null) {   // the images are still being made (a fraction of a second)
            c.drawColor(BLACK);
            return;
        }
        long now = SystemClock.uptimeMillis();
        float dt = lastDraw == 0 ? 1 / 60f : Math.min(0.05f, (now - lastDraw) / 1000f);
        lastDraw = now;
        T += dt;
        update(dt);

        c.save();
        float k = getWidth() / W;
        c.scale(k, k);
        float n = night, dim = (1 - fog * 0.55f) * (1 - rain * 0.55f);
        flashLight = flash * 0.3f;
        drawBackground(c, a);
        drawSky(c, a, n, dim);
        drawWeather(c, a, n);
        drawRing(c, a.ringBack, 0.12f);
        if (!orbFront) drawBody(c, a, n, dim);
        drawNumber(c, n);
        drawRing(c, a.ringFront, 0.22f);
        Words w = words;
        blocks(c, w.above);
        drawPointers(c, a, w);
        if (orbFront) drawBody(c, a, n, dim);   // after the readings, so their pointer lines never cross it
        blocks(c, w.below);
        drawGrain(c, n);
        if (reveal < 1) {   // fades in from black when it opens
            reveal = Math.min(1, reveal + dt / 0.6f);
            fill.setColor(Ui.alpha(BLACK, 1 - reveal));
            c.drawRect(0, 0, W, H, fill);
        }
        c.restore();
    }

    /** Black, and the lattice with the band of light sweeping across it: one draw. */
    private void drawBackground(Canvas c, Art a) {
        c.drawColor(flashed(BLACK));
        float x = (T * 80) % (W + 1400) - 700;   // the band's centre at the top edge
        sweepMatrix.setTranslate(x - 250 - SWEEP_NX * SWEEP_W / 2, 540 - SWEEP_NY * SWEEP_W / 2);
        a.band.setLocalMatrix(sweepMatrix);
        c.drawBitmap(a.lattice, 0, 0, latticePaint);
    }

    private void drawSky(Canvas c, Art art, float n, float dim) {
        items = 0;
        for (Cluster cl : clusters) {
            if (cl.pres < 0.01f) continue;
            float pk = easeBack(cl.pres), dx = sin(T * 0.1f + cl.i * 1.7f) * 34, dy = sin(T * 0.45f + cl.i) * 10, a = clamp(cl.pres * 2);
            for (int s = 0; s < cl.sx.length; s++) {
                float jig = sin(T * 0.9f + cl.sph[s]) * 4;
                proj(cl.x + dx + cl.sx[s] * pk, cl.y + dy + cl.sy[s] * pk + jig, cl.z + cl.sz[s]);
                add(0, pX, pY, cl.sr[s] * pS * pk * (1 + 0.02f * sin(T * 0.7f + cl.sph[s])), a, 0);
            }
        }
        for (float[] st : STARS) {
            Cluster cl = clusters[(int) st[0]];
            if (cl.pres < 0.3f) continue;
            proj(cl.x + st[1] * cl.pres + sin(T * 0.3f + st[1]) * 12, cl.y + st[2] + sin(T * 0.8f + st[2]) * 8, cl.z + st[3]);
            add(1, pX, pY, 15 * pS, clamp(cl.pres * 1.5f - 0.4f), 0);
        }
        for (int i = 0; i < SPECKS; i++) {
            proj(spX[i] + sin(T * 0.2f + spPh[i]) * 20, spY[i] + sin(T * 0.5f + spPh[i]) * 14, spZ[i]);
            add(spStar[i] ? 3 : 2, pX, pY, spS[i] * pS, 0.45f, i);
        }
        // Back to front (insertion sort: ~90 items, nearly in order from one frame to the next).
        int[] order = sortOrder;
        for (int i = 0; i < items; i++) order[i] = i;
        for (int i = 1; i < items; i++) {
            int v = order[i];
            float z = itZ[v];
            int j = i - 1;
            while (j >= 0 && itZ[order[j]] < z) { order[j + 1] = order[j]; j--; }
            order[j + 1] = v;
        }
        bmp.setColorFilter(flashFilter());
        float rimAmt = (1 - rain * 0.6f) * dim;
        int q = Math.round(rimAmt * 32);   // the rim's strength in 32 steps; new clouds made off the UI thread when it moves
        if (q != litFor && !litPending) {
            litPending = true;
            litFor = q;
            worker.execute(() -> {
                Bitmap d = lit(art.cloudDay, art.rimSun, q / 32f), nn = lit(art.cloudNight, art.rimMoon, q / 32f);
                handler.post(() -> { litDay = d; litNight = nn; litPending = false; });
            });
        }
        Bitmap cloudDay = litDay, cloudNight = litNight;
        for (int o = 0; o < items; o++) {
            int i = order[o];
            float x = itX[i], y = itY[i], r = itR[i], a = itA[i];
            switch (itKind[i]) {
                case 0: {   // a cloud sphere: one draw (two only while day turns to night), the same image for all, so they batch
                    float s = r * 2.03f;
                    if (n < 0.999f) sprite(c, cloudDay, x, y, s, a * (n > 0.001f ? 1 - n : 1));
                    if (n > 0.001f) sprite(c, cloudNight, x, y, s, a * n);
                    break;
                }
                case 1:   // a floating star: red by day, silver at night
                    star(c, art.starFill, x, y, r, T * 0.3f + x * 0.001f, Ui.alpha(flashed(n > 0.5f ? 0xFFC9CAD3 : RED), a));
                    break;
                case 2:   // a speck
                    fill.setColor(Ui.alpha(flashed(INK), a));
                    c.drawRect(x - r / 2, y - r / 2, x + r / 2, y + r / 2, fill);
                    break;
                default:   // a speck that's a star outline
                    star(c, art.starLine, x, y, r * 1.5f, spRot[itRef[i]] + T * 0.3f, Ui.alpha(flashed(INK), a));
            }
        }
        bmp.setColorFilter(null);
    }

    private void add(int kind, float x, float y, float r, float a, int ref) {
        if (items >= ITEMS) return;
        itKind[items] = kind; itX[items] = x; itY[items] = y; itR[items] = r; itA[items] = a; itZ[items] = pZ; itRef[items] = ref;
        items++;
    }

    private void sprite(Canvas c, Bitmap b, float x, float y, float size, float a) {
        if (a <= 0.003f) return;
        bmp.setAlpha(Math.round(255 * Math.min(1, a)));
        rect.set(x - size / 2, y - size / 2, x + size / 2, y + size / 2);
        c.drawBitmap(b, null, rect, bmp);
        bmp.setAlpha(255);
    }

    /** A pre-drawn eight-point star (an alpha image), turned and tinted. */
    private void star(Canvas c, Bitmap b, float x, float y, float r, float rot, int color) {
        if ((color >>> 24) < 2) return;
        tint.setColor(color);
        c.save();
        c.rotate((float) Math.toDegrees(rot), x, y);
        rect.set(x - r * STAR_PAD, y - r * STAR_PAD, x + r * STAR_PAD, y + r * STAR_PAD);
        c.drawBitmap(b, null, rect, tint);
        c.restore();
    }

    /** Rain, snow, lightning, fog and the flash. */
    private void drawWeather(Canvas c, Art art, float n) {
        int rainColor = flashed(n > 0.5f ? NIGHT_BLUE : BLUE);
        bmp.setColorFilter(flashFilter());
        stroke.setStrokeCap(Paint.Cap.ROUND);
        java.util.Arrays.fill(groupLen, 0);
        boolean dots = false;
        for (int i = 0; i < drops; i++) {
            proj(dX[i], dY[i], dZ[i]);
            float fade = clamp(dAge[i] / 0.1f) * clamp((470 - dY[i]) / 120);
            if (dKind[i] == 3) { sprite(c, art.flake, pX, pY, dW[i] * 2 * pS, fade); continue; }
            if (fade < 0.02f) continue;
            // Perspective scale runs ~0.78 (far cloud) to ~1.1 (near): four thickness steps across it.
            int g = Math.min(FADES - 1, (int) (fade * FADES)) * WIDTHS + Math.max(0, Math.min(WIDTHS - 1, (int) ((pS - 0.76f) / 0.09f)));
            float[] pts = groupPts[g];
            int k = groupLen[g];
            if (dKind[i] == 2) {   // a drizzle drop: a dot (a zero-length line with round caps)
                pts[k] = pX; pts[k + 1] = pY; pts[k + 2] = pX; pts[k + 3] = pY;
                dots = true;
            } else {
                float len = dLen[i] * pS, l = (float) Math.hypot(dVx[i], dVy[i]);
                pts[k] = pX; pts[k + 1] = pY; pts[k + 2] = pX - dVx[i] / l * len; pts[k + 3] = pY - dVy[i] / l * len;
            }
            groupLen[g] = k + 4;
        }
        float width = dots ? 12 : lerp(2.5f, 4.5f, rain);   // drizzle dots are 12 across; streaks thicken with the rain
        for (int g = 0; g < GROUPS; g++) {
            if (groupLen[g] == 0) continue;
            float fade = (g / WIDTHS + 0.5f) / FADES, scale = 0.76f + (g % WIDTHS + 0.5f) * 0.09f;
            stroke.setColor(Ui.alpha(rainColor, fade * 0.9f));
            stroke.setStrokeWidth(width * scale);
            c.drawLines(groupPts[g], 0, groupLen[g], stroke);
        }
        if (boltOn) drawBolt(c);
        if (fog > 0.01f) {
            Bitmap band = n > 0.5f ? art.fogNight : art.fogDay;
            for (int i = 0; i < 6; i++) {
                proj(sin(T * (0.07f + i * 0.013f) + i * 1.9f) * 260, -170 + i * 75, 200 - i * 70);
                float w = 900 * pS, h = 70 * pS;
                bmp.setAlpha(Math.round(255 * fog * 0.85f));
                rect.set(pX - w, pY - h, pX + w, pY + h);
                c.drawBitmap(band, null, rect, bmp);
            }
            bmp.setAlpha(255);
        }
        bmp.setColorFilter(null);
    }

    /** The lightning's flash on a colour: what it looks like under a white veil of strength flashLight. */
    private int flashed(int color) {
        float f = flashLight;
        if (f <= 0) return color;
        int r = Math.round((color >> 16 & 255) * (1 - f) + 255 * f), g = Math.round((color >> 8 & 255) * (1 - f) + 255 * f), b = Math.round((color & 255) * (1 - f) + 255 * f);
        return (color & 0xFF000000) | r << 16 | g << 8 | b;
    }

    /** The same for images: a colour filter (64 steps, made once each). */
    private android.graphics.ColorFilter flashFilter() {
        int q = Math.round(flashLight * 64 / 0.3f);
        if (q <= 0) return null;
        if (flashFilters[q] == null) {
            float f = q * 0.3f / 64;
            int mul = Math.round(255 * (1 - f)), add = Math.round(255 * f);
            flashFilters[q] = new android.graphics.LightingColorFilter(mul << 16 | mul << 8 | mul, add << 16 | add << 8 | add);
        }
        return flashFilters[q];
    }

    private void drawBolt(Canvas c) {
        float b = boltAge, a = b < 0.07f ? 1 : b < 0.13f ? 0.2f : b < 0.24f ? 1 : 1 - (b - 0.24f) / 0.41f;
        Bolt bolt = boltNow;
        if (bolt == null) return;
        bmp.setAlpha(Math.round(255 * clamp(a)));
        c.drawBitmap(bolt.image, bolt.x, bolt.y, bmp);
        bmp.setAlpha(255);
    }

    /** One half of the dotted orbit (pre-drawn): the back half behind the number, the front half over it. */
    private void drawRing(Canvas c, Bitmap half, float alpha) {
        tint.setColor(Ui.alpha(INK, alpha));
        c.drawBitmap(half, CX - half.getWidth() / 2f, ORB_CY - half.getHeight() / 2f, tint);
    }

    /** The sun (matte, sandy, a soft shadow side and a faint red halo) or the moon in today's phase. */
    private void drawBody(Canvas c, Art art, float n, float dim) {
        float r = orbR * (1 + 0.015f * sin(T * 1.1f));
        if (n < 0.999f) {
            float a = (1 - n) * dim;
            sprite(c, art.halo, orbX, orbY, r * 3.6f, a);
            sprite(c, art.sun, orbX, orbY, r * 2.02f, a);
            sprite(c, art.shadow, orbX, orbY, r * 2.02f, a * 0.5f);
        }
        if (n > 0.001f) {
            float phase = moonPhase(System.currentTimeMillis());
            if (!moonPending && (moon == null || Math.abs(phase - moonFor) > 0.01f)) {   // a new one about every 7 hours, made off the UI thread
                moonPending = true;
                moonFor = phase;
                worker.execute(() -> {
                    Bitmap m = frozen(sphere(180, moonShade(phase), 8), true);
                    handler.post(() -> { moon = m; moonPending = false; });
                });
            }
            Bitmap m = moon;
            if (m == null) return;
            float a = n * dim;
            c.save();
            c.rotate((float) Math.toDegrees(-0.25), orbX, orbY);
            sprite(c, m, orbX, orbY, r * 1.9f, a);
            c.restore();
            sprite(c, art.shadow, orbX, orbY, r * 1.9f, a * 0.35f);
        }
    }

    private void drawNumber(Canvas c, float n) {
        float glow = Math.round((1 - cover * 0.6f) * (1 - fog) * 4) / 4f;
        // A slow float: two sines (~7.5 s and ~17 s) so it never looks like a metronome. Filtered draws only:
        // an unfiltered bitmap snaps to whole pixels and the float turns into steps.
        float bob = sin(T * 0.84f) * 7 + sin(T * 0.37f + 1.3f) * 3, oy = 820 - 560 + bob, ox = CX - 550;
        if (n < 0.999f) {
            Numeral d = numeral(words.number, glow);
            if (d != null) lastDay = d;
            if (lastDay != null) c.drawBitmap(lastDay.image, ox + lastDay.left, oy + lastDay.top, bmp);
        }
        if (n > 0.001f) {
            Numeral d = numeral(words.number, 0);
            if (d != null) lastNight = d;
            if (lastNight != null) {
                bmp.setAlpha(Math.round(255 * n));
                c.drawBitmap(lastNight.image, ox + lastNight.left, oy + lastNight.top, bmp);
                bmp.setAlpha(255);
            }
        }
        numRight = CX + words.numWidth / 2; numTop = oy + 190; numMid = oy + 420;
        Block u = words.unitImage;   // baked at bob 0, so it glides with the number instead of snapping like text
        if (u != null) c.drawBitmap(u.image, u.x, u.y + bob, bmp);
    }

    // MARK: - Text, made once a minute

    /** Text (and the forecast's icons) drawn once into a small image, and where it goes. */
    private static final class Block {
        final Bitmap image;
        final float x, y;
        Block(Bitmap image, float x, float y) { this.image = image; this.x = x; this.y = y; }
    }

    /** Everything written on the screen, for one minute: images above the sun's front pass, and below (the forecast). */
    private static final class Words {
        final java.util.ArrayList<Block> above = new java.util.ArrayList<>(), below = new java.util.ArrayList<>();
        String number = "—", unit = "°C";
        float numWidth;
        Block unitImage;
        int lit = -1;
    }

    private volatile Words words = new Words();

    private void blocks(Canvas c, java.util.ArrayList<Block> list) {
        for (int i = 0, n = list.size(); i < n; i++) {
            Block b = list.get(i);
            c.drawBitmap(b.image, b.x, b.y, null);
        }
    }

    private interface Drawer { void draw(Canvas c); }

    /** Draws into an image the size of the area (in design coordinates), then crops it to what was drawn. */
    private static void bake(java.util.ArrayList<Block> into, float x0, float y0, int w, int h, Drawer d) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.translate(-x0, -y0);
        d.draw(c);
        int[] row = new int[w];
        int left = w, right = -1, top = h, bottom = -1;
        for (int y = 0; y < h; y++) {
            b.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                if ((row[x] >>> 24) == 0) continue;
                if (x < left) left = x;
                if (x > right) right = x;
                if (y < top) top = y;
                bottom = y;
            }
        }
        if (right >= 0) into.add(new Block(Bitmap.createBitmap(b, left, top, right - left + 1, bottom - top + 1), x0 + left, y0 + top));
        b.recycle();
    }

    /** The readings' pointer lines (they follow the clouds, the sun and the number) and the current prayer's star. */
    private void drawPointers(Canvas c, Art art, Words w) {
        Cluster main = clusters[0];
        proj(main.x + main.w * 0.25f, main.y - main.w * 0.1f, main.z);
        pointer(c, art, 1540, 400, pX, pY, main.pres > 0.3f ? 1 : 0);
        pointer(c, art, 1540, 236, orbX + orbR * 0.6f, orbY - orbR * 0.6f, clamp((orbX - CX - 80) / 220) * (orbFront ? 1 : 0.5f));
        pointer(c, art, 1540, 540, numRight + 14, numMid + 10, 1);
        if (w.lit >= 0) star(c, art.starFill, 54, 470 + w.lit * 40 - 11, 6, T * 0.6f, INK);
    }

    private void pointer(Canvas c, Art art, float x, float y, float tx, float ty, float la) {
        if (la < 0.02f) return;
        stroke.setColor(Ui.alpha(INK, 0.6f * la));
        stroke.setStrokeWidth(1.6f);
        c.drawLine(x - 14, y - 8, tx, ty, stroke);
        star(c, art.starFill, tx, ty, 6, T * 0.4f, Ui.alpha(INK, la));
    }

    /** Film grain over everything, moving every frame. */
    private void drawGrain(Canvas c, float n) {
        grainMatrix.setTranslate(random.nextInt(256), random.nextInt(256));
        grainShader.setLocalMatrix(grainMatrix);
        grain.setAlpha(Math.round(255 * lerp(0.07f, 0.08f, n)));
        c.drawRect(0, 0, W, H, grain);
    }

    /** New words (the data or the minute changed): made on the worker, swapped in on the UI thread. */
    private void refreshText() {
        if (worker.isShutdown()) return;
        worker.execute(() -> {
            Words w = makeWords();
            handler.post(() -> words = w);
        });
    }

    // Used only on the worker thread (with the text paints, which the UI thread no longer draws with).
    private final Paint wFill = new Paint(Paint.ANTI_ALIAS_FLAG), wStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path wPath = new Path();

    private Words makeWords() {
        HomeData.Weather w = data.shown;
        Words out = new Words();
        Locale loc = Ui.locale();
        long now = System.currentTimeMillis();
        boolean f = HomeData.fahrenheit();
        String deg = f ? "°F" : "°C";
        out.unit = deg;
        out.number = Double.isNaN(w.temp) ? "—" : String.valueOf(Math.round(f ? w.temp * 9 / 5 + 32 : w.temp));
        out.numWidth = measure.measureText(out.number);
        // The unit beside the number (drawNumber puts its baseline at numRight + 10, oy + 260; oy = 260 at rest).
        java.util.ArrayList<Block> u = new java.util.ArrayList<>();
        float ux = CX + out.numWidth / 2 + 10, uy = 520;
        unit.setAlpha(217);
        bake(u, ux - 8, uy - 80, (int) unit.measureText(deg) + 16, 100, c -> c.drawText(deg, ux, uy, unit));
        out.unitImage = u.isEmpty() ? null : u.get(0);

        // The date line (only the city while the TV's clock isn't set: a wrong date and wrong prayers would mislead).
        boolean clock = clockSet(now);
        String line = Prefs.city().toUpperCase(loc) + (clock ? "  ·  " + new SimpleDateFormat("EEEE, MMM d", loc).format(new Date(now)).toUpperCase(loc)
                + "  ·  " + new SimpleDateFormat(Ui.en ? "h:mm a" : "HH:mm", loc).format(new Date(now)) : "");
        top.setAlpha(191);
        float tw = top.measureText(line);
        bake(out.above, CX - tw / 2 - 12, 62, (int) tw + 24, 40, c -> c.drawText(line, CX, 92, top));

        // The weather in words under the number, and the chance of rain, dimmer, after it.
        int kind = kind(w.code);
        double chance = w.hourChance.isEmpty() ? 0 : w.hourChance.get(0);
        boolean wet = kind == DRIZZLE || kind == RAIN || kind == HEAVY || kind == STORM || kind == SNOW || kind == HEAVY_SNOW;
        String condition = HomeData.condition(w.code, w.isDay).toUpperCase(loc), chanceText = wet && chance >= 30 ? "  " + Math.round(chance) + "%" : "";
        float cw = label.measureText(condition), pw = chanceText.isEmpty() ? 0 : label.measureText(chanceText), lx = CX - (cw + pw) / 2;
        bake(out.above, lx - 12, 840, (int) (cw + pw) + 24, 80, c -> {
            label.setAlpha(255);
            c.drawText(condition, lx, 900, label);
            if (pw > 0) {
                label.setAlpha(115);
                c.drawText(chanceText, lx + cw, 900, label);
            }
        });

        Prayers.Day sunDay = prayers.today;   // sunrise and sunset worked out for the exact location (see Prayers)
        // The readings on the right, in three groups (each image only as big as its lines).
        String none = Ui.tr("NENHUMA", "NONE", "NADA");
        String[] sunLines = {
                // all three come from the date: "—" while the clock isn't set
                Ui.tr("NASCER", "RISE", "SALIDA") + ":" + (!clock ? "—" : sunDay != null ? shortTime(sunDay.sunrise) : w.sunrise == 0 ? "—" : shortTime(w.sunrise)),
                Ui.tr("PÔR", "SET", "PUESTA") + ":" + (!clock ? "—" : sunDay != null ? shortTime(sunDay.sunset) : w.sunset == 0 ? "—" : shortTime(w.sunset)),
                Ui.tr("LUA", "MOON", "LUNA") + ":" + (clock ? phaseName(moonPhase(now)) : "—"),
        };
        String[] cloudLines = {
                Ui.tr("NUVENS", "CLOUDS", "NUBES") + ":" + num(w.cover, "%"),
                Ui.tr("CHUVA", "RAIN", "LLUVIA") + ":" + (Double.isNaN(w.rain) ? "—" : w.rain > 0 ? trim(w.rain) + "MM" : none),
        };
        String[] readLines = {
                Ui.tr("SENSAÇÃO", "FEELS", "SENSACIÓN") + ":" + temp(w.feels, f) + deg,
                Ui.tr("UMID", "HUM", "HUM") + ":" + num(w.humidity, "%"),
                Ui.tr("ORVALHO", "DEW", "ROCÍO") + ":" + temp(w.dew, f) + deg,
                Ui.tr("UV MÁX", "MAX UV", "UV MÁX") + ":" + num(w.uv, ""),
                Ui.tr("VIS", "VIS", "VIS") + ":" + (Double.isNaN(w.visibility) ? "—" : trim(w.visibility) + "KM"),
                Ui.tr("VENTO", "WIND", "VIENTO") + ":" + (Double.isNaN(w.wind) ? "—" : Math.round(w.wind) + "KMH " + compass(w.windDir)),
        };
        String[][] groups = {sunLines, cloudLines, readLines};
        float[] groupY = {236, 400, 540};
        for (int g = 0; g < 3; g++) {
            String[] lines = groups[g];
            float gy = groupY[g];
            bake(out.above, 1520, gy - 36, 380, lines.length * 32 + 20, c -> { for (int i = 0; i < lines.length; i++) c.drawText(lines[i], 1540, gy + i * 32, mono); });
        }

        // Today's quote (a short verse or hadith), top left: a new one each day, through all of them in a shuffled order.
        String[] q = Config.DAILY_QUOTE ? quoteOfDay(now) : null;
        if (q != null) {
            android.text.StaticLayout lines = android.text.StaticLayout.Builder.obtain(q[0], 0, q[0].length(), quote, 290)
                    .setLineSpacing(0, 1.12f).build();
            bake(out.above, 60, 112, 330, 300, c -> {
                c.save();
                c.translate(74, 128);
                lines.draw(c);
                c.restore();
                c.drawText(q[1].toUpperCase(Locale.ROOT), 74, 128 + lines.getHeight() + 30, quoteRef);
            });
        }

        // Today's prayers, small at the left edge: the current (or next) one bright, past ones faint.
        Prayers.Day d = prayers.today;
        if (Config.PRAYER_TIMES && d != null && clock) {
            int lit = -1;
            for (int i = 0; i < 5; i++) if (lit < 0 && d.start[i] <= now && now < d.end[i]) lit = i;
            if (lit < 0) for (int i = 0; i < 5; i++) if (d.start[i] > now) { lit = i; break; }
            if (lit < 0) lit = 0;   // after Isha's end: tomorrow's Fajr is next
            out.lit = lit;
            int litRow = lit;
            bake(out.above, 40, 440, 260, 200, c -> {
                for (int i = 0; i < 5; i++) {
                    float x = 74, y = 470 + i * 40;
                    prayer.setAlpha(Math.round(255 * (i == litRow ? 1 : d.end[i] <= now ? 0.3f : 0.6f)));
                    prayer.setTextAlign(Paint.Align.LEFT);
                    c.drawText(d.name(i), x, y, prayer);
                    prayer.setTextAlign(Paint.Align.RIGHT);
                    c.drawText(clock12(d.start[i]), x + 128, y, prayer);
                    prayer.setTextAlign(Paint.Align.CENTER);
                    c.drawText("–", x + 144, y, prayer);
                    prayer.setTextAlign(Paint.Align.RIGHT);
                    c.drawText(clock12(d.end[i]), x + 214, y, prayer);
                }
            });
        }

        // NOW, then every 3 hours (one image per column): time, a small picture in the scene's colours, temperature,
        // and the chance of rain.
        if (!Double.isNaN(w.temp)) {
            int n = 1;
            String[] hourText = new String[8], degText = new String[8], chanceTexts = new String[8];
            int[] kinds = new int[8];
            boolean[] days = new boolean[8];
            hourText[0] = Ui.tr("AGORA", "NOW", "AHORA"); degText[0] = temp(w.temp, f) + deg; kinds[0] = kind; days[0] = w.isDay;
            chanceTexts[0] = chance >= 30 ? Math.round(chance) + "% " + Ui.tr("CHUVA", "RAIN", "LLUVIA") : null;
            SimpleDateFormat hour = new SimpleDateFormat(Ui.en ? "h a" : "HH'h'", loc);
            for (int i = 2; i < w.hours.size() && n < 8; i += 3) {
                hourText[n] = hour.format(new Date(w.hours.get(i))).toUpperCase(loc);
                degText[n] = temp(w.hourTemp.get(i), f) + deg;
                kinds[n] = kind(w.hourCode.get(i));
                days[n] = w.hourDay.get(i);
                double ch = w.hourChance.get(i);
                chanceTexts[n] = ch >= 30 ? Math.round(ch) + "% " + Ui.tr("CHUVA", "RAIN", "LLUVIA") : null;
                n++;
            }
            float x0 = 380, step = (1540 - x0) / Math.max(1, n - 1);
            for (int i = 0; i < n; i++) {
                float x = x0 + i * step;
                int col = i;
                bake(out.below, x - 75, 905, 150, 165, c -> {
                    slotTime.setAlpha(col == 0 ? 255 : 153);
                    c.drawText(hourText[col], x, 936, slotTime);
                    miniIcon(c, kinds[col], days[col], x, 964);
                    c.drawText(degText[col], x, 1030, slotTemp);
                    if (chanceTexts[col] != null) c.drawText(chanceTexts[col], x, 1058, slotRain);
                });
            }
            float lineX = x0 + step / 2;
            bake(out.below, lineX - 2, 918, 4, 144, c -> {
                wStroke.setStyle(Paint.Style.STROKE);
                wStroke.setColor(Ui.alpha(INK, 0.2f));
                wStroke.setStrokeWidth(1);
                c.drawLine(lineX, 920, lineX, 1060, wStroke);
            });
        }
        return out;
    }

    /** False while the TV's clock isn't set (after a power cut with no internet it restarts at the build date). */
    private static boolean clockSet(long now) {
        return Prefs.clockTrusted(now);
    }

    private static java.util.List<String[]> quotes;   // {text, reference}, from assets/quotes.json (see quotes-build.js)

    /** Today's quote: by the day's number, stepping 37 at a time (prime to the count), so Quran and hadith mix. */
    private String[] quoteOfDay(long now) {
        if (quotes == null) {
            java.util.List<String[]> list = new java.util.ArrayList<>();
            try (java.io.InputStream in = getContext().getAssets().open("quotes.json")) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                org.json.JSONArray a = new org.json.JSONArray(out.toString("UTF-8"));
                for (int i = 0; i < a.length(); i++) list.add(new String[] {a.getJSONObject(i).getString("t"), a.getJSONObject(i).getString("r")});
            } catch (Exception e) {
                AppLog.w("quotes: " + e.getMessage());
            }
            quotes = list;
        }
        if (quotes.isEmpty()) return null;
        long day = (now + java.util.TimeZone.getDefault().getOffset(now)) / 86_400_000L;
        return quotes.get((int) ((day * 37) % quotes.size()));
    }

    private void miniIcon(Canvas c, int kind, boolean day, float x, float y) {
        int cloud = day ? 0xFFF2F2F4 : 0xFF7A7C85;
        if (kind == CLEAR) { body(c, day, x, y + 4, 13); return; }
        if (kind == MOSTLY || kind == PARTLY) {
            body(c, day, x - 7, y - 3, 10);
            puff(c, cloud, x + 4, y + 6, kind == MOSTLY ? 0.7f : 0.9f);
            return;
        }
        puff(c, cloud, x, y, 1);
        wStroke.setStyle(Paint.Style.STROKE);
        wStroke.setStrokeCap(Paint.Cap.ROUND);
        if (kind == FOG) {
            wStroke.setColor(cloud);
            wStroke.setStrokeWidth(2.5f);
            c.drawLine(x - 14, y + 17, x + 14, y + 17, wStroke);
            c.drawLine(x - 10, y + 23, x + 10, y + 23, wStroke);
        } else if (kind == DRIZZLE || kind == RAIN || kind == HEAVY) {
            float len = kind == DRIZZLE ? 4 : kind == HEAVY ? 11 : 7;
            wStroke.setColor(BLUE);
            wStroke.setStrokeWidth(3);
            for (int i = 0; i < 3; i++) { float dx = x - 9 + i * 9; c.drawLine(dx, y + 15, dx - 2, y + 15 + len, wStroke); }
        } else if (kind == SNOW || kind == HEAVY_SNOW) {
            wFill.setColor(0xFFFFFFFF);
            for (int i = 0; i < 3; i++) c.drawCircle(x - 9 + i * 9, y + 19, 2.5f, wFill);
        } else if (kind == STORM) {
            wFill.setColor(0xFFFFFFFF);
            wPath.rewind();
            wPath.moveTo(x + 2, y + 10); wPath.lineTo(x - 6, y + 22); wPath.lineTo(x, y + 22); wPath.lineTo(x - 4, y + 32);
            wPath.lineTo(x + 8, y + 18); wPath.lineTo(x + 2, y + 18); wPath.close();
            c.drawPath(wPath, wFill);
        }
    }

    private void body(Canvas c, boolean day, float x, float y, float r) {
        if (day) { wFill.setColor(RED); c.drawCircle(x, y, r, wFill); return; }
        wFill.setColor(0xFFF2F2F4); c.drawCircle(x, y, r, wFill);
        wFill.setColor(BLACK); c.drawCircle(x + r * 0.45f, y - r * 0.3f, r * 0.85f, wFill);
    }

    private void puff(Canvas c, int color, float x, float y, float k) {
        wFill.setColor(color);
        c.drawCircle(x - 9 * k, y + 3 * k, 8 * k, wFill);
        c.drawCircle(x + 8 * k, y + 3 * k, 9 * k, wFill);
        c.drawCircle(x, y - 4 * k, 11 * k, wFill);
    }

    private static String temp(double v, boolean f) {
        return Double.isNaN(v) ? "—" : String.valueOf(Math.round(f ? v * 9 / 5 + 32 : v));
    }

    private static String num(double v, String suffix) {
        return Double.isNaN(v) ? "—" : Math.round(v) + suffix;
    }

    private static String trim(double v) {
        return v >= 10 ? String.valueOf(Math.round(v)) : String.format(Locale.ROOT, "%.1f", v);
    }

    private static String shortTime(long at) {
        String a = new SimpleDateFormat("a", Locale.US).format(new Date(at));
        return new SimpleDateFormat("h:mm", Locale.US).format(new Date(at)) + a.charAt(0);
    }

    private static String clock12(long at) {
        return new SimpleDateFormat("h:mm", Locale.US).format(new Date(at));
    }

    private static String compass(double deg) {
        if (Double.isNaN(deg)) return "";
        String[] c = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return c[(int) Math.round(deg / 45) % 8];
    }

    /** 0 new moon, 0.5 full. */
    private static float moonPhase(long at) {
        double p = ((at / 864e5 - 10962.77) / 29.530588853) % 1;
        return (float) (p < 0 ? p + 1 : p);
    }

    private static String phaseName(float p) {
        return new String[] {"NEW", "WXC", "FQ", "WXG", "FULL", "WNG", "LQ", "WNC"}[Math.round(p * 8) % 8];
    }

    // MARK: - Making the images (worker thread)

    private static final float STAR_PAD = 1.08f;   // the star image is a little bigger than the star (antialiased edges)

    private Art makeArt() {
        Art a = new Art();
        a.cloudDay = Ui.frozen(sphere(128, matte(DAY_CLOUD, 0.6f, 0.5f, 0, 18), 1));
        a.cloudNight = Ui.frozen(sphere(128, matte(NIGHT_CLOUD, 0.42f, 0.75f, 0, 22), 2));
        a.rimSun = Ui.frozen(sphere(128, rimOnly(RED, 0.45f), 3));
        a.rimMoon = Ui.frozen(sphere(128, rimOnly(0xFFC9CAD3, 0.85f), 4));
        a.sun = frozen(sphere(180, matte(RED, 0.85f, 0.3f, 0.45f, 45), 5), true);
        a.shadow = frozen(sphere(180, (px, py, nz, o) -> {
            float lam = px * LX + py * LY + nz * LZ;
            o[0] = o[1] = o[2] = 0;
            o[3] = clamp(0.62f * (1 - clamp(lam * 1.3f + 0.2f)) + (float) Math.pow(1 - nz, 2.5) * 0.3f);
            o[4] = 0;
        }, 6), true);
        a.flake = frozen(sphere(24, matte(0xFFFFFFFF, 0.7f, 0.35f, 0, 10), 7), true);
        a.halo = frozen(haloSprite(), true);
        a.fogDay = frozen(fogSprite(0xFF9A9CA4), true);
        a.fogNight = frozen(fogSprite(0xFF5A5C64), true);
        a.grainTile = frozen(grainTile(), false);
        a.starFill = frozen(alphaOnly(starSprite(false)), true);
        a.starLine = frozen(alphaOnly(starSprite(true)), true);
        a.ringBack = frozen(alphaOnly(ringSprite(false)), false);
        a.ringFront = frozen(alphaOnly(ringSprite(true)), false);

        a.lattice = frozen(alphaOnly(lattice()), false);
        a.band = new LinearGradient(0, 0, SWEEP_NX * SWEEP_W, SWEEP_NY * SWEEP_W,
                new int[] {0x12FFFFFF, 0x21FFFFFF, 0x21FFFFFF, 0x36FFFFFF, 0x36FFFFFF, 0x21FFFFFF, 0x21FFFFFF, 0x12FFFFFF},
                new float[] {0, 0.12f, 0.24f, 0.34f, 0.66f, 0.76f, 0.88f, 1}, Shader.TileMode.CLAMP);
        return a;
    }

    /** A cloud with its rim at a strength (0..1) baked in, mipmapped. */
    private static Bitmap lit(Bitmap cloud, Bitmap rim, float strength) {
        Bitmap b = cloud.copy(Bitmap.Config.ARGB_8888, true);
        Paint p = new Paint();
        p.setAlpha(Math.round(255 * strength));
        new Canvas(b).drawBitmap(rim, 0, 0, p);
        return frozen(b, true);
    }

    /** Just the alpha (a quarter of the memory), drawn in the paint's colour: one image for every tint. */
    private static Bitmap alphaOnly(Bitmap b) {
        Bitmap a = b.extractAlpha();
        b.recycle();
        Bitmap m = a.copy(Bitmap.Config.ALPHA_8, true);   // mutable, so frozen() can set mipmaps
        a.recycle();
        return m;
    }

    /** A read-only copy (the GPU keeps its texture), with mipmaps if it's drawn smaller than made. */
    private static Bitmap frozen(Bitmap b, boolean mipmaps) {
        b.setHasMipMap(mipmaps);
        Bitmap f = Ui.frozen(b);
        if (f != b) b.recycle();
        return f;
    }

    private static Bitmap sphere(int radius, Shade shade, long seed) {
        int size = radius * 2 + 4;
        int[] px = new int[size * size];
        float[] o = new float[5];
        Random r = new Random(seed);
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            float fx = (x + 0.5f - size / 2f) / radius, fy = (y + 0.5f - size / 2f) / radius, rr = fx * fx + fy * fy;
            float edge = (1 - (float) Math.sqrt(rr)) * radius;
            if (edge < -1) continue;
            float nz = (float) Math.sqrt(Math.max(0, 1 - rr));
            o[3] = 1; o[4] = 18;
            shade.at(fx, fy, nz, o);
            float g = (r.nextFloat() - 0.5f) * o[4];
            int a = Math.round(255 * clamp(edge + 0.5f) * clamp(o[3]));
            px[y * size + x] = (a << 24) | (c255(o[0] + g) << 16) | (c255(o[1] + g) << 8) | c255(o[2] + g);
        }
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);   // mutable, so frozen() can set mipmaps
        b.setPixels(px, 0, size, 0, 0, size, size);
        return b;
    }

    /** Clay or felt: a soft key light, a darker rim, a little bounce from below; `emit` makes it glow from within. */
    private static Shade matte(int base, float amb, float key, float emit, float grainAmount) {
        float br = base >> 16 & 255, bg = base >> 8 & 255, bb = base & 255;
        return (px, py, nz, o) -> {
            float lam = clamp((px * LX + py * LY + nz * LZ + 0.2f) / 1.2f);
            float rim = (float) Math.pow(1 - nz, 2.2);
            float k = (amb + key * lam) * (1 - 0.35f * rim) + clamp(py) * 0.12f;
            float m = k + (1 - k) * emit;
            o[0] = br * m; o[1] = bg * m; o[2] = bb * m; o[3] = 1; o[4] = grainAmount;
        };
    }

    /** Only the rim, in a colour: the sun (or the moon) catching the clouds' edges. */
    private static Shade rimOnly(int color, float amount) {
        float cr = color >> 16 & 255, cg = color >> 8 & 255, cb = color & 255;
        return (px, py, nz, o) -> {
            float facing = clamp(-(px * 0.45f + py * 0.9f) * 0.6f + 0.55f);
            o[0] = cr; o[1] = cg; o[2] = cb; o[3] = (float) Math.pow(1 - nz, 3) * facing * amount; o[4] = 0;
        };
    }

    /** The moon in its phase: the lit side bright, the dark side nearly black, coarse stipple grain. */
    private static Shade moonShade(float phase) {
        float a = phase * TAU, lx = sin(a), ly = -0.1f, lz = -cos(a);
        return (px, py, nz, o) -> {
            float lit = clamp((px * lx + py * ly + nz * lz) * 6 + 0.3f);
            float v = lerp(16, 242, lit) * (0.88f + 0.12f * nz);
            o[0] = v; o[1] = v; o[2] = lerp(v, v + 6, lit); o[3] = 1; o[4] = 70;
        };
    }

    /** The sun's faint halo: red from just inside its edge, fading out. */
    private static Bitmap haloSprite() {
        Bitmap b = Bitmap.createBitmap(360, 360, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(180, 180, 170, new int[] {Ui.alpha(RED, 0.2f), Ui.alpha(RED, 0.2f), Ui.alpha(RED, 0)},
                new float[] {0, 95 / 170f, 1}, Shader.TileMode.CLAMP));
        new Canvas(b).drawRect(0, 0, 360, 360, p);
        return b;
    }

    /** One soft band of fog: an ellipse fading out from its middle. */
    private static Bitmap fogSprite(int color) {
        Bitmap b = Bitmap.createBitmap(256, 64, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        RadialGradient g = new RadialGradient(128, 32, 128, Ui.alpha(color, 0.6f), Ui.alpha(color, 0), Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setScale(1, 0.25f, 128, 32);
        g.setLocalMatrix(m);
        p.setShader(g);
        new Canvas(b).drawRect(0, 0, 256, 64, p);
        return b;
    }

    private static Bitmap grainTile() {
        int[] px = new int[256 * 256];
        Random r = new Random(9);
        for (int i = 0; i < px.length; i++) {
            int v = r.nextBoolean() ? 0 : 255, a = (int) (r.nextFloat() * 90);
            px[i] = (a << 24) | (v << 16) | (v << 8) | v;
        }
        Bitmap b = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
        b.setPixels(px, 0, 256, 0, 0, 256, 256);
        return b;
    }

    /** An eight-point star in white (tinted when drawn), filled or as an outline 1.4 px thick at a 7.5 px radius. */
    private static Bitmap starSprite(boolean outline) {
        int s = 128;
        float r = s / 2f / STAR_PAD;
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        if (outline) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.4f * r / 7.5f);
            r -= p.getStrokeWidth() / 2;
        }
        Path path = new Path();
        starPath(path, s / 2f, s / 2f, r, 0);
        new Canvas(b).drawPath(path, p);
        return b;
    }

    /** One half of the dotted orbit, tilted, in white. */
    private static Bitmap ringSprite(boolean front) {
        float ct = Math.abs(cos(ORB_TILT)), st = Math.abs(sin(ORB_TILT));
        int w = (int) Math.ceil(2 * Math.hypot(ORB_RX * ct, ORB_RY * st)) + 12, h = (int) Math.ceil(2 * Math.hypot(ORB_RX * st, ORB_RY * ct)) + 12;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.5f);
        p.setColor(0xFFFFFFFF);
        p.setPathEffect(new DashPathEffect(new float[] {2, 10}, 0));
        c.translate(w / 2f, h / 2f);
        c.rotate((float) Math.toDegrees(ORB_TILT));
        c.drawArc(new RectF(-ORB_RX, -ORB_RY, ORB_RX, ORB_RY), front ? 0 : 180, 180, false, p);
        return b;
    }

    /** Star-and-cross: eight-point stars on a grid, their tips touching; fainter toward the middle (the number). */
    private static Bitmap lattice() {
        Bitmap b = Bitmap.createBitmap((int) W, (int) H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.4f);
        p.setColor(0xFFFFFFFF);
        Path path = new Path();
        float a = 120;
        for (float y = 0; y <= H + a; y += a) for (float x = 0; x <= W + a; x += a) {
            starPath(path, x, y, a / 2, 0);
            c.drawPath(path, p);
            starPath(path, x, y, a * 0.2f, (float) Math.PI / 8);
            c.drawPath(path, p);
        }
        Paint mask = new Paint();
        RadialGradient g = new RadialGradient(CX, 600, 900, new int[] {0x4D000000, 0x4D000000, 0xFF000000}, new float[] {0, 0.4f, 1}, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setScale(1.5f, 1, CX, 600);
        g.setLocalMatrix(m);
        mask.setShader(g);
        mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        c.drawRect(0, 0, W, H, mask);
        return b;
    }

    /** An eight-point star (two squares turned 45° to each other). */
    private static void starPath(Path path, float x, float y, float r, float rot) {
        path.rewind();
        for (int i = 0; i < 16; i++) {
            float a = rot + i * (float) Math.PI / 8, rr = i % 2 == 1 ? r * 0.7654f : r;
            if (i == 0) path.moveTo(x + cos(a) * rr, y + sin(a) * rr); else path.lineTo(x + cos(a) * rr, y + sin(a) * rr);
        }
        path.close();
    }

    /** The number for a value and glow, or null while it's being made (off the UI thread). */
    private Numeral numeral(String s, float glow) {
        String key = s + '|' + glow;
        Numeral n = numerals.get(key);
        if (n != null || numeralsPending.contains(key)) return n;
        numeralsPending.add(key);
        Bitmap grainTile = art.grainTile;
        worker.execute(() -> {
            Numeral made = makeNumeral(s, glow, grainTile);
            handler.post(() -> {
                numeralsPending.remove(key);
                if (numerals.size() > 8) numerals.clear();
                numerals.put(key, made);
            });
        });
        return null;
    }

    /** Condensed digits extruded and bevelled, the sun's red glowing on top by day; cropped to what's drawn. */
    private Numeral makeNumeral(String s, float glow, Bitmap grainTile) {
        float x = 550, y = 560;
        Bitmap b = Bitmap.createBitmap(1100, 660, Bitmap.Config.ARGB_8888);
        Canvas g = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(numeralFace);
        p.setTextSize(520);
        p.setTextAlign(Paint.Align.CENTER);
        for (int i = 24; i >= 1; i--) {
            p.setColor(mix(0xFF5D5F68, 0xFF24252A, i / 24f));
            g.drawText(s, x + i * 0.45f, y + i * 0.95f, p);
        }
        Bitmap face = Bitmap.createBitmap(1100, 660, Bitmap.Config.ARGB_8888);
        Canvas fc = new Canvas(face);
        Paint fp = new Paint(p);
        fp.setShader(new LinearGradient(0, y - 380, 0, y, 0xFFF6F6F8, 0xFFB4B5BC, Shader.TileMode.CLAMP));
        fc.drawText(s, x, y, fp);
        Paint sp = new Paint(p);
        sp.setStyle(Paint.Style.STROKE);
        sp.setStrokeJoin(Paint.Join.MITER);
        sp.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
        sp.setStrokeWidth(18);
        sp.setColor(0x8C282A30);
        fc.drawText(s, x + 5, y + 6, sp);
        sp.setStrokeWidth(10);
        sp.setColor(0xE6FFFFFF);
        fc.drawText(s, x - 4, y - 5, sp);
        Paint over = new Paint();
        over.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
        if (glow > 0) {
            over.setShader(new RadialGradient(x - 40, y - 470, 300, Ui.alpha(RED, 0.42f * glow), Ui.alpha(RED, 0), Shader.TileMode.CLAMP));
            fc.drawRect(0, 0, 1100, 660, over);
        }
        over.setShader(new BitmapShader(grainTile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        over.setAlpha(36);
        fc.drawRect(0, 0, 1100, 660, over);
        g.drawBitmap(face, 0, 0, null);
        face.recycle();
        // Crop to the drawn pixels: the GPU then blends only the glyphs' box, not the whole 1100×660.
        int[] row = new int[1100];
        int left = 1100, right = -1, topRow = 660, bottom = -1;
        for (int yy = 0; yy < 660; yy++) {
            b.getPixels(row, 0, 1100, 0, yy, 1100, 1);
            for (int xx = 0; xx < 1100; xx++) {
                if ((row[xx] >>> 24) == 0) continue;
                if (xx < left) left = xx;
                if (xx > right) right = xx;
                if (yy < topRow) topRow = yy;
                bottom = yy;
            }
        }
        if (right < 0) return new Numeral(frozen(b, false), 0, 0);
        Bitmap crop = Bitmap.createBitmap(b, left, topRow, right - left + 1, bottom - topRow + 1);
        if (crop != b) b.recycle();
        return new Numeral(Ui.frozen(crop), left, topRow);
    }

    // MARK: - Small helpers

    private float rnd(float a, float b) { return a + random.nextFloat() * (b - a); }
    private static float sin(float a) { return (float) Math.sin(a); }
    private static float cos(float a) { return (float) Math.cos(a); }
    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }
    private static float clamp(float v) { return v < 0 ? 0 : v > 1 ? 1 : v; }
    private static int c255(float v) { return v < 0 ? 0 : v > 255 ? 255 : (int) v; }

    private static float easeBack(float t) {
        float k = 1.6f;
        t = clamp(t) - 1;
        return 1 + (k + 1) * t * t * t + k * t * t;
    }

    private static int mix(int a, int b, float t) {
        int r = Math.round(lerp(a >> 16 & 255, b >> 16 & 255, t)), g = Math.round(lerp(a >> 8 & 255, b >> 8 & 255, t)), bl = Math.round(lerp(a & 255, b & 255, t));
        return 0xFF000000 | r << 16 | g << 8 | bl;
    }
}
