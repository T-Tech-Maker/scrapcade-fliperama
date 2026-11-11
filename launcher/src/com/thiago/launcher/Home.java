package com.thiago.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * Launcher "fliperama": uma tela so, com as capas de todos os jogos num carrossel.
 * O console vira filtro (LB/RB); apps e ajustes ficam nas opcoes (SELECT).
 * Tudo navegavel pelo controle (joyd) e pelo toque, e medido em proporcao da tela (Carousel.unit).
 */
public class Home extends Activity implements CarouselApi.Listener {
    private static final int SCR_MAIN = 0, SCR_APPS = 1;
    private int screen = SCR_MAIN;
    private float u;
    private PackageManager pm;
    private SharedPreferences prefs;
    private final Handler h = new Handler();

    private View mainScreen, appsScreen;
    private CarouselApi carousel;
    private boolean usingGl;
    private static final float TOP_H = 27;   // altura da barra de cima, em unidades
    private TextView hEmu, hFilter, hIp, hClock, hGame, hSub, hEmpty;
    private LogoView hLogo;
    private LinearLayout filterBox;
    private Envio envio;
    private ArquivosTela files;
    private boolean organizing, paused;

    private LinearLayout appsRoot;
    private TextView aTitle, aClock;
    private GridView appGrid;
    private final List<ResolveInfo> apps = new ArrayList<ResolveInfo>();

    private LinearLayout optPanel;
    private final List<TextView> optRows = new ArrayList<TextView>();
    private int optSel = 0;
    private boolean optOpen = false;

    private List<Catalog.Sys> systems = new ArrayList<Catalog.Sys>();   // os que tem jogo: sao os filtros
    private List<Catalog.Game> games = new ArrayList<Catalog.Game>();   // todos os jogos
    private List<Catalog.Game> view = new ArrayList<Catalog.Game>();    // os que passam no filtro
    private String filter = "";                                         // id do sistema; vazio = todos
    private int selApp = 0;

    private int dp(float v) { return (int) (v * u + 0.5f); }

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setBackgroundDrawable(null);     // as telas pintam o fundo inteiro: sem pintura dupla
        getWindow().setFormat(PixelFormat.RGBA_8888); // texto translucido por cima da camada da GPU
        u = Carousel.unit(this);
        pm = getPackageManager();
        prefs = getSharedPreferences("fliperama", MODE_PRIVATE);
        Theme.ctx = getApplicationContext();
        if (!prefs.getBoolean("aurora_v1", false))       // o visual novo vale para quem ja tinha outro tema salvo
            prefs.edit().putInt("theme", Theme.AURORA).putBoolean("aurora_v1", true).commit();
        Theme.current = prefs.getInt("theme", Theme.AURORA);
        filter = prefs.getString("filter", "");
        envio = new Envio(new Envio.Host() {
            public void changed() {
                h.removeCallbacks(reloader);
                h.postDelayed(reloader, 500);
                carousel.clearCache();    // pode ter sido uma capa nova
            }
        });
        envio.start();

        // Se a ultima abertura com GPU nao chegou ao 1o quadro, ela travou: usa a CPU, sem ficar em loop.
        if (prefs.getBoolean("gl_starting", false)) {
            prefs.edit().putBoolean("gl_starting", false).putBoolean("gpu", false).commit();
        }
        usingGl = prefs.getBoolean("gpu", true);
        if (usingGl) {
            prefs.edit().putBoolean("gl_starting", true).commit();
            carousel = new CarouselGL(this, new Runnable() {
                public void run() { prefs.edit().putBoolean("gl_starting", false).commit(); }
            });
        } else {
            carousel = new Carousel(this);
        }
        carousel.setListener(this);
        carousel.setShowCovers(prefs.getBoolean("covers", true));
        carousel.setShowBackdrop(prefs.getBoolean("backdrop", true));

        final FrameLayout root = new FrameLayout(this);
        mainScreen = buildMain();
        appsScreen = buildApps();
        files = new ArquivosTela(this, new Runnable() {
            public void run() {
                carousel.clearCache();      // capas e jogos podem ter mudado
                reload();
            }
        });
        int fp = FrameLayout.LayoutParams.FILL_PARENT;
        root.addView(carousel.view(), new FrameLayout.LayoutParams(fp, fp));   // fica sempre atras das telas
        root.addView(mainScreen, new FrameLayout.LayoutParams(fp, fp));
        root.addView(appsScreen, new FrameLayout.LayoutParams(fp, fp));
        root.addView(files.view(), new FrameLayout.LayoutParams(fp, fp));
        root.addView(buildOptions(), new FrameLayout.LayoutParams(fp, fp));
        root.addView(buildInfo(), new FrameLayout.LayoutParams(fp, fp));
        splash(root);
        setContentView(root);
        carousel.setAnimMs(prefs.getBoolean("fast", false) ? 130 : 200);
        themeHeader();

        reload();
        show(SCR_MAIN);
        watchStorage();
    }

    /**
     * Abertura: a logo do ScrapCade (a mesma imagem do fim da animacao de boot) cobre a tela enquanto o menu
     * carrega, e some aos poucos. So ao abrir o app, nao ao voltar de um jogo.
     */
    private void splash(final FrameLayout root) {
        final Bitmap logo;
        try {
            java.io.InputStream in = getAssets().open("logo.jpg");
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            logo = BitmapFactory.decodeStream(in, null, o);
            in.close();
        } catch (Throwable t) {
            return;     // sem a imagem, o menu abre direto
        }
        if (logo == null) return;
        final ImageView v = new ImageView(this);
        v.setBackgroundColor(0xFF0B0520);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setImageBitmap(logo);
        root.addView(v, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.FILL_PARENT, FrameLayout.LayoutParams.FILL_PARENT));
        h.postDelayed(new Runnable() {
            public void run() {
                AlphaAnimation out = new AlphaAnimation(1f, 0f);
                out.setDuration(450);
                out.setAnimationListener(new android.view.animation.Animation.AnimationListener() {
                    public void onAnimationStart(android.view.animation.Animation a) { }
                    public void onAnimationRepeat(android.view.animation.Animation a) { }
                    public void onAnimationEnd(android.view.animation.Animation a) {
                        v.setVisibility(View.GONE);
                        h.post(new Runnable() {       // tirar a View dentro do aviso da animacao derruba o desenho
                            public void run() {
                                root.removeView(v);
                                v.setImageDrawable(null);
                                logo.recycle();
                            }
                        });
                    }
                });
                v.startAnimation(out);
            }
        }, 1300);
    }

    /**
     * No boot o fliperama abre ANTES do cartao SD ser montado (ROMs "sumiam").
     * Escuta cartao montado/desmontado e apps instalados/removidos e recarrega a lista.
     */
    private final android.content.BroadcastReceiver changes = new android.content.BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
            h.removeCallbacks(reloader);
            h.postDelayed(reloader, 800);   // agrupa varios avisos seguidos
        }
    };
    private final Runnable reloader = new Runnable() { public void run() { reload(); } };

    private void watchStorage() {
        Catalog.syncClock();
        android.content.IntentFilter media = new android.content.IntentFilter();
        media.addAction(Intent.ACTION_MEDIA_MOUNTED);
        media.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        media.addAction(Intent.ACTION_MEDIA_SHARED);
        media.addAction(Intent.ACTION_MEDIA_SCANNER_FINISHED);
        media.addDataScheme("file");
        registerReceiver(changes, media);
        android.content.IntentFilter pkgs = new android.content.IntentFilter();
        pkgs.addAction(Intent.ACTION_PACKAGE_ADDED);
        pkgs.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkgs.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pkgs.addDataScheme("package");
        registerReceiver(changes, pkgs);
        registerReceiver(changes, new android.content.IntentFilter(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE));
        // garantia extra: confere de novo alguns segundos depois de abrir
        h.postDelayed(reloader, 8000);
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(changes); } catch (Exception ignored) { }
        envio.stop();
        super.onDestroy();
    }

    // ------------------------------------------------------------ telas

    /** Camada de texto por cima das capas: so redesenha quando o texto muda. */
    private View buildMain() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);

        // Barra de cima, escura como a do nome do jogo: logo do console | filtro com LB e RB | hora, data e IP
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(10), 0, dp(10), 0);
        head.setBackgroundColor(0xB8303338);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.HORIZONTAL);
        left.setGravity(Gravity.CENTER_VERTICAL);
        hLogo = new LogoView(this);
        left.addView(hLogo, new LinearLayout.LayoutParams(dp(92), dp(17)));
        View bar = new View(this);
        bar.setBackgroundColor(0x55FFFFFF);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(Math.max(1, dp(0.7f)), dp(14));
        bp.leftMargin = dp(8);
        bp.rightMargin = dp(7);
        left.addView(bar, bp);
        hEmu = text(8, 0xCCFFFFFF, false);
        hEmu.setSingleLine(true);
        hEmu.setEllipsize(TextUtils.TruncateAt.END);
        left.addView(hEmu, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.FILL_PARENT, 1f));
        filterBox = new LinearLayout(this);
        filterBox.setOrientation(LinearLayout.HORIZONTAL);
        filterBox.setGravity(Gravity.CENTER_VERTICAL);
        filterBox.addView(pill("LB"));
        hFilter = text(10.5f, Color.WHITE, true);
        hFilter.setSingleLine(true);
        hFilter.setPadding(dp(7), 0, dp(7), 0);
        filterBox.addView(hFilter);
        filterBox.addView(pill("RB"));
        head.addView(filterBox);
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.CENTER_VERTICAL);
        hClock = text(12.5f, Color.WHITE, true);
        hClock.setIncludeFontPadding(false);
        hClock.setGravity(Gravity.RIGHT);
        right.addView(hClock, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.FILL_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        hIp = text(7, 0xBBFFFFFF, false);
        hIp.setIncludeFontPadding(false);
        hIp.setGravity(Gravity.RIGHT);
        right.addView(hIp, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.FILL_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        head.addView(right, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        l.addView(head, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.FILL_PARENT, dp(TOP_H)));

        FrameLayout middle = new FrameLayout(this);
        hEmpty = text(14, Color.WHITE, false);
        hEmpty.setGravity(Gravity.CENTER);
        hEmpty.setPadding(dp(30), 0, dp(30), 0);
        middle.addView(hEmpty, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.FILL_PARENT,
                FrameLayout.LayoutParams.FILL_PARENT));
        l.addView(middle, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.FILL_PARENT, 0, 1f));

        LinearLayout titleBar = new LinearLayout(this);
        titleBar.setOrientation(LinearLayout.VERTICAL);
        titleBar.setGravity(Gravity.CENTER);
        titleBar.setBackgroundColor(0xB8303338);
        hGame = text(13.5f, Color.WHITE, false);
        hGame.setGravity(Gravity.CENTER_HORIZONTAL);
        hGame.setSingleLine(true);
        hGame.setEllipsize(TextUtils.TruncateAt.END);
        hGame.setPadding(dp(16), 0, dp(16), dp(1));
        hGame.setIncludeFontPadding(false);     // sem isso o contador "5 de 12" invadia a barra de botoes
        titleBar.addView(hGame, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.FILL_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        hSub = text(8.5f, 0xDDFFFFFF, false);
        hSub.setGravity(Gravity.CENTER_HORIZONTAL);
        hSub.setSingleLine(true);
        hSub.setIncludeFontPadding(false);
        titleBar.addView(hSub, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.FILL_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        l.addView(titleBar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.FILL_PARENT, dp(Carousel.TITLE_H)));

        HintBar hb = new HintBar(this);
        hb.set("LR", "escolher", "A", "jogar", "X", "favorito", "Y", "detalhes", "LB RB", "filtrar", "SELECT", "sistema");
        l.addView(hb);
        return l;
    }

    private void themeHeader() {
        // a barra de cima e escura em todos os temas: o texto dela fica sempre branco
        int tc = Theme.text(), shadow = Theme.light() ? 0 : 0xCC000000;
        hEmpty.setShadowLayer(shadow != 0 ? 2 * u : 0, 0, u, shadow);
        hEmpty.setTextColor(tc);
    }

    private void updateCaption() {
        Catalog.Game g = carousel.current();
        hEmpty.setVisibility(games.isEmpty() ? View.VISIBLE : View.GONE);
        boolean usb = android.os.Environment.MEDIA_SHARED.equals(android.os.Environment.getExternalStorageState());
        hEmpty.setText(usb
                ? "Conectado ao computador.\n\nCopie os jogos para a pasta \"Jogos novos\" do cartão.\n"
                        + "No fim, ejete a unidade no computador e tire o cabo:\nos jogos vão sozinhos para o console certo."
                : "Nenhum jogo ainda.\n\nLigue o cabo USB no computador e copie os jogos para a pasta \"Jogos novos\",\n"
                        + "ou abra no navegador do computador http://" + ip() + ":" + Envio.PORT);
        hLogo.set(g != null ? g.sys : null);
        hEmu.setText(g != null ? g.emuLabel() : "");     // o console ja esta no logo ao lado
        hGame.setText(g != null ? (isFav(g) ? "★  " : "") + g.title : "");
        String meta = g != null ? Meta.line(g) : "";
        hSub.setText(g != null ? (meta.length() > 0 ? meta + "     " : "") + (carousel.index() + 1) + " de " + view.size() : "");
        hFilter.setText(filterName(filter) + "  (" + view.size() + ")");
        filterBox.setVisibility(filters.size() < 2 ? View.INVISIBLE : View.VISIBLE);
    }

    // ------------------------------------------------------------ favoritos, mais jogados e detalhes

    private static final String F_ALL = "", F_FAV = "*fav", F_TOP = "*top";
    private List<String> filters = new ArrayList<String>();

    private boolean isFav(Catalog.Game g) { return prefs.getBoolean("fav|" + g.key(), false); }

    private int plays(Catalog.Game g) { return prefs.getInt("plays|" + g.key(), 0); }

    /** Filtros que tem jogo agora: Todos, Favoritos, Mais jogados e cada console. */
    private List<String> buildFilters() {
        List<String> l = new ArrayList<String>();
        l.add(F_ALL);
        boolean fav = false, top = false;
        for (Catalog.Game g : games) {
            if (!fav && isFav(g)) fav = true;
            if (!top && plays(g) > 0) top = true;
        }
        if (fav) l.add(F_FAV);
        if (top) l.add(F_TOP);
        for (Catalog.Sys s : systems) l.add(s.id);
        return l;
    }

    private String filterName(String id) {
        if (F_FAV.equals(id)) return "Favoritos";
        if (F_TOP.equals(id)) return "Mais jogados";
        for (Catalog.Sys s : systems) if (s.id.equals(id)) return s.name;
        return "Todos";
    }

    /** X: marca ou desmarca o jogo selecionado como favorito. */
    private void toggleFav() {
        Catalog.Game g = carousel.current();
        if (g == null) return;
        boolean on = !isFav(g);
        if (on) prefs.edit().putBoolean("fav|" + g.key(), true).commit();
        else prefs.edit().remove("fav|" + g.key()).commit();
        int at = carousel.index();
        applyFilter();
        if (!on && F_FAV.equals(filter) && !view.isEmpty()) {     // saiu da lista de favoritos: fica no vizinho
            carousel.setItems(view, Math.min(at, view.size() - 1));
            updateCaption();
        }
    }

    /** Y: painel com tudo que se sabe do jogo selecionado. */
    private void showDetails() {
        Catalog.Game g = carousel.current();
        if (g == null) return;
        Meta.Info m = Meta.get(g.key());
        StringBuilder s = new StringBuilder();
        s.append("Console: ").append(g.sys.name).append("     Emulador: ").append(g.emuLabel()).append('\n');
        if (m != null && (m.year.length() > 0 || m.dev.length() > 0 || m.genre.length() > 0)) {
            if (m.year.length() > 0) s.append("Ano: ").append(m.year).append("     ");
            if (m.dev.length() > 0) s.append("Empresa: ").append(m.dev).append("     ");
            if (m.genre.length() > 0) s.append("Gênero: ").append(Meta.genrePt(m.genre));
            s.append('\n');
        } else {
            s.append("Ano, empresa e gênero: sem dados para este jogo\n");
        }
        if (m != null && m.name.length() > 0 && !m.name.equals(g.title)) s.append("Versão: ").append(m.name).append('\n');
        s.append("\nArquivo: ").append(g.rom.getName());
        if (g.rom.isFile()) s.append("  (").append(Catalog.size(g.rom.length())).append(")");
        s.append("\nPasta: ").append(Arquivos.rel(g.rom.getParentFile())).append("\n\n");
        int n = plays(g);
        s.append(n == 0 ? "Ainda não jogado" : (n == 1 ? "Jogado 1 vez" : "Jogado " + n + " vezes"));
        long when = prefs.getLong("when|" + g.key(), 0);
        if (when > 0) s.append("     Última vez: ").append(new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(new Date(when)));
        s.append("\nFavorito: ").append(isFav(g) ? "sim" : "não").append("  (o botão X marca e desmarca)");
        showPanel(g.title, s.toString());
    }

    private View buildApps() {
        appsRoot = new LinearLayout(this);
        appsRoot.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(18), dp(8), dp(18), dp(6));
        aTitle = text(22, Color.WHITE, true);
        aTitle.setTypeface(Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD_ITALIC));
        aTitle.setText("APLICATIVOS");
        head.addView(aTitle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        aClock = text(17, Color.WHITE, true);
        head.addView(aClock);
        appsRoot.addView(head);

        appGrid = new GridView(this);
        appGrid.setNumColumns(6);
        appGrid.setVerticalSpacing(dp(6));
        appGrid.setPadding(dp(10), dp(6), dp(10), dp(6));
        appGrid.setCacheColorHint(0);
        appGrid.setSelector(new ColorDrawable(0));
        appGrid.setFocusable(false);
        appGrid.setAdapter(new AppsAdapter());
        appGrid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> a, View v, int position, long id) {
                selectApp(position);
                openApp(position);
            }
        });
        appsRoot.addView(appGrid, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.FILL_PARENT, 0, 1f));
        HintBar hb = new HintBar(this);
        hb.set("LR", "escolher", "A", "abrir", "B", "voltar");
        appsRoot.addView(hb);
        return appsRoot;
    }

    private static final int OPT_THEME = 0, OPT_ANIM = 1, OPT_COVERS = 2, OPT_BG = 3, OPT_APPS = 4, OPT_FILES = 5,
            OPT_GPU = 6, OPT_DRAW = 7, OPT_HELP = 8, OPT_INFO = 9, OPT_ANDROID = 10, OPT_REBOOT = 11, OPT_COUNT = 12;

    /** Painel de opcoes (SELECT): tema, animacao, capas, aplicativos, configuracoes do Android. */
    private View buildOptions() {
        FrameLayout dim = new FrameLayout(this);
        dim.setBackgroundColor(0x99000000);
        dim.setVisibility(View.GONE);
        optPanel = whitePanel("Sistema");
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setPadding(dp(12), dp(6), dp(12), 0);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(heading("Opções"));
        for (int i = 0; i < OPT_COUNT; i++) {
            TextView row = text(9.5f, Color.WHITE, false);
            row.setPadding(dp(8), dp(1.6f), dp(8), dp(1.6f));
            final int idx = i;
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { optSel = idx; optAction(1); }
            });
            optRows.add(row);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.FILL_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            rp.bottomMargin = dp(1.6f);
            left.addView(row, rp);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.15f);
        lp.rightMargin = dp(14);
        body.addView(left, lp);
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.addView(heading("Endereço IP"));
        optIp = text(10, 0xFF5E6269, false);
        optIp.setPadding(0, 0, 0, dp(6));
        right.addView(optIp);
        right.addView(heading("Enviar jogos do PC"));
        optWeb = text(9, 0xFF5E6269, false);
        optWeb.setPadding(0, 0, 0, dp(6));
        right.addView(optWeb);
        right.addView(heading("Uso do sistema"));
        bars = new Bars(this);
        right.addView(bars, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.FILL_PARENT, dp(78)));
        body.addView(right, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        optPanel.addView(body);
        HintBar hb = new HintBar(this);
        hb.setOnPanel();
        hb.set("UD", "escolher", "LR", "mudar", "A", "abrir", "B", "fechar");
        optPanel.addView(hb, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.FILL_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        dim.addView(optPanel, new FrameLayout.LayoutParams(dp(420), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        return dim;
    }

    /** Painel branco com faixa cinza no alto. */
    private LinearLayout whitePanel(String title) {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setBackgroundColor(0xFFFFFFFF);
        TextView t = text(13, Color.WHITE, false);
        t.setBackgroundColor(0xFF4A4D52);
        t.setPadding(dp(12), dp(4), dp(12), dp(4));
        t.setText(title);
        panelTitle = t;
        p.addView(t, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.FILL_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return p;
    }

    private TextView heading(String s) {
        TextView t = text(11, 0xFF2B2E33, false);
        t.setText(s);
        t.setPadding(0, 0, 0, dp(2));
        return t;
    }

    private static int[] readCpu() {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/stat"));
            String[] t = r.readLine().trim().split("\\s+");
            r.close();
            int total = 0;
            for (int i = 1; i < t.length && i < 9; i++) total += Integer.parseInt(t[i]);
            return new int[] {total, Integer.parseInt(t[4])};
        } catch (Exception e) {
            return new int[] {0, 0};
        }
    }

    private int[] lastCpu = new int[] {0, 0};

    /** Atualiza os graficos enquanto o painel esta aberto. */
    private final Runnable barsTick = new Runnable() {
        public void run() {
            int[] now = readCpu();
            int dt = now[0] - lastCpu[0], di = now[1] - lastCpu[1];
            int cpu = dt > 0 ? 100 - di * 100 / dt : 0;
            lastCpu = now;
            long total = memKb("MemTotal"), free = memKb("MemFree") + memKb("Cached");
            int mem = total > 0 ? (int) ((total - free) * 100 / total) : 0;
            int sd = 0;
            try {
                android.os.StatFs fs = new android.os.StatFs(Catalog.romsDir().getPath());
                sd = 100 - (int) ((long) fs.getAvailableBlocks() * 100 / fs.getBlockCount());
            } catch (Exception ignored) { }
            bars.set(cpu, mem, sd);
            if (optOpen) h.postDelayed(barsTick, 1000);
        }
    };

    private void refreshOptions() {
        String[] labels = new String[OPT_COUNT];
        labels[OPT_THEME] = "Tema:   ‹ " + Theme.NAMES[Theme.current] + " ›";
        labels[OPT_ANIM] = "Animações:   ‹ " + (prefs.getBoolean("fast", false) ? "Rápidas" : "Normais") + " ›";
        labels[OPT_COVERS] = "Capas dos jogos:   ‹ " + (prefs.getBoolean("covers", true) ? "Mostrar" : "Ocultar") + " ›";
        labels[OPT_BG] = "Fundo do jogo:   ‹ " + (prefs.getBoolean("backdrop", true) ? "Mostrar" : "Ocultar") + " ›";
        labels[OPT_APPS] = "Aplicativos";
        labels[OPT_FILES] = "Arquivos (copiar, mover, apagar)";
        labels[OPT_GPU] = "Clock da GPU:   ‹ " + gpuMhz() + " MHz ›";
        labels[OPT_DRAW] = "Desenho do menu:   ‹ " + (usingGl ? "GPU" : "CPU") + " ›";
        labels[OPT_HELP] = "Como jogar (botões)";
        labels[OPT_INFO] = "Informações do sistema";
        labels[OPT_ANDROID] = "Configurações do Android";
        labels[OPT_REBOOT] = "Reiniciar o aparelho";
        for (int i = 0; i < optRows.size(); i++) {
            TextView r = optRows.get(i);
            r.setText(labels[i]);
            r.setBackgroundColor(i == optSel ? 0xFF2CCF9A : 0xFF4A4D52);
        }
        optIp.setText(ip());
        optWeb.setText("http://" + ip() + ":" + Envio.PORT);
    }

    private void setOptions(boolean open) {
        optOpen = open;
        View dim = (View) optPanel.getParent();
        dim.setVisibility(open ? View.VISIBLE : View.GONE);
        h.removeCallbacks(barsTick);
        if (open) {
            optSel = 0;
            refreshOptions();
            lastCpu = readCpu();
            h.postDelayed(barsTick, 300);
            AlphaAnimation fade = new AlphaAnimation(0f, 1f);
            fade.setDuration(120);
            dim.startAnimation(fade);
        }
    }

    /** dir: -1/+1 = mudar valor (esq/dir), 1 tambem = confirmar. */
    private void optAction(int dir) {
        switch (optSel) {
            case OPT_THEME:
                Theme.current = (Theme.current + dir + Theme.NAMES.length) % Theme.NAMES.length;
                prefs.edit().putInt("theme", Theme.current).commit();
                applyTheme();
                break;
            case OPT_ANIM: {
                boolean fast = !prefs.getBoolean("fast", false);
                prefs.edit().putBoolean("fast", fast).commit();
                carousel.setAnimMs(fast ? 130 : 200);
                break;
            }
            case OPT_COVERS: {
                boolean on = !prefs.getBoolean("covers", true);
                prefs.edit().putBoolean("covers", on).commit();
                carousel.setShowCovers(on);
                break;
            }
            case OPT_BG: {
                boolean on = !prefs.getBoolean("backdrop", true);
                prefs.edit().putBoolean("backdrop", on).commit();
                carousel.setShowBackdrop(on);
                if (on) reload();        // busca as imagens que faltam
                break;
            }
            case OPT_APPS:
                if (dir == 1) {
                    setOptions(false);
                    show(SCR_APPS);
                }
                return;
            case OPT_FILES:
                if (dir == 1) {
                    setOptions(false);
                    files.open();
                }
                return;
            case OPT_GPU: {
                // 133 / 167 / 222 MHz (quem aplica e o joyd, root; 167 e o valor adotado; 222 passa do nominal)
                int[] divs = {5, 4, 3};
                int cur = prefs.getInt("gpu_div", 4), idx = 1;
                for (int i = 0; i < divs.length; i++) if (divs[i] == cur) idx = i;
                idx = (idx + dir + divs.length) % divs.length;
                prefs.edit().putInt("gpu_div", divs[idx]).commit();
                Catalog.sendCmd("gpu " + divs[idx]);
                break;
            }
            case OPT_DRAW: {
                // troca GPU/CPU: recria a tela
                prefs.edit().putBoolean("gpu", !usingGl).putBoolean("gl_starting", false).commit();
                Intent again = getIntent();
                finish();
                startActivity(again);
                return;
            }
            case OPT_HELP:
                if (dir == 1) {
                    setOptions(false);
                    showPanel("Como jogar", helpText());
                }
                return;
            case OPT_INFO:
                if (dir == 1) {
                    setOptions(false);
                    showPanel("Informações do sistema", infoText());
                }
                return;
            case OPT_REBOOT:
                if (dir == 1) {
                    toast("Reiniciando...");
                    Catalog.sendCmd("reboot");
                }
                return;
            case OPT_ANDROID:
                if (dir == 1) {
                    setOptions(false);
                    try {
                        startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Exception e) { toast("Configurações indisponíveis"); }
                }
                return;
            default:
                if (dir == 1) setOptions(false);
                return;
        }
        refreshOptions();
    }

    private int gpuMhz() { return 667 / prefs.getInt("gpu_div", 4); }

    // ------------------------------------------------------------ paineis de texto (informacoes, como jogar)

    private View infoDim;
    private TextView infoText, infoTitle, panelTitle, optIp, optWeb;
    private Bars bars;
    private boolean infoOpen;

    private View buildInfo() {
        FrameLayout dim = new FrameLayout(this);
        dim.setBackgroundColor(0x99000000);
        dim.setVisibility(View.GONE);
        LinearLayout p = whitePanel("");
        infoTitle = panelTitle;
        infoText = text(10, 0xFF3A3D42, false);
        infoText.setLineSpacing(dp(1), 1f);
        infoText.setPadding(dp(12), dp(6), dp(12), dp(2));
        p.addView(infoText, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.FILL_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        HintBar hb = new HintBar(this);
        hb.setOnPanel();
        hb.set("B", "fechar");
        p.addView(hb, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.FILL_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        dim.addView(p, new FrameLayout.LayoutParams(dp(420), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        dim.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { closePanel(); }
        });
        infoDim = dim;
        return dim;
    }

    private static long memKb(String key) {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/meminfo"));
            String l;
            while ((l = r.readLine()) != null) {
                if (l.startsWith(key)) {
                    r.close();
                    return Long.parseLong(l.replaceAll("[^0-9]", ""));
                }
            }
            r.close();
        } catch (Exception ignored) { }
        return 0;
    }

    private void showPanel(String title, String s) {
        infoOpen = true;
        infoTitle.setText(title);
        infoText.setText(s);
        infoDim.setVisibility(View.VISIBLE);
    }

    private void closePanel() {
        infoOpen = false;
        infoDim.setVisibility(View.GONE);
    }

    private String helpText() {
        return "No menu:  direcional escolhe (cima e baixo pulam 5)  •  LB e RB filtram  •  X favorito  •  Y detalhes\n"
                + "Em qualquer jogo:  SELECT + START volta para este menu\n"
                + "Arcade e Neo Geo:  SELECT põe ficha, START começa\n\n"
                + "COLOCAR JOGOS\n"
                + "Cabo USB no PC ou cartão no leitor: copie para a pasta \"Jogos novos\".\n"
                + "Pen drive: conecte com o fliperama aberto, e os jogos são copiados sozinhos.\n"
                + "Rede: no PC, abra http://" + ip() + ":" + Envio.PORT + " (lá também dá para apagar).\n"
                + "Cada jogo vai sozinho para o console certo, e a capa é baixada sozinha.\n\n"
                + "HALF-LIFE\n"
                + "Analógico esquerdo ou direcional: andar  •  Analógico direito: mirar\n"
                + "RT: atirar  •  LT: tiro secundário  •  LB e RB: trocar de arma\n"
                + "A: pular  •  B: agachar  •  Y: usar  •  X: recarregar\n"
                + "SELECT: lanterna  •  START: menu do jogo\n"
                + "Teclado e mouse USB: conecte antes de abrir o jogo";
    }

    private String infoText() {
        StringBuilder emus = new StringBuilder();
        for (Catalog.Sys s : systems) {
            // varios consoles usam o RetroArch: mostra cada emulador uma vez so
            if (s.emuLabel != null && emus.indexOf(s.emuLabel) < 0)
                emus.append(emus.length() > 0 ? ", " : "").append(s.emuLabel);
        }
        for (Catalog.Game g : games) {
            if (Catalog.isXash(g.rom)) emus.append(emus.length() > 0 ? ", " : "").append(g.emuLabel());
        }
        long sdFree = 0, sdTotal = 0;
        try {
            android.os.StatFs fs = new android.os.StatFs(Catalog.romsDir().getPath());
            sdFree = (long) fs.getAvailableBlocks() * fs.getBlockSize();
            sdTotal = (long) fs.getBlockCount() * fs.getBlockSize();
        } catch (Exception ignored) { }
        long up = android.os.SystemClock.elapsedRealtime() / 60000;
        return "Processador: Samsung S5PV210, 1000 MHz\n"
                + "GPU: PowerVR SGX540, " + gpuMhz() + " MHz (menu pela " + (usingGl ? "GPU" : "CPU") + ")\n"
                + "Memória: " + (memKb("MemFree") + memKb("Cached")) / 1024 + " MB livres de "
                + memKb("MemTotal") / 1024 + " MB\n"
                + "Cartão SD: " + sdFree / 1073741824L + " GB livres de " + sdTotal / 1073741824L + " GB\n"
                + "Rede: " + ip() + "\n"
                + "Sistema: T-OS 1.0 (Android " + android.os.Build.VERSION.RELEASE + ")  •  menu: ScrapCade  •  ligado há " + up + " min\n"
                + "Jogos: " + games.size() + ", na pasta roms do cartão SD\n"
                + "Emuladores: " + emus + "\n\n"
                + "Ícones: libretro/retroarch-assets (CC-BY 4.0)  •  Logos: es-theme-carbon\n"
                + "Botões: Xelu's Free Controller Prompts (CC0)  •  Capas: libretro-thumbnails";
    }

    private void applyTheme() {
        carousel.clearCache();
        themeHeader();
        appsRoot.setBackgroundDrawable(Theme.listBackground(0xFF2A8A84));
        aTitle.setTextColor(Theme.text());
        aClock.setTextColor(Theme.text());
        ((BaseAdapter) appGrid.getAdapter()).notifyDataSetChanged();
    }

    private void show(int s) {
        screen = s;
        View v = s == SCR_MAIN ? mainScreen : appsScreen;
        mainScreen.setVisibility(s == SCR_MAIN ? View.VISIBLE : View.GONE);
        appsScreen.setVisibility(s == SCR_APPS ? View.VISIBLE : View.GONE);
        AlphaAnimation fade = new AlphaAnimation(0f, 1f);
        fade.setDuration(140);
        v.startAnimation(fade);
        if (s == SCR_APPS) {
            appsRoot.setBackgroundDrawable(Theme.listBackground(0xFF2A8A84));
            aTitle.setTextColor(Theme.text());
            aClock.setTextColor(Theme.text());
            selectApp(selApp);
        }
    }

    // ------------------------------------------------------------ dados

    private void reload() {
        // com jogo aberto a copia espera: nao disputa CPU com ele
        if (!paused && !organizing && (Organizer.hasWork() || Organizer.usbHasNew())) organize();
        systems = Catalog.load(this);
        games = Catalog.games(systems);
        envio.setGames(games);
        applyFilter();
        loadApps();
        if (!paused) {
            Capas.start(getApplicationContext(), games, prefs.getBoolean("backdrop", true), new Capas.Done() {
                public void meta() {
                    h.post(new Runnable() { public void run() { updateCaption(); } });
                }
                public void art() {
                    h.post(new Runnable() { public void run() { carousel.clearCache(); } });
                }
            });
        }
    }

    /** Leva o que chegou em roms/novos para a pasta de cada console, sem travar a tela. */
    private void organize() {
        organizing = true;
        new Thread(new Runnable() {
            public void run() {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                final String usb = Organizer.importUsb(new Organizer.Progress() {
                    public void at(final String msg) {
                        h.post(new Runnable() { public void run() { toast(msg); } });
                    }
                });
                final String msg = Organizer.run();
                h.post(new Runnable() {
                    public void run() {
                        organizing = false;
                        if (usb != null) toast(usb);
                        if (msg != null) toast(msg);
                        reload();
                    }
                });
            }
        }, "organiza").start();
    }

    /** Monta a lista do filtro atual e mantem o jogo selecionado, se ele continuar na lista. */
    private void applyFilter() {
        filters = buildFilters();
        if (!filters.contains(filter)) filter = F_ALL;   // o filtro ficou sem jogo (cartao saiu, jogo apagado, favorito desmarcado)
        List<Catalog.Game> v = new ArrayList<Catalog.Game>();
        if (F_FAV.equals(filter)) {
            for (Catalog.Game g : games) if (isFav(g)) v.add(g);
        } else if (F_TOP.equals(filter)) {
            for (Catalog.Game g : games) if (plays(g) > 0) v.add(g);
            Collections.sort(v, new Comparator<Catalog.Game>() {     // ordenacao estavel: no empate, fica por nome
                public int compare(Catalog.Game a, Catalog.Game b) { return plays(b) - plays(a); }
            });
            while (v.size() > 20) v.remove(v.size() - 1);
        } else {
            for (Catalog.Game g : games) if (filter.length() == 0 || g.sys.id.equals(filter)) v.add(g);
        }

        Catalog.Game cur = carousel.current();
        String keep = cur != null ? cur.key() : prefs.getString("last_game", "");
        String fallback = prefs.getString("last_" + filter, "");
        int idx = -1, idxFallback = 0;
        boolean same = v.size() == view.size();
        for (int i = 0; i < v.size(); i++) {
            String k = v.get(i).key();
            if (k.equals(keep)) idx = i;
            if (k.equals(fallback)) idxFallback = i;
            if (same && !k.equals(view.get(i).key())) same = false;
        }
        // nada mudou (recarga depois de voltar de um jogo, por exemplo): nao mexe no carrossel
        if (!same || idx < 0) {
            view = v;
            carousel.setItems(view, idx >= 0 ? idx : idxFallback);
        } else if (v != view) {
            view = v;
        }
        updateCaption();
    }

    private void changeFilter(int dir) {
        if (filters.size() < 2) return;
        int cur = Math.max(0, filters.indexOf(filter));
        filter = filters.get((cur + dir + filters.size()) % filters.size());
        prefs.edit().putString("filter", filter).commit();
        applyFilter();
    }

    @Override
    public void onPick(Catalog.Game g) { play(g); }

    @Override
    public void onMoved() {
        // o toque chega pela thread da tela, a mesma destas Views
        updateCaption();
    }

    private void selectApp(int pos) {
        if (apps.isEmpty()) return;
        selApp = Math.max(0, Math.min(apps.size() - 1, pos));
        ((BaseAdapter) appGrid.getAdapter()).notifyDataSetChanged();
        int first = appGrid.getFirstVisiblePosition(), last = appGrid.getLastVisiblePosition();
        if (selApp < first || selApp > last) appGrid.setSelection(selApp);
    }

    private void play(Catalog.Game g) {
        if (g == null) return;
        String k = g.key();
        prefs.edit().putString("last_game", k).putString("last_" + g.sys.id, k).putString("last_", k).commit();
        String err = Catalog.launch(this, g.sys, g.rom);
        if (err != null) {
            toast(err);
            return;
        }
        // conta para o filtro "Mais jogados" e para os detalhes do jogo
        prefs.edit().putInt("plays|" + k, prefs.getInt("plays|" + k, 0) + 1).putLong("when|" + k, System.currentTimeMillis()).commit();
        toast("Abrindo " + g.title + "...\nPara voltar ao menu: Select + Start");
    }

    private void loadApps() {
        apps.clear();
        Intent main = new Intent(Intent.ACTION_MAIN, null);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0))
            if (!getPackageName().equals(ri.activityInfo.packageName)) apps.add(ri);
        Collections.sort(apps, new Comparator<ResolveInfo>() {
            public int compare(ResolveInfo a, ResolveInfo b) {
                return a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString());
            }
        });
        if (appGrid != null) ((BaseAdapter) appGrid.getAdapter()).notifyDataSetChanged();
    }

    private void openApp(int pos) {
        if (pos < 0 || pos >= apps.size()) return;
        ResolveInfo ri = apps.get(pos);
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        i.setClassName(ri.activityInfo.packageName, ri.activityInfo.name);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try { startActivity(i); } catch (Exception e) { toast("Não abriu"); }
    }

    // ------------------------------------------------------------ controle

    private static boolean isOk(int k) {
        return k == KeyEvent.KEYCODE_BUTTON_A || k == KeyEvent.KEYCODE_BUTTON_START
                || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_DPAD_CENTER;
    }

    private static boolean isBack(int k) {
        return k == KeyEvent.KEYCODE_BUTTON_B || k == KeyEvent.KEYCODE_BACK;
    }

    private static boolean isMenu(int k) {
        return k == KeyEvent.KEYCODE_BUTTON_SELECT || k == KeyEvent.KEYCODE_MENU;
    }

    /** Todas as teclas do controle passam por aqui (as listas nao recebem foco). */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (!handles(k)) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN) key(k, e.getRepeatCount() == 0);
        return true;
    }

    private boolean handles(int k) {
        return isOk(k) || isBack(k) || isMenu(k) || k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT
                || k == KeyEvent.KEYCODE_BUTTON_L1 || k == KeyEvent.KEYCODE_BUTTON_R1
                || k == KeyEvent.KEYCODE_BUTTON_X || k == KeyEvent.KEYCODE_BUTTON_Y;
    }

    private void key(int k, boolean first) {
        boolean left = k == KeyEvent.KEYCODE_DPAD_LEFT, right = k == KeyEvent.KEYCODE_DPAD_RIGHT;
        boolean up = k == KeyEvent.KEYCODE_DPAD_UP, down = k == KeyEvent.KEYCODE_DPAD_DOWN;
        boolean l1 = k == KeyEvent.KEYCODE_BUTTON_L1, r1 = k == KeyEvent.KEYCODE_BUTTON_R1;
        if (infoOpen) {
            if ((isBack(k) || isOk(k) || isMenu(k)) && first) closePanel();
            return;
        }
        if (optOpen) {
            if (up) { optSel = (optSel + optRows.size() - 1) % optRows.size(); refreshOptions(); }
            else if (down) { optSel = (optSel + 1) % optRows.size(); refreshOptions(); }
            else if (left && first) optAction(-1);
            else if ((right || isOk(k)) && first) optAction(1);
            else if ((isBack(k) || isMenu(k)) && first) setOptions(false);
            return;
        }
        if (files.isOpen()) {
            files.key(k, first);
            return;
        }
        if (isMenu(k) && first) { setOptions(true); return; }
        if (screen == SCR_MAIN) {
            if (left) step(-1);
            else if (right) step(1);
            else if (up) step(-5);
            else if (down) step(5);
            else if (l1 && first) changeFilter(-1);
            else if (r1 && first) changeFilter(1);
            else if (k == KeyEvent.KEYCODE_BUTTON_X && first) toggleFav();
            else if (k == KeyEvent.KEYCODE_BUTTON_Y && first) showDetails();
            else if (isOk(k) && first) play(carousel.current());
        } else {
            int cols = 6;
            if (left) selectApp(selApp - 1);
            else if (right) selectApp(selApp + 1);
            else if (up) selectApp(selApp - cols);
            else if (down) selectApp(selApp + cols);
            else if (isOk(k) && first) openApp(selApp);
            else if (isBack(k) && first) show(SCR_MAIN);
        }
    }

    private void step(int n) {
        carousel.move(n);
        updateCaption();
    }

    @Override
    public void onBackPressed() {
        if (infoOpen) closePanel();
        else if (optOpen) setOptions(false);
        else if (files.isOpen()) files.close();
        else if (screen != SCR_MAIN) show(SCR_MAIN);
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (infoOpen) closePanel();
        if (optOpen) setOptions(false);
        if (files.isOpen()) files.close();
        if (Intent.ACTION_MAIN.equals(i.getAction()) && screen != SCR_MAIN) show(SCR_MAIN);
    }

    @Override
    protected void onResume() {
        super.onResume();
        paused = false;
        if (usingGl) ((GLSurfaceView) carousel.view()).onResume();
        if (Catalog.gameMode) {          // voltou do jogo: B e LT voltam a ser "voltar" e "menu"
            Catalog.gameMode = false;
            if (Catalog.gameFailed(this)) toast("Esse jogo não abriu. O arquivo pode estar com defeito.");
            Catalog.sendCmd("game 0");
        }
        reload();
        tick();
    }

    @Override
    protected void onPause() {
        super.onPause();
        paused = true;
        Capas.stop = true;
        h.removeCallbacks(ticker);     // nada de trabalho de fundo enquanto o jogo roda
        h.removeCallbacks(reloader);
        h.removeCallbacks(barsTick);
        if (usingGl) ((GLSurfaceView) carousel.view()).onPause();   // libera a memoria da GPU para o jogo
    }

    // ------------------------------------------------------------ relogio / IP

    private final Runnable ticker = new Runnable() { public void run() { tick(); } };

    private void tick() {
        h.removeCallbacks(ticker);
        String now = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        hClock.setText(now);
        String day = new SimpleDateFormat("EEE, dd/MM", new Locale("pt", "BR")).format(new Date());
        String net = ip();
        hIp.setText(net.length() > 0 ? day + "   •   " + net : day + "   •   sem rede");
        aClock.setText(now);
        // pen drive ou arquivo novo no cartao, mesmo se o aviso do Android nao chegar
        if (!organizing && (Organizer.hasWork() || Organizer.usbHasNew())) reload();
        h.postDelayed(ticker, 10000);
    }

    private static String ip() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
                NetworkInterface ni = en.nextElement();
                for (Enumeration<InetAddress> ea = ni.getInetAddresses(); ea.hasMoreElements(); ) {
                    InetAddress a = ea.nextElement();
                    if (!a.isLoopbackAddress() && a instanceof Inet4Address) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return "";
    }

    // ------------------------------------------------------------ utilitarios

    /** Texto medido em unidades da tela, e nao na escala de fonte do Android. */
    private TextView text(float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * u);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** Botao do controle como etiqueta clara: os icones escuros sumiam na barra escura. */
    private TextView pill(String label) {
        TextView t = text(7.5f, 0xFF202329, true);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        t.setPadding(dp(5), dp(1.5f), dp(5), dp(1.5f));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xEEFFFFFF);
        g.setCornerRadius(dp(4));
        t.setBackgroundDrawable(g);
        return t;
    }

    private GradientDrawable highlight(int color, int alpha) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Art.alpha(color, alpha));
        g.setCornerRadius(dp(8));
        return g;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    // ------------------------------------------------------------ desenhos pequenos

    /** Logo branco do console do jogo selecionado; sem logo, o nome escrito no mesmo tom. */
    private static final class LogoView extends View {
        private final android.graphics.Paint p = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);
        private Catalog.Sys s;
        LogoView(Context c) { super(c); }
        void set(Catalog.Sys n) {
            if (n == s) return;
            s = n;
            invalidate();
        }
        @Override
        protected void onDraw(android.graphics.Canvas c) {
            if (s == null) return;
            Art.drawWhiteLogo(getContext(), c, s, new android.graphics.RectF(0, 0, getWidth(), getHeight()), p, false);
        }
    }

    /** Barras de uso: CPU, memoria e cartao. */
    private final class Bars extends View {
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final int[] v = new int[3];
        Bars(Context c) { super(c); }
        void set(int cpu, int mem, int sd) {
            v[0] = cpu;
            v[1] = mem;
            v[2] = sd;
            invalidate();
        }
        @Override
        protected void onDraw(android.graphics.Canvas c) {
            float w = getWidth(), label = 11 * u, top = 9 * u, bottom = getHeight() - label;
            String[] names = {"CPU", "MEM", "CARTÃO"};
            int[] cols = {0xFFD04545, 0xFFD9A32B, 0xFF2CCF9A};
            p.setTextSize(8 * u);
            p.setTextAlign(android.graphics.Paint.Align.CENTER);
            p.setColor(0xFF2B2E33);
            c.drawRect(0, bottom, w, bottom + u * 0.7f, p);
            float slot = w / 3;
            for (int i = 0; i < 3; i++) {
                float cx = slot * (i + 0.5f), bw = slot * 0.26f, bh = (bottom - top) * Math.min(v[i], 100) / 100f;
                p.setColor(cols[i]);
                c.drawRect(cx - bw, bottom - bh, cx + bw, bottom, p);
                p.setColor(0xFF2B2E33);
                c.drawText(v[i] + "%", cx, bottom - bh - 2 * u, p);
                p.setColor(0xFF5E6269);
                c.drawText(names[i], cx, bottom + 8.5f * u, p);
            }
        }
    }

    // ------------------------------------------------------------ adapters

    private final class AppsAdapter extends BaseAdapter {
        public int getCount() { return apps.size(); }
        public Object getItem(int p) { return apps.get(p); }
        public long getItemId(int p) { return p; }
        public View getView(int pos, View cv, ViewGroup parent) {
            LinearLayout cell = new LinearLayout(Home.this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(dp(4), dp(8), dp(4), dp(8));
            if (pos == selApp) cell.setBackgroundDrawable(highlight(Theme.light() ? 0xFF2A8A84 : 0xFFFFFFFF, 70));
            ResolveInfo ri = apps.get(pos);
            ImageView ic = new ImageView(Home.this);
            try { Drawable dr = ri.loadIcon(pm); ic.setImageDrawable(dr); } catch (Exception ignored) { }
            cell.addView(ic, new LinearLayout.LayoutParams(dp(46), dp(46)));
            TextView t = text(12, Theme.text(), false);
            t.setText(ri.loadLabel(pm).toString());
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.FILL_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(4);
            cell.addView(t, lp);
            return cell;
        }
    }
}
