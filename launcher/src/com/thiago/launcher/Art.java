package com.thiago.launcher;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

/** Cores e icones desenhados a mao (sem imagens/logos de terceiros). */
final class Art {
    static final int ICON_WIDE = 0;   // portatil deitado (GBA)
    static final int ICON_TALL = 1;   // portatil em pe (GB/GBC)
    static final int ICON_PAD = 2;    // controle de console
    static final int ICON_APPS = 3;   // grade de apps
    static final int ICON_GEAR = 4;   // engrenagem
    static final int ICON_PC = 5;     // computador

    private Art() { }

    // ------------------------------------------------------------ icones baixados
    // assets/icons/<id>.png = libretro/retroarch-assets (XMB "systematic"/"monochrome"), CC-BY 4.0

    private static final java.util.HashMap<String, android.graphics.Bitmap> ICONS =
            new java.util.HashMap<String, android.graphics.Bitmap>();

    /** Icone baixado do sistema (ou null se nao houver: ai usa o desenho a mao). */
    static synchronized android.graphics.Bitmap iconBitmap(android.content.Context ctx, String id) {
        if (ICONS.containsKey(id)) return ICONS.get(id);
        android.graphics.Bitmap b = null;
        java.io.InputStream in = null;
        try {
            in = ctx.getAssets().open("icons/" + id + ".png");
            b = android.graphics.BitmapFactory.decodeStream(in);
        } catch (Exception ignored) {
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
        }
        ICONS.put(id, b);
        return b;
    }

    private static final java.util.HashMap<String, android.graphics.Bitmap> BUTTONS =
            new java.util.HashMap<String, android.graphics.Bitmap>();

    /** Icone de botao do controle (assets/botoes, Xelu's Free Controller Prompts, CC0). */
    static synchronized android.graphics.Bitmap button(android.content.Context ctx, String id) {
        if (BUTTONS.containsKey(id)) return BUTTONS.get(id);
        android.graphics.Bitmap b = null;
        java.io.InputStream in = null;
        try {
            in = ctx.getAssets().open("botoes/" + id + ".png");
            b = android.graphics.BitmapFactory.decodeStream(in);
        } catch (Exception ignored) {
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
        }
        BUTTONS.put(id, b);
        return b;
    }

    private static final java.util.HashMap<String, android.graphics.Bitmap> WHITE_LOGOS =
            new java.util.HashMap<String, android.graphics.Bitmap>();

    /** Logo do console em branco (assets/logos_brancos, ver art_src/logos_brancos.py), ou null. */
    static synchronized android.graphics.Bitmap whiteLogo(android.content.Context ctx, String id) {
        if (WHITE_LOGOS.containsKey(id)) return WHITE_LOGOS.get(id);
        android.graphics.Bitmap b = null;
        java.io.InputStream in = null;
        try {
            in = ctx.getAssets().open("logos_brancos/" + id + ".png");
            b = android.graphics.BitmapFactory.decodeStream(in);
        } catch (Exception ignored) {
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
        }
        WHITE_LOGOS.put(id, b);
        return b;
    }

    /**
     * Desenha o logo branco do console dentro da caixa (centrado, ou encostado a esquerda).
     * Console sem logo (Neo Geo tem; "Jogos de computador" nao): escreve o nome, em negrito branco.
     */
    static void drawWhiteLogo(android.content.Context ctx, Canvas c, Catalog.Sys s, RectF box, Paint p, boolean center) {
        android.graphics.Bitmap b = whiteLogo(ctx, s.id);
        if (b != null) {
            float k = Math.min(box.width() / b.getWidth(), box.height() / b.getHeight());
            float w = b.getWidth() * k, h = b.getHeight() * k;
            float x = center ? box.centerX() - w / 2 : box.left, y = box.centerY() - h / 2;
            c.drawBitmap(b, null, new RectF(x, y, x + w, y + h), p);
            return;
        }
        String t = s.name.toUpperCase(java.util.Locale.US);
        p.setColor(0xFFFFFFFF);
        p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        p.setTextAlign(center ? Paint.Align.CENTER : Paint.Align.LEFT);
        p.setTextSize(box.height() * 0.78f);
        while (p.measureText(t) > box.width() && p.getTextSize() > 4) p.setTextSize(p.getTextSize() * 0.92f);
        c.drawText(t, center ? box.centerX() : box.left, box.centerY() + p.getTextSize() * 0.36f, p);
    }

    private static final java.util.HashMap<String, android.graphics.Bitmap> LOGOS =
            new java.util.HashMap<String, android.graphics.Bitmap>();

    /** Logo do sistema (assets/logos/<id>.png, do tema es-theme-carbon), ja sem as margens transparentes. */
    static synchronized android.graphics.Bitmap logoBitmap(android.content.Context ctx, String id) {
        if (LOGOS.containsKey(id)) return LOGOS.get(id);
        android.graphics.Bitmap b = null;
        java.io.InputStream in = null;
        try {
            in = ctx.getAssets().open("logos/" + id + ".png");
            android.graphics.Bitmap raw = android.graphics.BitmapFactory.decodeStream(in);
            int w = raw.getWidth(), h = raw.getHeight();
            int[] px = new int[w * h];
            raw.getPixels(px, 0, w, 0, 0, w, h);
            int x0 = w, y0 = h, x1 = -1, y1 = -1;
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                    if ((px[y * w + x] >>> 24) > 16) {
                        if (x < x0) x0 = x;
                        if (x > x1) x1 = x;
                        if (y < y0) y0 = y;
                        if (y > y1) y1 = y;
                    }
            b = x1 >= x0 ? android.graphics.Bitmap.createBitmap(raw, x0, y0, x1 - x0 + 1, y1 - y0 + 1) : raw;
        } catch (Exception ignored) {
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
        }
        LOGOS.put(id, b);
        return b;
    }

    /** Desenha o logo numa plaquinha clara (o logo e colorido e sumiria no cartao). false = nao ha logo. */
    static boolean drawLogo(android.content.Context ctx, Canvas c, String id, RectF box, float rad, boolean plate) {
        android.graphics.Bitmap b = logoBitmap(ctx, id);
        if (b == null) return false;
        Paint ip = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        if (plate) {
            ip.setColor(0xF2FFFFFF);
            c.drawRoundRect(box, rad, rad, ip);
        }
        float pad = box.height() * 0.14f;
        float bw = b.getWidth(), bh = b.getHeight();
        float k = Math.min((box.width() - 2 * pad) / bw, (box.height() - 2 * pad) / bh);
        float cx = box.centerX(), cy = box.centerY();
        c.drawBitmap(b, null, new RectF(cx - bw * k / 2, cy - bh * k / 2, cx + bw * k / 2, cy + bh * k / 2), ip);
        return true;
    }

    /** Icones monocromaticos (brancos) recebem a cor do tema. */
    static boolean mono(String id) { return "apps".equals(id) || "config".equals(id); }

    /** Desenha o icone baixado num quadrado centrado em (cx,cy); devolve false se nao houver. */
    static boolean drawIcon(android.content.Context ctx, Canvas c, String id, float cx, float cy, float size, int tint) {
        android.graphics.Bitmap b = iconBitmap(ctx, id);
        if (b == null) return false;
        Paint ip = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        if (mono(id)) ip.setColorFilter(new android.graphics.PorterDuffColorFilter(tint, android.graphics.PorterDuff.Mode.SRC_IN));
        float bw = b.getWidth(), bh = b.getHeight(), k = size / Math.max(bw, bh);
        RectF dst = new RectF(cx - bw * k / 2, cy - bh * k / 2, cx + bw * k / 2, cy + bh * k / 2);
        c.drawBitmap(b, null, dst, ip);
        return true;
    }

    static int blend(int c1, int c2, float t) {
        int a = (int) (Color.alpha(c1) + (Color.alpha(c2) - Color.alpha(c1)) * t);
        int r = (int) (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * t);
        int g = (int) (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * t);
        int b = (int) (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * t);
        return Color.argb(a, r, g, b);
    }
    static int lighten(int c, float t) { return blend(c, 0xFFFFFFFF, t); }
    static int darken(int c, float t) { return blend(c, 0xFF000000, t); }
    static int alpha(int c, int a) {
        if (a < 0) a = 0;
        if (a > 255) a = 255;
        return (c & 0x00FFFFFF) | (a << 24);
    }

    /** Icone centrado em (cx,cy); s = tamanho de referencia; fg = corpo; hole = recortes. */
    static void icon(Canvas c, Paint p, int kind, float cx, float cy, float s, int fg, int hole) {
        RectF r = new RectF();
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        switch (kind) {
            case ICON_WIDE: {
                p.setColor(fg);
                r.set(cx - s, cy - s * 0.52f, cx + s, cy + s * 0.52f);
                c.drawRoundRect(r, s * 0.42f, s * 0.42f, p);
                p.setColor(hole);
                r.set(cx - s * 0.46f, cy - s * 0.36f, cx + s * 0.46f, cy + s * 0.30f);
                c.drawRoundRect(r, s * 0.05f, s * 0.05f, p);
                cross(c, p, cx - s * 0.72f, cy - s * 0.02f, s * 0.11f);
                c.drawCircle(cx + s * 0.64f, cy + s * 0.08f, s * 0.09f, p);
                c.drawCircle(cx + s * 0.82f, cy - s * 0.09f, s * 0.09f, p);
                break;
            }
            case ICON_TALL: {
                p.setColor(fg);
                r.set(cx - s * 0.58f, cy - s * 0.85f, cx + s * 0.58f, cy + s * 0.85f);
                c.drawRoundRect(r, s * 0.12f, s * 0.12f, p);
                p.setColor(hole);
                r.set(cx - s * 0.42f, cy - s * 0.70f, cx + s * 0.42f, cy - s * 0.08f);
                c.drawRoundRect(r, s * 0.05f, s * 0.05f, p);
                cross(c, p, cx - s * 0.26f, cy + s * 0.32f, s * 0.10f);
                c.drawCircle(cx + s * 0.20f, cy + s * 0.40f, s * 0.085f, p);
                c.drawCircle(cx + s * 0.38f, cy + s * 0.26f, s * 0.085f, p);
                break;
            }
            case ICON_PAD: {
                p.setColor(fg);
                r.set(cx - s, cy - s * 0.42f, cx + s, cy + s * 0.30f);
                c.drawRoundRect(r, s * 0.36f, s * 0.36f, p);
                c.drawCircle(cx - s * 0.66f, cy + s * 0.22f, s * 0.36f, p);
                c.drawCircle(cx + s * 0.66f, cy + s * 0.22f, s * 0.36f, p);
                p.setColor(hole);
                cross(c, p, cx - s * 0.62f, cy - s * 0.04f, s * 0.11f);
                float bx = cx + s * 0.62f, by = cy - s * 0.04f, d = s * 0.15f, br = s * 0.075f;
                c.drawCircle(bx, by - d, br, p);
                c.drawCircle(bx, by + d, br, p);
                c.drawCircle(bx - d, by, br, p);
                c.drawCircle(bx + d, by, br, p);
                r.set(cx - s * 0.22f, cy - s * 0.10f, cx - s * 0.05f, cy - s * 0.02f);
                c.drawRoundRect(r, s * 0.03f, s * 0.03f, p);
                r.set(cx + s * 0.05f, cy - s * 0.10f, cx + s * 0.22f, cy - s * 0.02f);
                c.drawRoundRect(r, s * 0.03f, s * 0.03f, p);
                break;
            }
            case ICON_APPS: {
                p.setColor(fg);
                float q = s * 0.36f, g = s * 0.13f, st = -(q * 1.5f + g);
                for (int i = 0; i < 3; i++)
                    for (int j = 0; j < 3; j++) {
                        float x = cx + st + j * (q + g), y = cy + st + i * (q + g);
                        r.set(x, y, x + q, y + q);
                        c.drawRoundRect(r, q * 0.25f, q * 0.25f, p);
                    }
                break;
            }
            case ICON_GEAR: {
                p.setColor(fg);
                c.drawCircle(cx, cy, s * 0.55f, p);
                for (int i = 0; i < 8; i++) {
                    c.save();
                    c.rotate(i * 45f, cx, cy);
                    r.set(cx - s * 0.13f, cy - s * 0.80f, cx + s * 0.13f, cy - s * 0.40f);
                    c.drawRoundRect(r, s * 0.04f, s * 0.04f, p);
                    c.restore();
                }
                p.setColor(hole);
                c.drawCircle(cx, cy, s * 0.22f, p);
                break;
            }
            case ICON_PC: {
                p.setColor(fg);
                r.set(cx - s, cy - s * 0.72f, cx + s, cy + s * 0.48f);
                c.drawRoundRect(r, s * 0.10f, s * 0.10f, p);
                r.set(cx - s * 0.20f, cy + s * 0.48f, cx + s * 0.20f, cy + s * 0.74f);
                c.drawRect(r, p);
                r.set(cx - s * 0.56f, cy + s * 0.70f, cx + s * 0.56f, cy + s * 0.84f);
                c.drawRoundRect(r, s * 0.05f, s * 0.05f, p);
                p.setColor(hole);
                r.set(cx - s * 0.86f, cy - s * 0.58f, cx + s * 0.86f, cy + s * 0.34f);
                c.drawRoundRect(r, s * 0.04f, s * 0.04f, p);
                break;
            }
            default:
                break;
        }
    }

    private static void cross(Canvas c, Paint p, float x, float y, float d) {
        c.drawRect(x - d * 1.6f, y - d * 0.55f, x + d * 1.6f, y + d * 0.55f, p);
        c.drawRect(x - d * 0.55f, y - d * 1.6f, x + d * 0.55f, y + d * 1.6f, p);
    }
}
