package com.thiago.launcher;

import android.content.Context;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.HashMap;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Dados de cada jogo: nome oficial, ano, empresa e genero.
 * Vem dos indices dentro do app (assets/crc e assets/fbneo.idx, ver art_src/gera_crc.py), sem rede e sem conta:
 * o jogo e reconhecido pelo CRC do arquivo. O resultado fica em roms/.meta, para so procurar uma vez.
 */
final class Meta {
    static final class Info {
        String name = "", year = "", dev = "", genre = "";
    }

    private static final HashMap<String, Info> cache = new HashMap<String, Info>();
    private static boolean loaded;

    private Meta() { }

    private static File file() { return new File(Catalog.romsDir(), ".meta"); }

    private static void load() {
        if (loaded || !Catalog.romsDir().isDirectory()) return;     // cartao ainda nao montou: tenta depois
        loaded = true;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(file()), "UTF-8"));
            String l;
            while ((l = r.readLine()) != null) {
                String[] f = l.split("\t", -1);
                if (f.length < 5) continue;
                Info i = new Info();
                i.name = f[1];
                i.year = f[2];
                i.dev = f[3];
                i.genre = f[4];
                cache.put(f[0], i);
            }
        } catch (IOException ignored) {
            // ainda nao existe
        } finally {
            try { if (r != null) r.close(); } catch (IOException ignored) { }
        }
    }

    /** O que ja se sabe do jogo; null se ele ainda nao foi procurado. */
    static synchronized Info get(String key) {
        load();
        return cache.get(key);
    }

    static synchronized void put(String key, Info i) {
        load();
        cache.put(key, i);
        try {
            Writer w = new OutputStreamWriter(new FileOutputStream(file(), true), "UTF-8");
            try {
                w.write(key + "\t" + i.name + "\t" + i.year + "\t" + i.dev + "\t" + i.genre + "\n");
            } finally {
                w.close();
            }
        } catch (IOException ignored) {
            // sem cartao: procura de novo na proxima abertura
        }
    }

    /** "1991 • Nintendo • Plataforma", so com o que existir. */
    static String line(Catalog.Game g) {
        Info i = get(g.key());
        if (i == null) return "";
        StringBuilder s = new StringBuilder();
        for (String p : new String[] {i.year, i.dev, genrePt(i.genre)}) {
            if (p.length() == 0) continue;
            if (s.length() > 0) s.append("  •  ");
            s.append(p);
        }
        return s.toString();
    }

    private static final String[][] GENRES = {
        {"platform", "Plataforma"}, {"racing", "Corrida"}, {"driving", "Corrida"}, {"fighting", "Luta"},
        {"beat", "Briga de rua"}, {"shoot", "Tiro"}, {"role-playing", "RPG"}, {"rpg", "RPG"}, {"sport", "Esporte"},
        {"puzzle", "Quebra-cabeça"}, {"adventure", "Aventura"}, {"strategy", "Estratégia"}, {"simulation", "Simulação"},
        {"pinball", "Pinball"}, {"board", "Tabuleiro"}, {"card", "Cartas"}, {"music", "Música"}, {"quiz", "Perguntas"},
        {"educational", "Educativo"}, {"compilation", "Coletânea"}, {"action", "Ação"},
    };

    static String genrePt(String g) {
        String low = g.toLowerCase(Locale.US);
        for (String[] p : GENRES) if (low.contains(p[0])) return p[1];
        return g;
    }

    // ------------------------------------------------------------ busca (le o arquivo do jogo: fora da thread da tela)

    static String ext(File f) {
        String n = f.getName().toLowerCase(Locale.US);
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(dot + 1) : "";
    }

    static String base(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    /** Qual indice de CRC serve para o jogo, ou null (PlayStation, arcade e PC nao tem). */
    static String indexId(Catalog.Game g) {
        String e = ext(g.rom);
        if ("gba".equals(g.sys.id)) return "gba";
        if ("gbc".equals(g.sys.id)) return "gb".equals(e) ? "gb" : "gbc";
        if ("nes".equals(g.sys.id)) return "nes";
        if ("snes".equals(g.sys.id)) return "snes";
        if ("n64".equals(g.sys.id)) return "n64";
        if ("md".equals(g.sys.id)) return "sms".equals(e) ? "sms" : ("gg".equals(e) ? "gg" : "md");
        return null;
    }

    static Info find(Context ctx, Catalog.Game g) throws IOException {
        Info i = new Info();
        String id = g.sys.id;
        if (Catalog.isXash(g.rom)) {
            i.name = "Half-Life";
            i.year = "1998";
            i.dev = "Valve";
            i.genre = "Shooter";
            return i;
        }
        if ("arcade".equals(id) || "neogeo".equals(id)) {
            String n = Catalog.neoName(base(g.rom));
            if (n != null) i.name = n;
            String[] x = Catalog.neoExtra(base(g.rom));
            if (x != null) {
                i.year = x[0];
                i.dev = x[1];
            }
            return i;
        }
        String ix = indexId(g);
        if (ix == null) return i;
        String rec = lookup(ctx, ix, romCrc(g.rom));
        if (rec != null) {
            String[] f = rec.split("\t", -1);
            i.name = f[0];
            if (f.length > 1) i.year = f[1];
            if (f.length > 2) i.dev = f[2];
            if (f.length > 3) i.genre = f[3];
        }
        return i;
    }

    private static String lookup(Context ctx, String id, String crc) throws IOException {
        if (crc == null) return null;
        BufferedReader r = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(ctx.getAssets().open("crc/" + id + ".idx")), "UTF-8"));
        try {
            String l;
            while ((l = r.readLine()) != null) if (l.startsWith(crc)) return l.substring(9);
        } finally {
            r.close();
        }
        return null;
    }

    /** CRC do jogo, sem o cabecalho que as bases nao contam (.smc de 512 bytes, NES de 16). */
    private static String romCrc(File f) throws IOException {
        if (f.getName().toLowerCase(Locale.US).endsWith(".zip")) {
            ZipFile z = new ZipFile(f);
            try {
                ZipEntry big = null;
                for (java.util.Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                    ZipEntry ze = e.nextElement();
                    if (big == null || ze.getSize() > big.getSize()) big = ze;
                }
                return big == null ? null : String.format(Locale.US, "%08X", big.getCrc());
            } finally {
                z.close();
            }
        }
        if (f.length() > 64L * 1024 * 1024) return null;
        long skip = "nes".equals(ext(f)) ? 16 : (f.length() % 1024 == 512 ? 512 : 0);
        InputStream in = new FileInputStream(f);
        try {
            in.skip(skip);
            CRC32 c = new CRC32();
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) c.update(b, 0, n);
            return String.format(Locale.US, "%08X", c.getValue());
        } finally {
            in.close();
        }
    }
}
