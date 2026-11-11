package com.thiago.launcher;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gerenciador de arquivos pelo controle (SELECT, Arquivos): navegar, copiar, mover, clonar e apagar,
 * nas mesmas pastas da pagina do PC (classe Arquivos) e no pen drive.
 * Renomear e criar pasta ficam na pagina do PC, que tem teclado.
 */
final class ArquivosTela {
    private static final int FILL = ViewGroup.LayoutParams.FILL_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int GREEN = 0xFF2CCF9A, DARK = 0xFF2B2E33, GRAY = 0xFF4A4D52;

    private final Activity act;
    private final Runnable onChanged;
    private final Handler h = new Handler();
    private final float u;
    private final FrameLayout root, menuDim;
    private final LinearLayout menu;
    private final TextView title, status, menuTitle;
    private final ListView list;
    private final List<TextView> menuRows = new ArrayList<TextView>();

    private List<Arquivos.Entry> items = new ArrayList<Arquivos.Entry>();
    private String cur = "";            // pasta aberta; "" = lista das pastas principais
    private int sel;
    private Arquivos.Entry clip;        // o que foi escolhido para copiar ou mover
    private boolean clipMove, busy, changed, menuOpen;
    private int menuSel;
    private Runnable[] menuRuns;

    /** onChanged: chamado ao fechar, se algum arquivo mudou (o menu recarrega a lista de jogos). */
    ArquivosTela(Activity a, Runnable onChanged) {
        act = a;
        this.onChanged = onChanged;
        u = Carousel.unit(a);
        root = new FrameLayout(a);
        root.setBackgroundColor(0x99000000);
        root.setVisibility(View.GONE);
        root.setClickable(true);        // o toque nao passa para as capas atras

        LinearLayout panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xFFFFFFFF);
        title = text(12, Color.WHITE, false);
        title.setBackgroundColor(GRAY);
        title.setPadding(dp(12), dp(4), dp(12), dp(4));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.START);
        panel.addView(title, new LinearLayout.LayoutParams(FILL, WRAP));
        list = new ListView(a);
        list.setDivider(null);
        list.setCacheColorHint(0);
        list.setSelector(new ColorDrawable(0));     // o destaque e desenhado pelo adapter
        list.setFocusable(false);                   // as teclas chegam por key()
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (busy || menuOpen) return;
                sel = pos;
                adapter.notifyDataSetChanged();
                enter();
            }
        });
        panel.addView(list, new LinearLayout.LayoutParams(FILL, 0, 1f));
        status = text(9, DARK, false);
        status.setBackgroundColor(0xFFE9EBEF);
        status.setPadding(dp(12), dp(3), dp(12), dp(3));
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        panel.addView(status, new LinearLayout.LayoutParams(FILL, WRAP));
        HintBar hb = new HintBar(a);
        hb.setOnPanel();
        hb.set("UD", "escolher", "A", "abrir", "Y", "ações", "X", "colar aqui", "B", "voltar");
        panel.addView(hb, new LinearLayout.LayoutParams(FILL, WRAP));
        root.addView(panel, new FrameLayout.LayoutParams(dp(480), dp(272), Gravity.CENTER));

        menuDim = new FrameLayout(a);
        menuDim.setBackgroundColor(0x99000000);
        menuDim.setVisibility(View.GONE);
        menuDim.setClickable(true);
        menu = new LinearLayout(a);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setBackgroundColor(0xFFFFFFFF);
        menu.setPadding(dp(10), dp(8), dp(10), dp(8));
        menuTitle = text(10.5f, DARK, true);
        menuTitle.setPadding(dp(2), 0, dp(2), dp(6));
        menu.addView(menuTitle, new LinearLayout.LayoutParams(FILL, WRAP));
        menuDim.addView(menu, new FrameLayout.LayoutParams(dp(290), WRAP, Gravity.CENTER));
        root.addView(menuDim, new FrameLayout.LayoutParams(FILL, FILL));
    }

    View view() { return root; }

    boolean isOpen() { return root.getVisibility() == View.VISIBLE; }

    void open() {
        changed = false;
        clip = null;
        closeMenu();
        root.setVisibility(View.VISIBLE);
        load("", null);
    }

    /** Fecha a tela. Uma copia em andamento continua ate o fim. */
    void close() {
        closeMenu();
        root.setVisibility(View.GONE);
        if (changed) onChanged.run();
    }

    private int dp(float v) { return (int) (v * u + 0.5f); }

    private TextView text(float size, int color, boolean bold) {
        TextView t = new TextView(act);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * u);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private static String size(long b) {
        if (b >= 1024L * 1024 * 1024) return String.format(java.util.Locale.US, "%.1f GB", b / 1073741824.0);
        if (b >= 1024L * 1024) return String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0);
        return (b + 1023) / 1024 + " KB";
    }

    // ------------------------------------------------------------ lista

    private void load(String path, String selectName) {
        List<Arquivos.Entry> l = Arquivos.entries(path);
        if (l == null) {                 // a pasta sumiu (pen drive tirado, pasta apagada)
            path = "";
            l = Arquivos.entries("");
        }
        cur = path;
        items = l;
        sel = 0;
        if (selectName != null) for (int i = 0; i < l.size(); i++) if (l.get(i).name.equals(selectName)) sel = i;
        title.setText("Arquivos   •   " + (cur.length() == 0 ? "pastas de jogos" : cur));
        adapter.notifyDataSetChanged();
        list.setSelectionFromTop(sel, 0);
        showStatus(null);
    }

    private void showStatus(String msg) {
        if (msg != null) status.setText(msg);
        else if (clip != null) status.setText((clipMove ? "Mover " : "Copiar ") + clip.name + ":  abra a pasta de destino e aperte X");
        else status.setText(items.isEmpty() ? "Pasta vazia" : (items.size() == 1 ? "1 item" : items.size() + " itens"));
    }

    private Arquivos.Entry current() { return sel >= 0 && sel < items.size() ? items.get(sel) : null; }

    private void move(int d) {
        if (items.isEmpty()) return;
        sel = Math.max(0, Math.min(items.size() - 1, sel + d));
        adapter.notifyDataSetChanged();
        int first = list.getFirstVisiblePosition(), last = list.getLastVisiblePosition();
        if (sel <= first || sel >= last) {
            int row = list.getChildCount() > 0 ? list.getChildAt(0).getHeight() : dp(16);
            list.setSelectionFromTop(sel, sel <= first ? 0 : Math.max(0, list.getHeight() - row * 2));
        }
    }

    private void enter() {
        Arquivos.Entry e = current();
        if (e == null) return;
        if (e.dir) load(e.path, null);
        else actions(e);
    }

    private void up() {
        if (cur.length() == 0) {
            close();
            return;
        }
        int cut = cur.lastIndexOf('/');
        load(cut < 0 ? "" : cur.substring(0, cut), cut < 0 ? cur : cur.substring(cut + 1));
    }

    private final BaseAdapter adapter = new BaseAdapter() {
        public int getCount() { return items.size(); }
        public Object getItem(int p) { return items.get(p); }
        public long getItemId(int p) { return p; }
        public View getView(int pos, View cv, ViewGroup parent) {
            Arquivos.Entry e = items.get(pos);
            boolean on = pos == sel;
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(3), dp(12), dp(3));
            row.setBackgroundColor(on ? GREEN : 0);
            TextView n = text(10.5f, on ? 0xFF10131A : DARK, e.dir);
            n.setText(e.dir ? e.name + "  /" : e.name);
            n.setSingleLine(true);
            n.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            row.addView(n, new LinearLayout.LayoutParams(0, WRAP, 1f));
            TextView s = text(9, on ? 0xFF10131A : 0xFF7A7E86, false);
            s.setText(e.dir ? "pasta" : size(e.size));
            s.setPadding(dp(10), 0, 0, 0);
            row.addView(s);
            return row;
        }
    };

    // ------------------------------------------------------------ menu de acoes

    private void openMenu(String heading, String[] labels, Runnable[] runs) {
        for (TextView r : menuRows) menu.removeView(r);
        menuRows.clear();
        menuTitle.setText(heading);
        menuRuns = runs;
        for (int i = 0; i < labels.length; i++) {
            TextView r = text(10, Color.WHITE, false);
            r.setText(labels[i]);
            r.setPadding(dp(8), dp(2.5f), dp(8), dp(2.5f));
            final int idx = i;
            r.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { pick(idx); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(FILL, WRAP);
            lp.bottomMargin = dp(2);
            menu.addView(r, lp);
            menuRows.add(r);
        }
        menuSel = 0;
        menuOpen = true;
        paintMenu();
        menuDim.setVisibility(View.VISIBLE);
    }

    private void paintMenu() {
        for (int i = 0; i < menuRows.size(); i++) menuRows.get(i).setBackgroundColor(i == menuSel ? GREEN : GRAY);
    }

    private void closeMenu() {
        menuOpen = false;
        menuDim.setVisibility(View.GONE);
    }

    private void pick(int i) {
        Runnable r = menuRuns[i];
        closeMenu();
        if (r != null) r.run();
    }

    private void actions(final Arquivos.Entry e) {
        if (cur.length() == 0) {
            showStatus("Abra a pasta primeiro: as pastas principais não podem ser mexidas.");
            return;
        }
        openMenu(e.name, new String[] {"Copiar para outra pasta", "Mover para outra pasta", "Clonar (cópia nesta pasta)", "Apagar", "Cancelar"},
                new Runnable[] {
                    new Runnable() { public void run() { clip = e; clipMove = false; showStatus(null); } },
                    new Runnable() { public void run() { clip = e; clipMove = true; showStatus(null); } },
                    new Runnable() { public void run() { runOp("clonar", e.path, null); } },
                    new Runnable() { public void run() { confirmDelete(e); } },
                    null});
    }

    private void confirmDelete(final Arquivos.Entry e) {
        openMenu("Apagar " + e.name + (e.dir ? " e tudo dentro dela" : "") + "?\nNão dá para desfazer.",
                new String[] {"Não", "Sim, apagar"},
                new Runnable[] {null, new Runnable() { public void run() { runOp("apagar", e.path, null); } }});
    }

    private void paste() {
        if (clip == null) {
            showStatus("Nada para colar: escolha Copiar ou Mover em um arquivo (Y).");
            return;
        }
        if (cur.length() == 0) {
            showStatus("Abra a pasta de destino primeiro.");
            return;
        }
        Arquivos.Entry c = clip;
        boolean mv = clipMove;
        clip = null;
        runOp(mv ? "mover" : "copiar", c.path, cur);
    }

    private final Runnable progress = new Runnable() {
        public void run() {
            if (!busy) return;
            status.setText("Trabalhando...   " + size(Arquivos.progress) + "   (não desligue o aparelho)");
            h.postDelayed(this, 500);
        }
    };

    /** Copias grandes demoram minutos: rodam fora da thread da tela, e os botoes ficam parados ate terminar. */
    private void runOp(final String op, final String a, final String b) {
        busy = true;
        changed = true;
        status.setText("Aguarde...");
        h.postDelayed(progress, 700);
        new Thread(new Runnable() {
            public void run() {
                String r;
                try {
                    Map<String, String> q = new HashMap<String, String>();
                    q.put("a", a);
                    if (b != null) q.put("b", b);
                    r = Arquivos.run(op, q);
                } catch (Exception e) {
                    r = "500 " + e.getMessage();
                }
                final String res = r;
                h.post(new Runnable() {
                    public void run() {
                        busy = false;
                        h.removeCallbacks(progress);
                        int keep = sel;
                        load(cur, null);
                        sel = Math.max(0, Math.min(items.size() - 1, keep));
                        adapter.notifyDataSetChanged();
                        list.setSelectionFromTop(sel, 0);
                        showStatus(res.startsWith("200") ? "Feito." : "Não deu:  " + res.substring(Math.min(4, res.length())));
                    }
                });
            }
        }, "arquivos").start();
    }

    // ------------------------------------------------------------ controle

    /** Todas as teclas do controle enquanto a tela esta aberta. */
    void key(int k, boolean first) {
        if (busy) return;
        boolean up = k == KeyEvent.KEYCODE_DPAD_UP, down = k == KeyEvent.KEYCODE_DPAD_DOWN;
        boolean ok = k == KeyEvent.KEYCODE_BUTTON_A || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_BUTTON_START;
        boolean back = k == KeyEvent.KEYCODE_BUTTON_B || k == KeyEvent.KEYCODE_BACK;
        if (menuOpen) {
            if (up) menuSel = (menuSel + menuRows.size() - 1) % menuRows.size();
            else if (down) menuSel = (menuSel + 1) % menuRows.size();
            else if (ok && first) { pick(menuSel); return; }
            else if (back && first) { closeMenu(); return; }
            paintMenu();
            return;
        }
        if (up) move(-1);
        else if (down) move(1);
        else if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_BUTTON_L1) move(-8);
        else if (k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_BUTTON_R1) move(8);
        else if (ok && first) enter();
        else if (k == KeyEvent.KEYCODE_BUTTON_Y && first) { if (current() != null) actions(current()); }
        else if (k == KeyEvent.KEYCODE_BUTTON_X && first) paste();
        else if (back && first) up();
    }
}
