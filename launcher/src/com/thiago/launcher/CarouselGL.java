package com.thiago.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.os.Process;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Carrossel de capas desenhado pela GPU (PowerVR SGX540, OpenGL ES 1.1): a capa escolhida de frente,
 * as vizinhas viradas em perspectiva, com reflexo no chao.
 * Cada capa e desenhada UMA vez num Bitmap, por uma thread de fundo (ler a imagem do cartao demora),
 * e vira textura; a animacao so posiciona texturas, o que a GPU faz a 60 quadros por segundo.
 * So ficam na memoria as capas perto da selecionada. Ao pausar (jogo aberto) o contexto GL e destruido:
 * a memoria da GPU fica toda para o jogo.
 * Texto (nome do jogo, relogio) fica em Views normais por cima: nao redesenha a cada quadro.
 */
final class CarouselGL extends GLSurfaceView implements GLSurfaceView.Renderer, CarouselApi {
    private static final int BACK_W = 1024, BACK_H = 512;   // texturas potencia de 2
    private static final int EMPTY_COLOR = 0xFF2B68C8;
    // ordem de preparo das capas: primeiro a selecionada, depois para fora
    private static final int[] WARM = {0, 1, -1, 2, -2, 3, -3, 4, -4};

    private static final class Tex {
        int id;
        float u, v, top, side;     // top/side: quanto o corpo da capa ficou para dentro do quadro (capa larga ou estreita)

        void set(float t, float s) {
            top = t;
            side = s;
        }
    }

    private final float unit;
    private Listener listener;
    private final Runnable onFirstFrame;
    private boolean firstFrameDone;

    // estado compartilhado (protegido por "this")
    private List<Catalog.Game> items = new ArrayList<Catalog.Game>();
    private float pos, animFrom;
    private int target, animMs = 200;
    private long animStart;
    private boolean animating, wipe, showArt = true;
    private String doneKey, loadingKey;   // a thread de fundo prepara uma capa de cada vez
    private Bitmap doneBmp;
    private float doneTop, doneSide;
    private final HashSet<String> failed = new HashSet<String>();
    private boolean showBg = true, bgLoading;      // fundo do jogo: tambem preparado em thread de fundo
    private String bgDoneKey;
    private Bitmap bgDoneBmp;

    // so na thread da GPU
    private final HashMap<String, Tex> texs = new HashMap<String, Tex>();
    private int w, h;
    private final FloatBuffer vb = floats(16), tb = floats(8), cb = floats(16);
    private boolean measuring;
    private int mFrames;
    private long mStart;
    // fundo do jogo selecionado: uma textura so, que aparece quando o carrossel para e some quando ele anda
    private static final String BG = "gfundo";
    private Tex bgTex;
    private String bgTexKey, bgPendKey;     // jogo da textura atual; jogo que espera a atual sumir para entrar
    private Bitmap bgPendBmp;
    private float bgAlpha;
    private long bgLast;
    private boolean bgMoving;

    CarouselGL(Context c, Runnable onFirstFrame) {
        super(c);
        this.onFirstFrame = onFirstFrame;
        unit = Carousel.unit(c);
        setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        getHolder().setFormat(PixelFormat.RGBA_8888);
        setRenderer(this);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    private static FloatBuffer floats(int n) {
        return ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    public View view() { return this; }
    public void setListener(Listener l) { listener = l; }
    public synchronized void setAnimMs(int ms) { animMs = ms; }

    public synchronized void setShowCovers(boolean on) {
        if (showArt == on) return;
        showArt = on;
        clearCache();
    }

    public synchronized void setShowBackdrop(boolean on) {
        showBg = on;
        requestRender();
    }

    public synchronized void setItems(List<Catalog.Game> l, int sel) {
        items = l;
        target = sel;
        pos = sel;
        animating = false;
        requestRender();
    }

    public synchronized void clearCache() {
        wipe = true;
        failed.clear();
        requestRender();
    }

    public synchronized Catalog.Game current() {
        return items.isEmpty() ? null : items.get(Carousel.mod(target, items.size()));
    }

    public synchronized int index() {
        return items.isEmpty() ? -1 : Carousel.mod(target, items.size());
    }

    public synchronized void move(int dir) {
        if (items.size() < 2) return;
        animFrom = pos;
        target += dir;
        animStart = SystemClock.uptimeMillis();
        animating = true;
        requestRender();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP && listener != null) {
            float x = e.getX(), vw = getWidth();
            if (x < vw * 0.32f) { move(-1); listener.onMoved(); }
            else if (x > vw * 0.68f) { move(1); listener.onMoved(); }
            else if (current() != null) listener.onPick(current());
        }
        return true;
    }

    // ------------------------------------------------------------ GPU

    public void onSurfaceCreated(GL10 gl, EGLConfig cfg) {
        texs.clear();    // contexto novo: as texturas antigas nao existem mais
        bgTex = null;
        bgTexKey = null;
        gl.glDisable(GL10.GL_DITHER);
        gl.glDisable(GL10.GL_DEPTH_TEST);
        gl.glDisable(GL10.GL_LIGHTING);
        gl.glEnable(GL10.GL_TEXTURE_2D);
        gl.glEnableClientState(GL10.GL_VERTEX_ARRAY);
        gl.glEnableClientState(GL10.GL_TEXTURE_COORD_ARRAY);
        gl.glTexEnvx(GL10.GL_TEXTURE_ENV, GL10.GL_TEXTURE_ENV_MODE, GL10.GL_MODULATE);
        gl.glBlendFunc(GL10.GL_ONE, GL10.GL_ONE_MINUS_SRC_ALPHA);   // Bitmap do Android = alfa pre-multiplicado
        gl.glHint(GL10.GL_PERSPECTIVE_CORRECTION_HINT, GL10.GL_NICEST);
        gl.glClearColor(0f, 0f, 0f, 1f);
    }

    public void onSurfaceChanged(GL10 gl, int width, int height) {
        if (width != w || height != h) dropAll(gl);
        w = width;
        h = height;
        gl.glViewport(0, 0, w, h);
        gl.glMatrixMode(GL10.GL_PROJECTION);
        gl.glLoadIdentity();
        gl.glOrthof(0, w, h, 0, -1, 1);
        gl.glMatrixMode(GL10.GL_MODELVIEW);
        gl.glLoadIdentity();
    }

    private void dropAll(GL10 gl) {
        for (Tex t : texs.values()) gl.glDeleteTextures(1, new int[]{t.id}, 0);
        texs.clear();
        bgTex = null;
        bgTexKey = null;
    }

    /** Prepara o fundo do jogo na thread de fundo (ler e borrar a imagem demora). */
    private void startBackdrop(final Catalog.Game g) {
        bgLoading = true;
        final int sw = w, sh = h;
        new Thread(new Runnable() {
            public void run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                Bitmap out;
                try {
                    out = Carousel.backdropBitmap(Catalog.backdrop(g.rom), sw, sh);
                } catch (Throwable t) {
                    out = null;      // sem imagem ou sem memoria: fica o fundo do tema
                }
                synchronized (CarouselGL.this) {
                    bgLoading = false;
                    if (bgDoneBmp != null) bgDoneBmp.recycle();
                    bgDoneKey = g.key();
                    bgDoneBmp = out;
                }
                requestRender();
            }
        }, "fundo").start();
    }

    /**
     * Anda o fundo do jogo: pede a imagem do jogo "want" (null = nenhum, o carrossel esta andando), troca a textura
     * so quando a anterior ja sumiu e faz aparecer ou sumir aos poucos. Devolve true enquanto falta animar.
     */
    private boolean stepBackdrop(GL10 gl, Catalog.Game want) {
        String wantKey = want != null ? want.key() : null;
        synchronized (this) {
            if (bgDoneKey != null) {
                if (bgPendBmp != null) bgPendBmp.recycle();
                bgPendKey = bgDoneKey;
                bgPendBmp = bgDoneBmp;
                bgDoneKey = null;
                bgDoneBmp = null;
            }
        }
        if (bgPendKey != null && bgAlpha <= 0.02f) {
            Tex old = texs.remove(BG);
            if (old != null) gl.glDeleteTextures(1, new int[]{old.id}, 0);
            bgTex = bgPendBmp != null ? upload(gl, BG, bgPendBmp, 1f, 1f) : null;
            if (bgPendBmp != null) bgPendBmp.recycle();
            bgTexKey = bgPendKey;
            bgPendKey = null;
            bgPendBmp = null;
            bgAlpha = 0f;
        }
        boolean shown = wantKey != null && wantKey.equals(bgTexKey);
        if (wantKey != null && !shown && !wantKey.equals(bgPendKey)) {
            synchronized (this) {
                if (!bgLoading) startBackdrop(want);
            }
        }
        float goal = shown && bgTex != null ? 1f : 0f;
        long now = SystemClock.uptimeMillis();
        float dt = bgMoving ? Math.min(100, now - bgLast) : 16;     // parado, o intervalo desde o ultimo quadro nao vale
        bgLast = now;
        if (bgAlpha < goal) bgAlpha = Math.min(goal, bgAlpha + dt / 350f);
        else if (bgAlpha > goal) bgAlpha = Math.max(goal, bgAlpha - dt / 160f);
        bgMoving = bgAlpha != goal || bgPendKey != null;
        return bgMoving;
    }

    /** Faixa horizontal do fundo do jogo, de y0 a y1, com a opacidade indo de a0 (em cima) a a1 (embaixo). */
    private void backdropBand(GL10 gl, float y0, float y1, float a0, float a1) {
        cb.clear();
        cb.put(a0).put(a0).put(a0).put(a0);
        cb.put(a0).put(a0).put(a0).put(a0);
        cb.put(a1).put(a1).put(a1).put(a1);
        cb.put(a1).put(a1).put(a1).put(a1);
        cb.position(0);
        gl.glColorPointer(4, GL10.GL_FLOAT, 0, cb);
        quad(gl, bgTex, 0, w, y0, y1, y0, y1, 1f, 1f, 0, bgTex.u, bgTex.v * y0 / h, bgTex.v * y1 / h);
    }

    private Tex upload(GL10 gl, String key, Bitmap b, float u, float v) {
        int[] ids = new int[1];
        gl.glGenTextures(1, ids, 0);
        gl.glBindTexture(GL10.GL_TEXTURE_2D, ids[0]);
        gl.glTexParameterx(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_MIN_FILTER, GL10.GL_LINEAR);
        gl.glTexParameterx(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_MAG_FILTER, GL10.GL_LINEAR);
        gl.glTexParameterx(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_WRAP_S, GL10.GL_CLAMP_TO_EDGE);
        gl.glTexParameterx(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_WRAP_T, GL10.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GL10.GL_TEXTURE_2D, 0, b, 0);
        Tex t = new Tex();
        t.id = ids[0];
        t.u = u;
        t.v = v;
        texs.put(key, t);
        return t;
    }

    private String backKey(int col) { return "b" + col + "|" + Theme.current; }

    private String coverKey(Catalog.Game g) { return "c" + g.key() + "|" + Theme.current; }

    private Tex back(GL10 gl, int col) {
        String key = backKey(col);
        Tex t = texs.get(key);
        if (t != null) return t;
        // desenha, manda para a GPU e joga fora: a imagem nao fica duplicada na memoria
        Bitmap b = Bitmap.createBitmap(BACK_W, BACK_H, Bitmap.Config.ARGB_8888);
        Theme.background(new Canvas(b), new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG), w, h, col, unit);
        t = upload(gl, key, b, w / (float) BACK_W, h / (float) BACK_H);
        b.recycle();
        return t;
    }

    private static int pot(int v) {
        int p = 1;
        while (p < v) p <<= 1;
        return p;
    }

    /** Prepara a capa na thread de fundo, com prioridade baixa: a animacao nao engasga. */
    private void startLoad(final Catalog.Game g, final String key, final boolean art) {
        final float cw = Carousel.coverW(w, h, unit), ch = Carousel.coverH(h, unit);
        final int m = Carousel.margin(unit);
        final int tw = pot((int) cw + 2 * m + 1), th = pot((int) ch + 2 * m + 1);
        loadingKey = key;
        new Thread(new Runnable() {
            public void run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                Bitmap b = null;
                float top = 0, side = 0;
                try {
                    b = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
                    top = Carousel.drawCover(getContext(), new Canvas(b), g, cw, ch, m, unit, art);
                    side = Carousel.lastSide();
                } catch (Throwable t) {
                    if (b != null) b.recycle();
                    b = null;
                }
                synchronized (CarouselGL.this) {
                    loadingKey = null;
                    if (b != null) {
                        doneKey = key;
                        doneBmp = b;
                        doneTop = top;
                        doneSide = side;
                    } else {
                        failed.add(key);    // sem memoria: fica o retangulo colorido, sem tentar sem parar
                    }
                }
                requestRender();
            }
        }, "capa").start();
    }

    /** Sobe a capa pronta e pede a proxima. Devolve true se ainda ha o que desenhar neste quadro. */
    private boolean feedCovers(GL10 gl, List<Catalog.Game> its, int tgt, boolean art) {
        Bitmap ready;
        String readyKey;
        float readyTop, readySide;
        synchronized (this) {
            ready = doneBmp;
            readyKey = doneKey;
            readyTop = doneTop;
            readySide = doneSide;
            doneBmp = null;
            doneKey = null;
        }
        boolean uploaded = false;
        if (ready != null) {
            if (!texs.containsKey(readyKey)) {
                upload(gl, readyKey, ready, (Carousel.coverW(w, h, unit) + 2 * Carousel.margin(unit)) / ready.getWidth(),
                        (Carousel.coverH(h, unit) + 2 * Carousel.margin(unit)) / ready.getHeight()).set(readyTop, readySide);
                uploaded = true;
            }
            ready.recycle();
        }
        int n = its.size();
        if (n == 0 || w == 0) return uploaded;
        synchronized (this) {
            if (loadingKey == null) {
                for (int k : WARM) {
                    if (k < -(n - 1) / 2 || k > n / 2) continue;
                    Catalog.Game g = its.get(Carousel.mod(tgt + k, n));
                    String key = coverKey(g);
                    if (!texs.containsKey(key) && !failed.contains(key)) {
                        startLoad(g, key, art);
                        return uploaded;
                    }
                }
            }
        }
        // tudo pronto em volta da selecionada: solta as capas distantes
        HashSet<String> keep = new HashSet<String>();
        for (int k = -Carousel.SIDE - 1; k <= Carousel.SIDE + 1; k++) keep.add(coverKey(its.get(Carousel.mod(tgt + k, n))));
        List<String> drop = new ArrayList<String>();
        for (String key : texs.keySet()) if (key.charAt(0) == 'c' && !keep.contains(key)) drop.add(key);
        for (String key : drop) gl.glDeleteTextures(1, new int[]{texs.remove(key).id}, 0);
        return uploaded;
    }

    /** Fundo de tela inteira: sem perspectiva. */
    private void flat(GL10 gl, Tex t) {
        quad(gl, t, 0, w, 0, h, 0, h, 1f, 1f, 0, t.u, 0, t.v);
    }

    /**
     * Quadrilatero com bordas verticais em x0 e x1 (ytop e ybot de cada uma). s0 e s1 = escala de cada borda:
     * vem do "w" de cada vertice, e a GPU usa isso para corrigir a perspectiva da imagem.
     */
    private void quad(GL10 gl, Tex t, float x0, float x1, float y0t, float y0b, float y1t, float y1b,
                      float s0, float s1, float u0, float u1, float v0, float v1) {
        float w0 = 1f / s0, w1 = 1f / s1;
        vb.clear();
        vb.put(x0 * w0).put(y0t * w0).put(0f).put(w0);
        vb.put(x1 * w1).put(y1t * w1).put(0f).put(w1);
        vb.put(x0 * w0).put(y0b * w0).put(0f).put(w0);
        vb.put(x1 * w1).put(y1b * w1).put(0f).put(w1);
        vb.position(0);
        gl.glVertexPointer(4, GL10.GL_FLOAT, 0, vb);
        if (t != null) {
            tb.clear();
            tb.put(u0).put(v0).put(u1).put(v0).put(u0).put(v1).put(u1).put(v1);
            tb.position(0);
            gl.glBindTexture(GL10.GL_TEXTURE_2D, t.id);
            gl.glTexCoordPointer(2, GL10.GL_FLOAT, 0, tb);
        }
        gl.glDrawArrays(GL10.GL_TRIANGLE_STRIP, 0, 4);
    }

    /** Uma capa na posicao "off" em relacao ao centro (0 = selecionada), com o reflexo. */
    private void drawCover(GL10 gl, Catalog.Game g, float off) {
        float a = Math.min(1f, Math.abs(off));
        float scale = Carousel.coverScale(a);   // tamanho no meio da capa
        float k = 0.05f * a;                    // leve perspectiva: borda de dentro cresce, a de fora encolhe
        float thin = 1f - 0.03f * a;            // virada, a capa fica um pouco mais estreita
        float dim = 1f - 0.12f * a;
        float s0 = off > 0 ? 1f + k : 1f - k, s1 = off > 0 ? 1f - k : 1f + k;
        float m = Carousel.margin(unit), cw = Carousel.coverW(w, h, unit), ch = Carousel.coverH(h, unit);
        float x = w / 2f + Carousel.coverX(off, w, h, unit), cy = Carousel.coverY(h, unit) + Carousel.coverDrop(a, h, unit);
        float hw = (cw + 2 * m) / 2f * scale * thin, hh = (ch + 2 * m) / 2f * scale;
        float x0 = x - hw, x1 = x + hw;
        if (x1 < 0 || x0 > w) return;
        Tex t = texs.get(coverKey(g));
        float y0t = cy - hh * s0, y0b = cy + hh * s0, y1t = cy - hh * s1, y1b = cy + hh * s1;
        if (t == null) {
            // ainda carregando: retangulo na cor do console
            int c = g.sys.color;
            gl.glDisable(GL10.GL_TEXTURE_2D);
            gl.glDisableClientState(GL10.GL_TEXTURE_COORD_ARRAY);
            float cr = ((c >> 16) & 255) / 255f * dim * 0.7f, cg = ((c >> 8) & 255) / 255f * dim * 0.7f,
                    cbl = (c & 255) / 255f * dim * 0.7f;
            gl.glColor4f(cr, cg, cbl, 1f);
            float px = m * scale * thin, py = m * scale;
            quad(gl, null, x0 + px, x1 - px, y0t + py * s0, y0b - py * s0, y1t + py * s1, y1b - py * s1, s0, s1, 0, 0, 0, 0);
            gl.glEnable(GL10.GL_TEXTURE_2D);
            gl.glEnableClientState(GL10.GL_TEXTURE_COORD_ARRAY);
            return;
        }
        // reflexo: a parte de baixo da capa, de ponta-cabeca, sumindo
        float texH = t.v / (ch + 2 * m), texW = t.u / (cw + 2 * m);
        float mu = (m + t.side) * texW, mv = m * texH, vBottom = t.v - mv;
        float rh = ch * 0.24f, gapY = 1.5f * unit;
        float rx0 = x0 + (m + t.side) * scale * thin, rx1 = x1 - (m + t.side) * scale * thin;
        float yb0 = y0b - m * scale * s0 + gapY * s0, yb1 = y1b - m * scale * s1 + gapY * s1;
        float ra = 0.38f * dim;
        cb.clear();
        cb.put(ra).put(ra).put(ra).put(ra);
        cb.put(ra).put(ra).put(ra).put(ra);
        cb.put(0f).put(0f).put(0f).put(0f);
        cb.put(0f).put(0f).put(0f).put(0f);
        cb.position(0);
        gl.glEnableClientState(GL10.GL_COLOR_ARRAY);
        gl.glColorPointer(4, GL10.GL_FLOAT, 0, cb);
        quad(gl, t, rx0, rx1, yb0, yb0 + rh * scale * s0, yb1, yb1 + rh * scale * s1, s0, s1,
                mu, t.u - mu, vBottom, vBottom - rh * texH);
        gl.glDisableClientState(GL10.GL_COLOR_ARRAY);
        if (a < 0.98f) {
            // borda da selecionada: aparece conforme a capa chega ao centro
            int c = Theme.accent(g.sys.color);
            float on = 1f - a, e = 2f * unit;
            float px = (m - e + t.side) * scale * thin, py = (m - e) * scale, down = t.top * scale;
            gl.glDisable(GL10.GL_TEXTURE_2D);
            gl.glDisableClientState(GL10.GL_TEXTURE_COORD_ARRAY);
            gl.glColor4f(((c >> 16) & 255) / 255f * on, ((c >> 8) & 255) / 255f * on, (c & 255) / 255f * on, on);
            quad(gl, null, x0 + px, x1 - px, y0t + (py + down) * s0, y0b - py * s0, y1t + (py + down) * s1, y1b - py * s1,
                    s0, s1, 0, 0, 0, 0);
            gl.glEnable(GL10.GL_TEXTURE_2D);
            gl.glEnableClientState(GL10.GL_TEXTURE_COORD_ARRAY);
        }
        gl.glColor4f(dim, dim, dim, 1f);
        quad(gl, t, x0, x1, y0t, y0b, y1t, y1b, s0, s1, 0, t.u, 0, t.v);
    }

    public void onDrawFrame(GL10 gl) {
        List<Catalog.Game> its;
        float p;
        int tgt;
        boolean anim, art, bg;
        synchronized (this) {
            bg = showBg;
            if (wipe) {
                dropAll(gl);
                wipe = false;
            }
            if (animating) {
                float t = (SystemClock.uptimeMillis() - animStart) / (float) animMs;
                if (t >= 1f) { t = 1f; animating = false; }
                float e = 1f - (1f - t) * (1f - t) * (1f - t);
                pos = animFrom + (target - animFrom) * e;
            } else {
                pos = target;
            }
            its = items;
            p = pos;
            tgt = target;
            anim = animating;
            art = showArt;
        }
        // medicao: quadros e tempo de cada animacao (logcat -s fliperama)
        if (anim && !measuring) { measuring = true; mFrames = 0; mStart = SystemClock.uptimeMillis(); }
        if (measuring) {
            mFrames++;
            if (!anim) {
                measuring = false;
                android.util.Log.d("fliperama", "animacao: " + mFrames + " quadros em "
                        + (SystemClock.uptimeMillis() - mStart) + " ms");
            }
        }
        boolean more = w > 0 && feedCovers(gl, its, tgt, art);
        gl.glClear(GL10.GL_COLOR_BUFFER_BIT);
        int n = its.size();
        if (w > 0 && stepBackdrop(gl, bg && n > 0 && !anim ? its.get(Carousel.mod(tgt, n)) : null)) more = true;
        if (w > 0) {
            int i0 = (int) Math.floor(p);
            float fr = p - i0;
            gl.glDisable(GL10.GL_BLEND);
            gl.glColor4f(1f, 1f, 1f, 1f);
            int c0 = Theme.backColor(n > 0 ? its.get(Carousel.mod(i0, n)).sys.color : EMPTY_COLOR);
            flat(gl, back(gl, c0));
            gl.glEnable(GL10.GL_BLEND);
            if (n > 0 && fr > 0.01f) {
                int c1 = Theme.backColor(its.get(Carousel.mod(i0 + 1, n)).sys.color);
                if (c1 != c0) {
                    gl.glColor4f(fr, fr, fr, fr);
                    flat(gl, back(gl, c1));
                }
            }
            if (bgTex != null && bgAlpha > 0.01f) {
                // em cima cobre o tema; mais embaixo vai sumindo, para o chao do tema continuar sob os reflexos
                float mid = h * 0.52f;
                gl.glEnableClientState(GL10.GL_COLOR_ARRAY);
                backdropBand(gl, 0, mid, bgAlpha, bgAlpha);
                backdropBand(gl, mid, h, bgAlpha, bgAlpha * 0.22f);
                gl.glDisableClientState(GL10.GL_COLOR_ARRAY);
            }
            if (n > 0) {
                int base = Math.round(p);
                for (int s = Carousel.SIDE; s >= 0; s--) {     // de fora para dentro: a selecionada por cima
                    for (int sign = -1; sign <= 1; sign += 2) {
                        if (s == 0 && sign > 0) continue;
                        int k = s * sign;
                        if (k < -(n - 1) / 2 || k > n / 2) continue;   // poucos jogos: sem capa repetida na tela
                        int i = base + k;
                        drawCover(gl, its.get(Carousel.mod(i, n)), i - p);
                    }
                }
            }
        }
        if (!firstFrameDone) {
            firstFrameDone = true;
            if (onFirstFrame != null) post(onFirstFrame);
        }
        if (anim || more) requestRender();
    }
}
