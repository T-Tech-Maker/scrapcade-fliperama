package com.thiago.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

/**
 * Carrossel de capas desenhado pela CPU: reserva para quando a GPU falhar (ver CarouselGL), sem perspectiva.
 * As medidas e a arte das capas ficam aqui e valem para os dois: tudo em proporcao da tela.
 */
final class Carousel extends View implements CarouselApi {
    /** Quantas capas ficam prontas de cada lado da selecionada. */
    static final int SIDE = 4;

    private int animMs = 200;
    private List<Catalog.Game> items = new ArrayList<Catalog.Game>();
    private float pos, animFrom;
    private int target;
    private long animStart;
    private boolean animating, covers = true;
    private final Paint bp = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF r = new RectF();
    private final float u;
    private Listener listener;
    private final HashMap<String, Bitmap> cards = new HashMap<String, Bitmap>();
    private final HashMap<String, Bitmap> backs = new HashMap<String, Bitmap>();

    Carousel(Context c) {
        super(c);
        u = unit(c);
    }

    public View view() { return this; }
    public void setListener(Listener l) { listener = l; }
    public void setAnimMs(int ms) { animMs = ms; }

    public void setShowCovers(boolean on) {
        if (covers == on) return;
        covers = on;
        clearCache();
    }

    /** O fundo do jogo so existe no desenho pela GPU: pela CPU ele pesaria na animacao. */
    public void setShowBackdrop(boolean on) { }

    public void setItems(List<Catalog.Game> l, int sel) {
        items = l;
        target = sel;
        pos = sel;
        animating = false;
        invalidate();
    }

    public void clearCache() {
        for (Bitmap b : cards.values()) b.recycle();
        for (Bitmap b : backs.values()) b.recycle();
        cards.clear();
        backs.clear();
        invalidate();
    }

    public Catalog.Game current() { return items.isEmpty() ? null : items.get(mod(target, items.size())); }

    public int index() { return items.isEmpty() ? -1 : mod(target, items.size()); }

    public void move(int dir) {
        if (items.size() < 2) return;
        animFrom = pos;
        target += dir;
        animStart = SystemClock.uptimeMillis();
        animating = true;
        invalidate();
    }

    static int mod(int a, int n) { int m = a % n; return m < 0 ? m + n : m; }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP && listener != null) {
            float x = e.getX(), w = getWidth();
            if (x < w * 0.32f) { move(-1); listener.onMoved(); }
            else if (x > w * 0.68f) { move(1); listener.onMoved(); }
            else if (current() != null) listener.onPick(current());
        }
        return true;
    }

    // ------------------------------------------------------------ medidas (usadas tambem pela GPU)

    /**
     * Unidade de tamanho da tela: 1,5 pixel em 800x480. Tudo no menu e medido nela, e nao na densidade
     * do Android: assim o desenho mantem a proporcao em qualquer resolucao.
     */
    static float unit(Context c) {
        DisplayMetrics m = c.getResources().getDisplayMetrics();
        float w = Math.max(m.widthPixels, m.heightPixels), h = Math.min(m.widthPixels, m.heightPixels);
        return Math.min(w / 533.33f, h / 320f);
    }

    /** Faixa do nome do jogo, acima da barra de botoes, em unidades. */
    static final float TITLE_H = 30;
    /** Tamanho das capas laterais em relacao a selecionada. */
    static final float SIDE_SCALE = 0.68f;

    /** Altura util: acima da faixa do nome e da barra de botoes. */
    static float areaH(float h, float u) { return h - (TITLE_H + HintBar.H) * u; }

    static float coverH(float h, float u) { return areaH(h, u) * 0.66f; }

    static float coverW(float w, float h, float u) { return Math.min(w * 0.24f, coverH(h, u) * 0.72f); }

    static float coverY(float h, float u) { return areaH(h, u) * 0.485f; }

    /** Escala da capa, de 0 (selecionada) a 1 (lateral). */
    static float coverScale(float a) { return 1f - (1f - SIDE_SCALE) * a; }

    /** As capas laterais descem um pouco: ficam mais perto do chao do que do teto. */
    static float coverDrop(float a, float h, float u) { return coverH(h, u) * (1f - SIDE_SCALE) * 0.30f * a; }

    /** Distancia horizontal ao centro: capas bem juntas, ocupando a largura toda. */
    static float coverX(float off, float w, float h, float u) {
        float a = Math.abs(off), cw = coverW(w, h, u);
        float near = cw * (1f + SIDE_SCALE) / 2 + w * 0.010f, far = cw * SIDE_SCALE + w * 0.008f;
        float x = Math.min(a, 1f) * near + Math.max(a - 1f, 0f) * far;
        return off < 0 ? -x : x;
    }

    /** Folga em volta da capa, na imagem, para a sombra. */
    static int margin(float u) { return (int) (3 * u); }

    // ------------------------------------------------------------ arte da capa

    /**
     * Desenha a capa. Le a imagem do cartao: nao chamar na thread da tela.
     * Capa com imagem nao leva texto (o nome ja esta nela e na faixa de baixo). Caixa mais baixa que a de jogo
     * em pe (console costuma ter capa quase quadrada) fica apoiada no chao, sem deformar.
     * Devolve quanto o corpo ficou abaixo do topo de (m,m), para a borda da selecionada acompanhar.
     */
    static float drawCover(Context ctx, Canvas c, Catalog.Game g, float cw, float ch, int m, float u, boolean showArt) {
        Catalog.Sys s = g.sys;
        Paint q = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        float rad = 2 * u, x = m + cw / 2, band = ch * 0.075f, inset = u;
        Bitmap b = showArt ? decode(Catalog.cover(g.rom), cw, ch) : null;
        float top = 0, side = 0;
        if (b != null) {
            // A caixa segue o formato da propria capa, sem esticar nem cortar: Super Nintendo americano fica largo
            // e baixo, PlayStation e Game Boy Advance quase quadrados, Mega Drive e NES em pe. Sempre apoiada no chao.
            float maxW = cw - 2 * inset, maxH = ch - band - inset, ar = b.getWidth() / (float) b.getHeight();
            float artW = maxW, artH = maxW / ar;
            if (artH > maxH) {
                artH = maxH;
                artW = maxH * ar;
            }
            top = ch - band - artH - inset;
            side = (maxW - artW) / 2;
        }
        setLast(new float[] {top, side});
        RectF rr = new RectF(m + side, m + top, m + cw - side, m + ch);
        Theme.card(c, q, rr, rad, s.color, u);
        c.save();
        c.clipRect(rr.left, rr.top, rr.right, rr.top + band);
        q.setColor(Theme.band(s.color));
        c.drawRoundRect(rr, rad, rad, q);
        c.restore();
        // logo branco do console na faixa, como a lombada de uma caixa de jogo
        Art.drawWhiteLogo(ctx, c, s, new RectF(x - rr.width() * 0.40f, rr.top + band * 0.20f, x + rr.width() * 0.40f, rr.top + band * 0.80f), q, true);
        RectF art = new RectF(rr.left + inset, rr.top + band, rr.right - inset, rr.bottom - inset);
        if (b != null) {
            drawArt(c, q, b, art);
            b.recycle();
        } else {
            // sem imagem: so o nome do jogo (o console ja esta na faixa da capa e no canto da tela)
            drawTitle(c, q, g.title, art.left, art.top, art.right, art.bottom, Theme.cardText(s.color));
        }
        return top;
    }

    /** Le a capa ja reduzida para perto do tamanho final; null se nao houver ou nao der. */
    /**
     * Fundo do jogo para a GPU: a imagem recortada no formato da tela, bem borrada (reduzida a poucos pontos e
     * ampliada com filtro) e escurecida, numa textura de 256x256. null se o jogo nao tem imagem.
     */
    static Bitmap backdropBitmap(File f, int w, int h) {
        Bitmap src = decode(f, 96, 58);
        if (src == null) return null;
        float sw = src.getWidth(), sh = src.getHeight(), ar = w / (float) h;
        float cw = Math.min(sw, sh * ar), ch = cw / ar;
        Rect crop = new Rect((int) ((sw - cw) / 2), (int) ((sh - ch) / 2), (int) ((sw + cw) / 2), (int) ((sh + ch) / 2));
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        Bitmap tiny = Bitmap.createBitmap(56, 34, Bitmap.Config.ARGB_8888);
        new Canvas(tiny).drawBitmap(src, crop, new Rect(0, 0, 56, 34), p);
        src.recycle();
        Bitmap out = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawBitmap(tiny, null, new Rect(0, 0, 256, 256), p);
        tiny.recycle();
        c.drawColor(0x70000000);      // escurece: as capas e os textos ficam na frente
        return out;
    }

    private static Bitmap decode(File f, float tw, float th) {
        if (f == null) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= tw && o.outHeight / (sample * 2) >= th) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        } catch (Throwable t) {
            return null;
        }
    }

    /** A caixa ja tem o formato da capa: a imagem entra inteira. */
    private static void drawArt(Canvas c, Paint q, Bitmap b, RectF art) {
        q.setShader(null);
        c.drawBitmap(b, null, art, q);
    }

    /** Recuo da ultima capa desenhada: {de cima, de cada lado}, em pixels. Lido logo depois, na mesma thread. */
    private static final ThreadLocal<float[]> LAST = new ThreadLocal<float[]>();

    private static void setLast(float[] v) { LAST.set(v); }

    static float lastSide() {
        float[] v = LAST.get();
        return v == null ? 0 : v[1];
    }

    /** Nome do jogo centrado na caixa, em ate 5 linhas. */
    private static void drawTitle(Canvas c, Paint q, String t, float l, float top, float r, float bottom, int col) {
        float w = (r - l) * 0.88f, avail = bottom - top;
        float size = (r - l) * 0.125f;
        q.setShader(null);
        q.setStyle(Paint.Style.FILL);
        q.setColor(col);
        q.setTypeface(Typeface.DEFAULT_BOLD);
        q.setTextAlign(Paint.Align.CENTER);
        q.setTextSize(size);
        // A linha so quebra em espaco: palavra comprida ("Vehicle-001/II") encolhe a letra, para nao sair da capa
        float longest = 0;
        for (String word : t.split(" ")) longest = Math.max(longest, q.measureText(word));
        if (longest > w) {
            size *= w / longest;
            q.setTextSize(size);
        }
        List<String> lines = wrap(q, t, w, Math.max(1, Math.min(5, (int) (avail / (size * 1.2f)))));
        float lh = size * 1.2f, y = top + (avail - lh * lines.size()) / 2 + size;
        c.save();
        c.clipRect(l, top, r, bottom);
        for (String s : lines) {
            c.drawText(s, (l + r) / 2, y, q);
            y += lh;
        }
        c.restore();
    }

    private static List<String> wrap(Paint q, String t, float w, int maxLines) {
        List<String> out = new ArrayList<String>();
        String cur = "";
        for (String word : t.split(" ")) {
            String next = cur.length() == 0 ? word : cur + " " + word;
            if (q.measureText(next) <= w || cur.length() == 0) {
                cur = next;
            } else {
                out.add(cur);
                cur = word;
            }
        }
        out.add(cur);
        while (out.size() > maxLines) {   // junta o excesso na ultima linha, que recebe reticencias abaixo
            String a = out.remove(out.size() - 1);
            out.set(out.size() - 1, out.get(out.size() - 1) + " " + a);
        }
        int last = out.size() - 1;
        String s = out.get(last);
        if (q.measureText(s) > w) {
            while (s.length() > 1 && q.measureText(s + "...") > w) s = s.substring(0, s.length() - 1);
            out.set(last, s.trim() + "...");
        }
        return out;
    }

    // ------------------------------------------------------------ desenho pela CPU

    private Bitmap back(int col) {
        int w = getWidth(), h = getHeight();
        String key = col + "|" + Theme.current + "|" + w + "x" + h;
        Bitmap b = backs.get(key);
        if (b == null && w > 0 && h > 0) {
            b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Theme.background(new Canvas(b), new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG), w, h, col, u);
            backs.put(key, b);
        }
        return b;
    }

    private Bitmap card(Catalog.Game g) {
        String key = g.key() + "|" + Theme.current;
        Bitmap b = cards.get(key);
        if (b != null) return b;
        float cw = coverW(getWidth(), getHeight(), u), ch = coverH(getHeight(), u);
        int m = margin(u);
        b = Bitmap.createBitmap((int) cw + 2 * m, (int) ch + 2 * m, Bitmap.Config.ARGB_8888);
        drawCover(getContext(), new Canvas(b), g, cw, ch, m, u, covers);
        cards.put(key, b);
        return b;
    }

    /** Com muitos jogos a memoria acabaria: guarda so as capas perto da selecionada. */
    private void trim(int n, int base) {
        if (cards.size() <= 2 * SIDE + 3) return;
        List<String> keep = new ArrayList<String>();
        for (int k = -SIDE - 1; k <= SIDE + 1; k++) keep.add(items.get(mod(base + k, n)).key() + "|" + Theme.current);
        for (Iterator<java.util.Map.Entry<String, Bitmap>> it = cards.entrySet().iterator(); it.hasNext(); ) {
            java.util.Map.Entry<String, Bitmap> e = it.next();
            if (!keep.contains(e.getKey())) {
                e.getValue().recycle();
                it.remove();
            }
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight(), n = items.size();
        if (n == 0) {
            Bitmap b = back(0xFF2B68C8);
            if (b != null) c.drawBitmap(b, 0, 0, null);
            return;
        }
        if (animating) {
            float t = (SystemClock.uptimeMillis() - animStart) / (float) animMs;
            if (t >= 1f) { t = 1f; animating = false; }
            float e = 1f - (1f - t) * (1f - t) * (1f - t);
            pos = animFrom + (target - animFrom) * e;
        } else {
            pos = target;
        }
        int i0 = (int) Math.floor(pos);
        float fr = pos - i0;
        int c0 = Theme.backColor(items.get(mod(i0, n)).sys.color), c1 = Theme.backColor(items.get(mod(i0 + 1, n)).sys.color);
        Bitmap b0 = back(c0);
        if (b0 != null) c.drawBitmap(b0, 0, 0, null);
        if (fr > 0.01f && c1 != c0) {
            Bitmap b1 = back(c1);
            if (b1 != null) {
                bp.setAlpha((int) (fr * 255));
                c.drawBitmap(b1, 0, 0, bp);
                bp.setAlpha(255);
            }
        }
        float cy = coverY(h, u);
        int base = Math.round(pos);
        float bw = coverW(w, h, u) + 2 * margin(u), bh = coverH(h, u) + 2 * margin(u);
        for (int s = SIDE; s >= 0; s--) {
            for (int sign = -1; sign <= 1; sign += 2) {
                if (s == 0 && sign > 0) continue;
                int k = s * sign;
                if (k < -(n - 1) / 2 || k > n / 2) continue;   // poucos jogos: sem capa repetida na tela
                int i = base + k;
                float off = i - pos;
                float a = Math.min(1f, Math.abs(off));
                float scale = coverScale(a), y = cy + coverDrop(a, h, u);
                float x = w / 2f + coverX(off, w, h, u);
                if (x + bw < 0 || x - bw > w) continue;
                Bitmap cb = card(items.get(mod(i, n)));
                r.set(x - bw * scale / 2, y - bh * scale / 2, x + bw * scale / 2, y + bh * scale / 2);
                bp.setAlpha(255);
                c.drawBitmap(cb, null, r, bp);
            }
        }
        bp.setAlpha(255);
        trim(n, base);
        if (animating) postInvalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w != ow || h != oh) clearCache();
    }
}
