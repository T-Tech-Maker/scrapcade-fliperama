package com.thiago.launcher;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;

/** Temas visuais do fliperama (escolhidos no menu de opcoes com SELECT). */
final class Theme {
    static final int CARBONO = 0, NEON = 1, ARCADE = 2, CLARO = 3, AURORA = 4;
    static final String[] NAMES = {"Carbono", "Neon", "Arcade", "Claro", "Aurora"};
    static int current = AURORA;
    /** Para ler o fundo do tema Aurora dos assets. */
    static android.content.Context ctx;

    private Theme() { }

    /** Cor que manda no fundo: no Aurora o fundo e um so, nao muda com o console. */
    static int backColor(int col) { return current == AURORA ? 0 : col; }

    /** Borda da capa selecionada. */
    static int accent(int col) { return current == AURORA ? 0xFF32E6A8 : Art.lighten(col, 0.35f); }

    private static void aurora(Canvas c, Paint p, int w, int h) {
        p.setShader(null);
        p.setColor(0xFF0B1660);
        c.drawRect(0, 0, w, h, p);
        java.io.InputStream in = null;
        try {
            in = ctx.getAssets().open("fundo.jpg");
            android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeStream(in);
            Paint q = new Paint(Paint.FILTER_BITMAP_FLAG);
            c.drawBitmap(b, null, new RectF(0, 0, w, h), q);
            b.recycle();
        } catch (Exception ignored) {
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
        }
    }

    static boolean light() { return current == CLARO; }

    /** Cor do texto principal sobre o fundo. */
    static int text() { return light() ? 0xFF1E2229 : 0xFFFFFFFF; }

    static int textDim() { return light() ? 0x991E2229 : 0xCCFFFFFF; }

    /** Fundo de tela inteira para a cor de um sistema. */
    static void background(Canvas c, Paint p, int w, int h, int col, float d) {
        p.setStyle(Paint.Style.FILL);
        switch (current) {
            case AURORA:
                aurora(c, p, w, h);
                break;
            case NEON: {
                p.setShader(null);
                p.setColor(0xFF020205);
                c.drawRect(0, 0, w, h, p);
                p.setShader(new RadialGradient(w / 2f, h * 0.47f, w * 0.55f, Art.alpha(col, 80), 0, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, h, p);
                p.setShader(null);
                p.setColor(Art.alpha(Art.lighten(col, 0.3f), 26));
                p.setStrokeWidth(1);
                float step = 32 * d;
                for (float x = 0; x < w; x += step) c.drawLine(x, 0, x, h, p);
                for (float y = 0; y < h; y += step) c.drawLine(0, y, w, y, p);
                break;
            }
            case ARCADE: {
                p.setShader(new LinearGradient(0, 0, 0, h * 0.62f, 0xFF12052A, Art.blend(0xFF5B1466, col, 0.25f),
                        Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, h * 0.62f, p);
                p.setShader(new LinearGradient(0, h * 0.62f, 0, h, 0xFF1A0631, 0xFF050208, Shader.TileMode.CLAMP));
                c.drawRect(0, h * 0.62f, w, h, p);
                p.setShader(null);
                // sol de linhas
                p.setColor(0x55FF7AC8);
                for (int i = 0; i < 7; i++) {
                    float y = h * 0.62f - i * 9 * d;
                    float half = (float) Math.sqrt(Math.max(0, 1 - Math.pow(i / 7.0, 2))) * w * 0.16f;
                    c.drawRect(w / 2f - half, y - 3 * d, w / 2f + half, y, p);
                }
                // grade em perspectiva no "chao"
                p.setColor(0x66FF3EA5);
                p.setStrokeWidth(1.5f * d);
                float hz = h * 0.62f;
                for (int i = -10; i <= 10; i++) c.drawLine(w / 2f + i * w * 0.02f, hz, w / 2f + i * w * 0.16f, h, p);
                for (int i = 0; i < 8; i++) {
                    float t = i / 8f;
                    float y = hz + (h - hz) * t * t;
                    c.drawLine(0, y, w, y, p);
                }
                break;
            }
            case CLARO: {
                p.setShader(new LinearGradient(0, 0, 0, h, 0xFFF4F6F9, Art.blend(0xFFF4F6F9, col, 0.18f),
                        Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, h, p);
                p.setShader(null);
                break;
            }
            default: {
                p.setShader(new LinearGradient(0, 0, 0, h, Art.darken(col, 0.45f), 0xFF06070A, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, h, p);
                p.setShader(new RadialGradient(w / 2f, h * 0.47f, w * 0.42f, Art.alpha(Art.lighten(col, 0.25f), 110),
                        0, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, h, p);
                p.setShader(null);
                break;
            }
        }
    }

    /** Corpo do cartao (sem conteudo). */
    static void card(Canvas c, Paint p, RectF r, float rad, int col, float d) {
        p.setStyle(Paint.Style.FILL);
        p.setShader(null);
        switch (current) {
            case AURORA: {
                p.setColor(0xFF15171D);
                c.drawRoundRect(r, rad, rad, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(d);
                p.setColor(Art.lighten(col, 0.15f));
                c.drawRoundRect(r, rad, rad, p);
                break;
            }
            case NEON: {
                p.setColor(0xE6050508);
                c.drawRoundRect(r, rad, rad, p);
                p.setStyle(Paint.Style.STROKE);
                p.setColor(Art.alpha(Art.lighten(col, 0.35f), 70));
                p.setStrokeWidth(9 * d);
                c.drawRoundRect(r, rad, rad, p);
                p.setColor(Art.lighten(col, 0.45f));
                p.setStrokeWidth(2.5f * d);
                c.drawRoundRect(r, rad, rad, p);
                break;
            }
            case CLARO: {
                p.setColor(0x22000000);
                RectF s = new RectF(r);
                s.offset(0, 4 * d);
                c.drawRoundRect(s, rad, rad, p);
                p.setColor(0xFFFFFFFF);
                c.drawRoundRect(r, rad, rad, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(3 * d);
                p.setColor(col);
                c.drawLine(r.left + rad, r.bottom - 1.5f * d, r.right - rad, r.bottom - 1.5f * d, p);
                break;
            }
            case ARCADE: {
                p.setShader(new LinearGradient(0, r.top, 0, r.bottom, Art.blend(col, 0xFFFF3EA5, 0.25f),
                        Art.darken(col, 0.45f), Shader.TileMode.CLAMP));
                c.drawRoundRect(r, rad, rad, p);
                p.setShader(null);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2.5f * d);
                p.setColor(0xFFFFD34E);
                c.drawRoundRect(r, rad, rad, p);
                break;
            }
            default: {
                p.setColor(0x66000000);
                RectF s = new RectF(r);
                s.offset(0, 7 * d);
                c.drawRoundRect(s, rad, rad, p);
                p.setShader(new LinearGradient(0, r.top, 0, r.bottom, Art.lighten(col, 0.20f), Art.darken(col, 0.28f),
                        Shader.TileMode.CLAMP));
                c.drawRoundRect(r, rad, rad, p);
                p.setShader(null);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2 * d);
                p.setColor(0xA6FFFFFF);
                c.drawRoundRect(r, rad, rad, p);
                break;
            }
        }
        p.setStyle(Paint.Style.FILL);
        p.setShader(null);
    }

    /** Faixa do console no alto da capa, como a lombada de uma caixa de jogo. */
    static int band(int col) {
        if (current == CLARO || current == AURORA) return col;
        if (current == NEON) return Art.darken(col, 0.55f);
        if (current == ARCADE) return Art.blend(col, 0xFFFF3EA5, 0.45f);
        return Art.darken(col, 0.30f);
    }

    static int cardText(int col) {
        if (current == CLARO) return Art.darken(col, 0.25f);
        if (current == NEON) return Art.lighten(col, 0.55f);
        return 0xFFFFFFFF;
    }

    static int iconFg(int col) {
        if (current == CLARO) return col;
        if (current == NEON) return Art.lighten(col, 0.45f);
        return 0xEEFFFFFF;
    }

    static int iconHole(int col) {
        if (current == CLARO) return 0xFFFFFFFF;
        if (current == NEON) return 0xFF050508;
        return Art.darken(col, 0.30f);
    }

    /** Fundo das telas de lista (jogos/apps). */
    static GradientDrawable listBackground(int col) {
        int top, bottom;
        switch (current) {
            case AURORA: top = 0xFF0B1660; bottom = 0xFF1E3AB0; break;
            case NEON: top = Art.blend(0xFF020205, col, 0.18f); bottom = 0xFF020205; break;
            case ARCADE: top = Art.blend(0xFF12052A, col, 0.2f); bottom = 0xFF050208; break;
            case CLARO: top = 0xFFF4F6F9; bottom = Art.blend(0xFFF4F6F9, col, 0.18f); break;
            default: top = Art.darken(col, 0.45f); bottom = 0xFF06070A; break;
        }
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
    }

    /** Cor do destaque da linha selecionada. */
    static int highlight(int col) {
        if (current == NEON) return Art.alpha(Art.lighten(col, 0.1f), 150);
        if (current == ARCADE) return Art.alpha(Art.blend(col, 0xFFFF3EA5, 0.4f), 220);
        return Art.alpha(col, 235);
    }

    static int panel() { return light() ? 0x14000000 : 0x33000000; }
}
