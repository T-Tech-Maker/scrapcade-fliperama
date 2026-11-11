package com.thiago.launcher;

import android.content.Context;
import android.os.Process;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * Trabalho de fundo da biblioteca, com prioridade baixa:
 *  1) dados de cada jogo (classe Meta), sem rede;
 *  2) com a rede ligada, a capa e a imagem de fundo que faltam (acervo libretro-thumbnails, sem conta).
 * O que o acervo nao tem fica anotado em roms/.sem_capa e roms/.sem_fundo, para nao tentar de novo a cada abertura.
 */
final class Capas {
    private static final String BASE = "http://thumbnails.libretro.com/";   // HTTP: o Android 2.3 nao fala o TLS atual
    private static final String[] COVER = {"Named_Boxarts", "Named_Titles"};   // sem caixa, serve a tela de titulo
    private static final String[] BACKDROP = {"Named_Snaps", "Named_Titles"};  // uma cena do jogo
    static volatile boolean stop;
    private static boolean running, again;

    /** Avisos, chamados fora da thread da tela. */
    interface Done {
        /** Chegaram dados de jogos (ano, empresa, genero). */
        void meta();
        /** Chegou capa ou fundo novo. */
        void art();
    }

    private Capas() { }

    static synchronized void start(final Context ctx, final List<Catalog.Game> games, final boolean backdrops, final Done done) {
        stop = false;       // antes de tudo: uma pausa rapida do menu deixava o trabalho em andamento parar e nao voltar
        if (running) {
            again = true;   // o trabalho em andamento pode ter visto a pausa e parado: roda de novo quando acabar
            return;
        }
        running = true;
        new Thread(new Runnable() {
            public void run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST);
                while (true) {
                    try {
                        work(ctx, games, backdrops, done);
                    } catch (Exception e) {
                        // sem rede ou cartao: tenta de novo na proxima abertura
                        android.util.Log.w("fliperama", "capas: " + e);
                    }
                    synchronized (Capas.class) {
                        if (!again || stop) {
                            running = false;
                            again = false;
                            return;
                        }
                        again = false;
                    }
                }
            }
        }, "capas").start();
    }

    private static HashSet<String> readMemo(File f) throws IOException {
        HashSet<String> s = new HashSet<String>();
        if (!f.isFile()) return s;
        BufferedReader r = new BufferedReader(new FileReader(f));
        try {
            String l;
            while ((l = r.readLine()) != null) s.add(l);
        } finally {
            r.close();
        }
        return s;
    }

    private static void addMemo(File f, String key) throws IOException {
        FileWriter w = new FileWriter(f, true);
        try {
            w.write(key + "\n");
        } finally {
            w.close();
        }
    }

    private static void work(Context ctx, List<Catalog.Game> games, boolean backdrops, Done done) throws IOException {
        boolean any = false;
        for (Catalog.Game g : games) {
            if (Meta.get(g.key()) == null) {    // sem rede e rapido: nao para no meio, mesmo com jogo aberto
                Meta.put(g.key(), Meta.find(ctx, g));
                any = true;
            }
        }
        if (any) done.meta();

        File memoC = new File(Catalog.romsDir(), ".sem_capa"), memoF = new File(Catalog.romsDir(), ".sem_fundo");
        HashSet<String> missC = readMemo(memoC), missF = readMemo(memoF);
        any = false;
        try {
            for (Catalog.Game g : games) {
                if (stop) break;
                if (Catalog.isXash(g.rom)) continue;
                boolean needC = Catalog.cover(g.rom) == null && !missC.contains(g.key());
                boolean needF = backdrops && Catalog.backdrop(g.rom) == null && !missF.contains(g.key());
                if (!needC && !needF) continue;
                List<String[]> where = candidates(g);
                if (where.isEmpty()) continue;
                File dir = new File(g.rom.getParentFile(), "images");
                if (needC) {
                    if (fetchAny(where, COVER, new File(dir, Meta.base(g.rom) + ".png"))) any = true;
                    else addMemo(memoC, g.key());
                }
                if (needF) {
                    if (fetchAny(where, BACKDROP, new File(dir, Meta.base(g.rom) + "-fundo.png"))) any = true;
                    else addMemo(memoF, g.key());
                }
            }
        } finally {
            if (any) done.art();     // o que ja baixou aparece, mesmo se a rede cair no meio
        }
    }

    /** Onde procurar a arte do jogo: pares {pasta do acervo, nome}, do mais confiavel para o menos. */
    private static List<String[]> candidates(Catalog.Game g) throws IOException {
        List<String[]> l = new ArrayList<String[]>();
        String id = g.sys.id, base = Meta.base(g.rom);
        Meta.Info m = Meta.get(g.key());
        String official = m != null ? m.name : "";
        if ("arcade".equals(id) || "neogeo".equals(id)) {
            // Neo Geo: primeiro a caixa do cartucho de casa (AES, em pe). O acervo de arcade costuma ter so o cartaz
            // ou a ficha de comandos do fliperama, larga, que nao parece capa.
            String home = "neogeo".equals(id) && official.length() > 0 ? neoBox(official) : null;
            if (home != null) l.add(new String[] {"SNK - Neo Geo", home});
            // Depois pelo nome exato do conjunto; so no fim o nome parecido, que pode trazer arte errada
            if (official.length() > 0) l.add(new String[] {"FBNeo - Arcade Games", official});
            String desc = Catalog.arcadeName(base);
            if ("arcade".equals(id) && desc != null) l.add(new String[] {"MAME", desc});
            String near = fromIndex(g.title);
            if (near != null) l.add(new String[] {"MAME", near});
            return l;
        }
        String sys = system(g);
        if (sys == null) return l;
        if (official.length() > 0) l.add(new String[] {sys, official});
        if (!base.equals(official)) l.add(new String[] {sys, base});
        return l;
    }

    private static boolean fetchAny(List<String[]> where, String[] kinds, File dest) throws IOException {
        for (String[] w : where)
            for (String kind : kinds)
                if (fetchFrom(w[0], kind, w[1], dest)) return true;
        return false;
    }

    /**
     * O acervo de arcade usa nomes mais novos que os do MAME 0.37 ("(World 4 Players)" e nao "(4 Players US)").
     * Baixa uma vez a lista dele e pega o nome mais curto que comeca com o nome do jogo.
     */
    private static String fromIndex(String title) throws IOException {
        File idx = index("MAME", ".capas_arcade");
        if (idx == null) return null;
        String safe = title.replaceAll("[&*/:`<>?\\\\|\"]", "_").toLowerCase(Locale.US);
        String best = null;
        BufferedReader r = new BufferedReader(new FileReader(idx));
        try {
            String l;
            while ((l = r.readLine()) != null) {
                String name = entry(l);
                if (name == null) continue;
                String low = name.toLowerCase(Locale.US);
                if (!low.startsWith(safe)) continue;
                if (low.length() > safe.length() && " ,(".indexOf(low.charAt(safe.length())) < 0) continue;
                // Versao mundial ou americana primeiro; entre iguais, o nome mais curto
                boolean world = low.contains("world") || low.contains("us");
                boolean bestWorld = best != null && (best.toLowerCase(Locale.US).contains("world") || best.toLowerCase(Locale.US).contains("us"));
                if (best == null || (world && !bestWorld) || (world == bestWorld && name.length() < best.length())) best = name;
            }
        } finally {
            r.close();
        }
        return best;
    }

    /** Nome sem os parenteses do fim, do jeito que o acervo grava ("/" vira "_"), em minusculas. */
    private static String stem(String name) {
        int p = name.indexOf(" (");
        if (p < 0) p = name.indexOf(" [");
        if (p > 0) name = name.substring(0, p);
        return name.replace('/', '_').trim().toLowerCase(Locale.US);
    }

    /**
     * Caixa do Neo Geo de casa com o mesmo nome do jogo ("Metal Slug 4 (NGH-2630)" para "Metal Slug 4 (NGM-2630)").
     * Prefere a caixa de casa (NGH), depois a de fliperama (NGM); pirata e versao coreana so se o jogo for um deles.
     */
    private static String neoBox(String official) throws IOException {
        File idx = index("SNK - Neo Geo", ".capas_neogeo");
        if (idx == null) return null;
        String want = stem(official), offLow = official.toLowerCase(Locale.US);
        boolean pirate = offLow.contains("bootleg"), korean = offLow.contains("korea");
        String best = null;
        int bestScore = -1;
        BufferedReader r = new BufferedReader(new FileReader(idx));
        try {
            String l;
            while ((l = r.readLine()) != null) {
                String name = entry(l);
                if (name == null || !stem(name).equals(want)) continue;
                String low = name.toLowerCase(Locale.US);
                if (!pirate && low.contains("bootleg")) continue;
                if (!korean && low.contains("korea")) continue;
                int score = (low.contains("ngh") ? 4 : 0) + (low.contains("ngm") ? 2 : 0)
                        + (low.contains("prototype") || low.contains("alternate") ? 0 : 1);
                if (score > bestScore) {
                    best = name;
                    bestScore = score;
                }
            }
        } finally {
            r.close();
        }
        return best;
    }

    /** Nome do arquivo numa linha da lista do acervo, ou null. */
    private static String entry(String l) throws IOException {
        int a = l.indexOf("href=\""), b = l.indexOf(".png\"", a + 1);
        if (a < 0 || b < 0) return null;
        return java.net.URLDecoder.decode(l.substring(a + 6, b), "UTF-8");
    }

    /** Lista de capas de uma pasta do acervo, baixada uma vez e guardada no cartao. null se nao deu. */
    private static File index(String sys, String file) throws IOException {
        File idx = new File(Catalog.romsDir(), file);
        if (!idx.isFile()) {
            HttpURLConnection c = (HttpURLConnection) new URL(BASE + enc(sys) + "/Named_Boxarts/").openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(30000);
            File tmp = new File(idx.getPath() + ".baixando");
            try {
                InputStream in = c.getInputStream();
                FileOutputStream out = new FileOutputStream(tmp);
                try {
                    byte[] b = new byte[16384];
                    int n;
                    while ((n = in.read(b)) > 0) out.write(b, 0, n);
                } finally {
                    out.close();
                    in.close();
                }
            } finally {
                c.disconnect();
            }
            if (!tmp.renameTo(idx)) return null;
        }
        return idx;
    }

    /** Pasta do console no acervo, ou null se o console nao tem arte la (jogos de computador). */
    private static String system(Catalog.Game g) {
        if ("psx".equals(g.sys.id)) return "Sony - PlayStation";
        String ix = Meta.indexId(g);
        if (ix == null) return null;
        if ("gb".equals(ix)) return "Nintendo - Game Boy";
        if ("gbc".equals(ix)) return "Nintendo - Game Boy Color";
        if ("gba".equals(ix)) return "Nintendo - Game Boy Advance";
        if ("nes".equals(ix)) return "Nintendo - Nintendo Entertainment System";
        if ("snes".equals(ix)) return "Nintendo - Super Nintendo Entertainment System";
        if ("n64".equals(ix)) return "Nintendo - Nintendo 64";
        if ("sms".equals(ix)) return "Sega - Master System - Mark III";
        if ("gg".equals(ix)) return "Sega - Game Gear";
        return "Sega - Mega Drive - Genesis";
    }

    private static String enc(String s) throws IOException {
        return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
    }

    /** true se baixou. Falta no acervo devolve false; falha de rede lanca excecao e interrompe tudo. */
    private static boolean fetchFrom(String sys, String kind, String name, File dest) throws IOException {
        String safe = name.replaceAll("[&*/:`<>?\\\\|\"]", "_");
        URL u = new URL(BASE + enc(sys) + "/" + kind + "/" + enc(safe) + ".png");
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        try {
            if (c.getResponseCode() != 200) return false;
            dest.getParentFile().mkdirs();
            File tmp = new File(dest.getPath() + ".baixando");
            InputStream in = c.getInputStream();
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                byte[] b = new byte[16384];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
            } finally {
                out.close();
                in.close();
            }
            return tmp.renameTo(dest);
        } finally {
            c.disconnect();
        }
    }
}
