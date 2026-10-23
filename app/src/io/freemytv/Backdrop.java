package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * The home screen wallpaper made from the featured title's image, built once in the background:
 *  - the photo cropped to 16:9 at 1920×1080 (the TV's UI size), with soft scrims already applied (tvOS style: a 20%
 *    veil, plus a little more behind the title at the top left, the menu bar and the Dock);
 *  - the frosted version (192×108, heavily blurred): the wallpaper behind the apps grid, the drawer's frosted glass;
 *  - the Liquid Glass version (480×270, lightly blurred): the photo shows through the Dock and the menu bar.
 * The final image lives only in GPU memory (HARDWARE): drawing it costs one texture, and the process doesn't hold 8 MB.
 */
final class Backdrop {
    static final int W = 1920, H = 1080, FW = 192, FH = 108, GW = 480, GH = 270;
    final Bitmap image;
    final int[] frost;      // heavily blurred (frosted glass, bottom drawer)
    final int[] glass;      // lightly blurred (Liquid Glass: the photo shows through the glass)
    final Bitmap frostImage;            // the frosted version, dimmed: the wallpaper while the apps grid is up

    private Backdrop(Bitmap image, int[] frost, int[] glass) {
        this.image = image;
        this.frost = frost;
        this.glass = glass;
        // Already dimmed like tvOS's app grid (rgb 29, 29, 29 at 40%), so it's drawn as one opaque image.
        int[] dim = new int[frost.length];
        for (int i = 0; i < dim.length; i++) {
            int p = frost[i];
            int r = Math.round(((p >> 16) & 255) * 0.6f + 29 * 0.4f), g = Math.round(((p >> 8) & 255) * 0.6f + 29 * 0.4f);
            int b = Math.round((p & 255) * 0.6f + 29 * 0.4f);
            dim[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        frostImage = Bitmap.createBitmap(dim, FW, FH, Bitmap.Config.ARGB_8888);
    }

    static Backdrop make(Bitmap src) {
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint photo = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        float scale = Math.max((float) W / src.getWidth(), (float) H / src.getHeight());
        float sw = src.getWidth() * scale, sh = src.getHeight() * scale;
        c.drawBitmap(src, null, new RectF((W - sw) / 2, (H - sh) / 2, (W + sw) / 2, (H + sh) / 2), photo);
        src.recycle();
        // Like tvOS: the photo stays bright (a 20% veil), with soft scrims only where text sits: the title at the top
        // left (stronger when the photo is bright there), the corner buttons, and under the Dock.
        float boost = Math.max(0, Math.min(0.25f, (luminance(out, 0, 0.05f, 0.5f, 0.4f) - 0.3f) * 1.0f));
        Paint scrim = new Paint(Paint.DITHER_FLAG);
        c.drawColor(black(0.2f));
        scrim.setShader(new android.graphics.RadialGradient(0, H * 0.16f, W * 0.62f,
                new int[] {black(0.42f + boost), black(0.22f + boost * 0.5f), 0}, new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP));
        c.save();
        c.scale(1, 0.62f, 0, H * 0.16f);
        c.drawCircle(0, H * 0.16f, W * 0.62f, scrim);
        c.restore();
        scrim.setShader(new LinearGradient(0, 0, 0, H * 0.18f, new int[] {black(0.25f), 0}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H * 0.18f, scrim);
        scrim.setShader(new LinearGradient(0, H, 0, H * 0.6f, new int[] {black(0.3f), black(0.12f), 0},
                new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, H * 0.6f, W, H, scrim);

        int[] frost = reduce(out, FW, FH, 3, 3);
        int[] glass = reduce(out, GW, GH, 2, 2);
        return new Backdrop(gpu(out), frost, glass);
    }

    /** Copies it to GPU memory (cheap to draw; the process doesn't keep the pixels). */
    static Bitmap gpu(Bitmap b) {
        Bitmap g = b.copy(Bitmap.Config.HARDWARE, false);
        if (g == null) return b;
        b.recycle();
        return g;
    }

    /** Halved at each step (a true average, no aliasing), then blurred (box passes of radius r). */
    private static int[] reduce(Bitmap full, int w, int h, int radius, int passes) {
        Bitmap b = full;
        while (b.getWidth() / 2 >= w * 1.5f) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            if (b != full) b.recycle();
            b = half;
        }
        Bitmap small = Bitmap.createScaledBitmap(b, w, h, true);
        if (b != full) b.recycle();
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        small.recycle();
        int[] tmp = new int[px.length];
        for (int pass = 0; pass < passes; pass++) {
            box(px, tmp, w, h, radius, true);
            box(tmp, px, w, h, radius, false);
        }
        return px;
    }

    /** Moving average of radius r in one direction, repeating the edge pixels. */
    static void box(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int n = horizontal ? w : h, lines = horizontal ? h : w, step = horizontal ? 1 : w, lineStep = horizontal ? w : 1;
        float k = 1f / (2 * r + 1);
        for (int line = 0; line < lines; line++) {
            int base = line * lineStep;
            float sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[base + Math.max(0, Math.min(n - 1, i)) * step];
                sr += (p >> 16) & 255;
                sg += (p >> 8) & 255;
                sb += p & 255;
            }
            for (int i = 0; i < n; i++) {
                out[base + i * step] = 0xFF000000 | (Math.round(sr * k) << 16) | (Math.round(sg * k) << 8) | Math.round(sb * k);
                int add = in[base + Math.min(n - 1, i + r + 1) * step], sub = in[base + Math.max(0, i - r) * step];
                sr += ((add >> 16) & 255) - ((sub >> 16) & 255);
                sg += ((add >> 8) & 255) - ((sub >> 8) & 255);
                sb += (add & 255) - (sub & 255);
            }
        }
    }

    /** Average luminance (0 to 1) of a rectangle given as fractions of the image, by sampling. */
    private static float luminance(Bitmap b, float x0, float y0, float x1, float y1) {
        float sum = 0;
        int count = 0;
        for (int j = 0; j < 24; j++) {
            for (int i = 0; i < 32; i++) {
                int x = Math.round((x0 + (x1 - x0) * (i + 0.5f) / 32) * (b.getWidth() - 1));
                int y = Math.round((y0 + (y1 - y0) * (j + 0.5f) / 24) * (b.getHeight() - 1));
                int p = b.getPixel(x, y);
                sum += (0.2126f * ((p >> 16) & 255) + 0.7152f * ((p >> 8) & 255) + 0.0722f * (p & 255)) / 255f;
                count++;
            }
        }
        return sum / count;
    }

    private static int black(float a) {
        return Math.round(Math.max(0, Math.min(1, a)) * 255) << 24;
    }
}
