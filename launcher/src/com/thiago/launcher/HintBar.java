package com.thiago.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/** Barra de dicas dos botoes no rodape, com os icones de assets/botoes. */
final class HintBar extends View {
    /** Altura, em unidades da tela (Carousel.unit). */
    static final float H = 22;

    private String[] hints = new String[0];   // pares: botoes separados por espaco, texto
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final float u;
    private boolean onPanel;

    HintBar(Context c) {
        super(c);
        u = Carousel.unit(c);
    }

    void set(String... h) { hints = h; invalidate(); }

    /** Dentro dos paineis brancos: sem fundo proprio, em qualquer tema. */
    void setOnPanel() { onPanel = true; invalidate(); }

    @Override
    protected void onMeasure(int ws, int hs) {
        setMeasuredDimension(MeasureSpec.getSize(ws), (int) (H * u));
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        boolean white = onPanel || Theme.current == Theme.AURORA;
        p.setStyle(Paint.Style.FILL);
        if (!onPanel) {
            p.setColor(white ? 0xF7FFFFFF : 0xA0000000);
            c.drawRect(0, 0, w, h, p);
        }
        float x = 12 * u, cy = h / 2f, s = 15.5f * u;
        p.setTypeface(Typeface.DEFAULT);
        p.setTextSize(9.5f * u);
        p.setTextAlign(Paint.Align.LEFT);
        for (int i = 0; i + 1 < hints.length; i += 2) {
            for (String k : hints[i].split(" ")) {
                Bitmap b = Art.button(getContext(), k.toLowerCase(java.util.Locale.US));
                if (b != null) c.drawBitmap(b, null, new RectF(x, cy - s / 2, x + s, cy + s / 2), p);
                x += s + u;
            }
            p.setColor(white ? 0xFF5E6269 : 0xFFE6E9F0);
            c.drawText(hints[i + 1], x + 2 * u, cy + 3.4f * u, p);
            x += 2 * u + p.measureText(hints[i + 1]) + 13 * u;
        }
    }
}
