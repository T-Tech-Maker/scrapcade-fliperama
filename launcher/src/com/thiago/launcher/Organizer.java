package com.thiago.launcher;

import android.os.Environment;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Caixa de entrada: tudo que cai em roms/novos vai sozinho para a pasta do console.
 * ROM solta ou .zip com uma ROM dentro: vai para o console pela extensao.
 * .zip de arcade: o MAME 0.37b5 acha cada arquivo pelo nome antigo, entao o zip e refeito com os nomes
 * que o clrmame.dat do proprio emulador pede, conferindo por CRC. Os dumps novos de Neo Geo tem as metades
 * dos sprites trocadas em relacao aos antigos, e isso tambem e desfeito aqui.
 */
final class Organizer {
    private static final class Rom {
        final String name, crc;
        Rom(String name, String crc) { this.name = name; this.crc = crc; }
    }

    /** De onde vem cada arquivo do zip novo: entrada do zip original, e se troca as metades. */
    private static final class Source {
        final ZipEntry entry;
        final boolean swap;
        Source(ZipEntry entry, boolean swap) { this.entry = entry; this.swap = swap; }
    }

    private Organizer() { }

    /** A pasta que aparece primeiro no PC, com o cabo USB ou com o cartao no leitor. */
    static File inbox() { return new File(Environment.getExternalStorageDirectory(), "Jogos novos"); }

    /** Tudo que esta na caixa de entrada, inclusive em subpastas: da para arrastar uma pasta inteira. */
    private static void collect(File d, int depth, List<File> out) {
        File[] fs = d.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) {
                if (depth < 3) collect(f, depth + 1, out);
            } else if (ready(f)) {
                out.add(f);
            }
        }
    }

    private static List<File> pending() {
        List<File> l = new ArrayList<File>();
        collect(inbox(), 0, l);
        collect(new File(Catalog.romsDir(), "novos"), 0, l);    // pasta antiga: quem ja usou nao perde nada
        return l;
    }

    static boolean hasWork() { return !pending().isEmpty(); }

    /** Windows e copias pela metade deixam arquivos que nao sao jogo. */
    private static boolean ready(File f) {
        String n = f.getName().toLowerCase(Locale.US);
        return f.isFile() && !n.endsWith(Envio.PARTIAL) && !n.startsWith(".") && !n.equals("desktop.ini") && !n.equals("thumbs.db")
                && settled(f);
    }

    /** Tamanho e hora em que cada arquivo foi visto pela primeira vez com esse tamanho. */
    private static final java.util.HashMap<String, long[]> seen = new java.util.HashMap<String, long[]>();

    /**
     * Arquivo ainda sendo copiado (adb, cabo, rede) nao se mexe: so entra depois de 10 s com o mesmo tamanho.
     * A hora do arquivo nao serve: o adb push e o Windows mantem a hora do original. Em 10/11/2026 as faixas
     * do Pepsiman foram movidas no meio da copia e, ainda pequenas, foram parar no Mega Drive.
     */
    private static synchronized boolean settled(File f) {
        long now = android.os.SystemClock.uptimeMillis(), len = f.length();
        long[] v = seen.get(f.getPath());
        if (v == null || v[0] != len) {
            seen.put(f.getPath(), new long[] {len, now});
            return false;
        }
        return now - v[1] >= 10000;
    }

    /** Algum .cue (na mesma pasta ou ja em roms/psx) cita esse arquivo: e faixa de CD. */
    private static boolean inSomeCue(File bin) {
        File[] dirs = {bin.getParentFile(), new File(Catalog.romsDir(), "psx")};
        String want = bin.getName().toLowerCase(Locale.US);
        for (File d : dirs) {
            File[] cues = d == null ? null : d.listFiles();
            if (cues == null) continue;
            for (File c : cues) {
                if (!c.getName().toLowerCase(Locale.US).endsWith(".cue") || c.length() > 64 * 1024) continue;
                try {
                    java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(c));
                    try {
                        String l;
                        while ((l = r.readLine()) != null) if (l.toLowerCase(Locale.US).contains(want)) return true;
                    } finally {
                        r.close();
                    }
                } catch (IOException ignored) { }
            }
        }
        return false;
    }

    private static void removeEmptyFolders(File d) {
        File[] fs = d.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) {
                removeEmptyFolders(f);
                String[] left = f.list();
                if (left != null && left.length == 0) f.delete();
            }
        }
    }

    private static String folder(String file) {
        String n = file.toLowerCase(Locale.US);
        if (n.endsWith(".gba")) return "gba";
        if (n.endsWith(".gb") || n.endsWith(".gbc")) return "gbc";
        if (n.endsWith(".nes")) return "nes";
        if (n.endsWith(".smc") || n.endsWith(".sfc")) return "snes";
        if (n.endsWith(".z64") || n.endsWith(".n64") || n.endsWith(".v64")) return "n64";
        if (n.endsWith(".md") || n.endsWith(".gen") || n.endsWith(".smd") || n.endsWith(".sms") || n.endsWith(".gg"))
            return "megadrive";
        if (n.endsWith(".wad")) return "doom";
        if (n.endsWith(".cue") || n.endsWith(".pbp") || n.endsWith(".img") || n.endsWith(".iso")) return "psx";
        return null;
    }

    // ------------------------------------------------------------ pen drive

    /** O vold deste aparelho monta o pen drive aqui sozinho (dev_mount usb em /system/etc/vold.fstab). */
    private static final File USB = new File("/mnt/usb");
    private static final String[] GAME_EXTS = {".zip", ".gba", ".gb", ".gbc", ".nes", ".smc", ".sfc", ".md", ".gen", ".smd",
            ".sms", ".gg", ".wad", ".cue", ".bin", ".iso", ".pbp", ".img", ".z64", ".n64", ".v64"};

    interface Progress { void at(String msg); }

    private static boolean isGame(String file) {
        String n = file.toLowerCase(Locale.US);
        if (n.startsWith(".")) return false;
        for (String e : GAME_EXTS) if (n.endsWith(e)) return true;
        return false;
    }

    /**
     * Pastas de sistema que nunca tem jogo. Em 11/11/2026 um pen drive com o instalador do Windows trouxe
     * bootfix.bin, efisys.bin e outros .bin de boot/efi/sources, que entraram no Mega Drive como cartuchos.
     */
    private static final java.util.HashSet<String> SKIP_USB_DIRS = new java.util.HashSet<String>(java.util.Arrays.asList(
            "lost.dir", "system volume information", "android", "$recycle.bin", "recycler",
            "boot", "efi", "sources", "support", "windows", "program files", "program files (x86)", "dcim"));

    private static void scanUsb(File d, int depth, List<File> out) {
        File[] fs = d.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) {
                String n = f.getName();
                if (depth < 3 && !n.startsWith(".") && !SKIP_USB_DIRS.contains(n.toLowerCase(Locale.US))) scanUsb(f, depth + 1, out);
            } else if (isGame(f.getName())) {
                out.add(f);
            }
        }
    }

    private static File usbMemo() { return new File(Catalog.romsDir(), ".importados"); }

    private static java.util.HashSet<String> readUsbMemo() {
        java.util.HashSet<String> m = new java.util.HashSet<String>();
        try {
            BufferedReader r = new BufferedReader(new FileReader(usbMemo()));
            try {
                String l;
                while ((l = r.readLine()) != null) m.add(l);
            } finally {
                r.close();
            }
        } catch (IOException ignored) {
            // ainda nao importou nada
        }
        return m;
    }

    private static List<File> usbPending() {
        List<File> all = new ArrayList<File>(), todo = new ArrayList<File>();
        scanUsb(USB, 0, all);
        if (all.isEmpty()) return todo;
        java.util.HashSet<String> memo = readUsbMemo();
        for (File f : all) if (!memo.contains(f.getName() + "|" + f.length())) todo.add(f);
        return todo;
    }

    /** Ha jogo no pen drive que ainda nao veio? Rapido: o fliperama pergunta sempre que recarrega. */
    static boolean usbHasNew() { return !usbPending().isEmpty(); }

    /** Copia para roms/novos o que ainda nao veio deste pen drive. So le dele: pode tirar depois sem risco. */
    static String importUsb(Progress p) {
        List<File> todo = usbPending();
        if (todo.isEmpty()) return null;
        File dir = inbox();
        dir.mkdirs();
        int done = 0;
        for (File f : todo) {
            p.at("Copiando do pen drive: " + (done + 1) + " de " + todo.size() + "\n" + f.getName());
            File tmp = new File(dir, f.getName() + Envio.PARTIAL);
            try {
                InputStream in = new java.io.FileInputStream(f);
                OutputStream out = new FileOutputStream(tmp);
                try {
                    copy(in, out, Long.MAX_VALUE);
                } finally {
                    out.close();
                    in.close();
                }
                if (tmp.length() != f.length()) throw new IOException("copia incompleta");
                if (!tmp.renameTo(new File(dir, f.getName()))) throw new IOException("nao gravou");
                java.io.FileWriter w = new java.io.FileWriter(usbMemo(), true);
                try {
                    w.write(f.getName() + "|" + f.length() + "\n");
                } finally {
                    w.close();
                }
                done++;
            } catch (IOException e) {
                tmp.delete();     // pen drive saiu no meio ou cartao cheio: o que faltou vem na proxima vez
                return done + " de " + todo.size() + " copiados. Confira o espaço no cartão e o pen drive.";
            }
        }
        return (done == 1 ? "1 arquivo copiado" : done + " arquivos copiados") + " do pen drive. Pode tirar.";
    }

    /** Organiza tudo. Devolve o aviso para mostrar na tela, ou null se nada aconteceu. */
    static String run() {
        List<File> fs = pending();
        if (fs.isEmpty()) return null;
        int ok = 0;
        List<String> tooNew = new ArrayList<String>(), failed = new ArrayList<String>();
        for (File f : fs) {
            String n = f.getName();
            try {
                if (n.toLowerCase(Locale.US).endsWith(".zip")) {
                    if (fromZip(f, tooNew)) ok++;
                } else if (n.toLowerCase(Locale.US).endsWith(".bin")) {
                    // .bin de CD (com .cue junto ou grande) ou cartucho de Mega Drive
                    boolean cd = f.length() > 16L * 1024 * 1024 || new File(f.getParent(), n.substring(0, n.length() - 4) + ".cue").isFile()
                            || inSomeCue(f);
                    move(f, cd ? "psx" : "megadrive");
                    if (!cd) ok++;
                } else if (folder(n) != null) {
                    move(f, folder(n));
                    ok++;
                } else {
                    failed.add(n);
                    moveTo(f, "nao_reconhecidos");
                }
            } catch (Exception e) {
                failed.add(n);
            }
        }
        removeEmptyFolders(inbox());
        StringBuilder s = new StringBuilder();
        if (ok > 0) s.append(ok == 1 ? "1 jogo novo organizado" : ok + " jogos novos organizados");
        if (!tooNew.isEmpty()) s.append(s.length() > 0 ? "\n" : "").append("Não rodam no arcade daqui: ").append(join(tooNew));
        if (!failed.isEmpty()) s.append(s.length() > 0 ? "\n" : "").append("Não reconhecidos (use .zip): ").append(join(failed));
        return s.length() > 0 ? s.toString() : null;
    }

    private static String join(List<String> l) {
        StringBuilder s = new StringBuilder();
        for (String x : l) s.append(s.length() > 0 ? ", " : "").append(x);
        return s.toString();
    }

    private static void move(File f, String dir) throws IOException { moveTo(f, dir); }

    /** "Sonic (1).md", do navegador baixando de novo, e o mesmo jogo: vira "Sonic.md", e nao entra repetido. */
    private static String noCopySuffix(String name) { return name.replaceFirst(" \\(\\d+\\)(\\.[^.]+)$", "$1"); }

    private static void moveTo(File f, String dir) throws IOException {
        File d = new File(Catalog.romsDir(), dir);
        d.mkdirs();
        File to = new File(d, noCopySuffix(f.getName()));
        if (to.exists() && !to.delete()) throw new IOException("ja existe");
        if (!f.renameTo(to)) throw new IOException("nao moveu");
    }

    private static boolean fbAlpha() { return new File("/data/local/cores/fbalpha2012_neogeo.so").isFile(); }

    /** FB Alpha completo, para o arcade que nao e Neo Geo. Sem ele, cai na conversao para o MAME 0.37b5 abaixo. */
    private static boolean fbArcade() { return new File("/data/local/cores/fbalpha2012.so").isFile(); }

    /** Conjunto de Neo Geo: tem as ROMs de sprites (c1) e de som (m1). */
    private static boolean isNeoGeo(ZipFile z) {
        boolean c1 = false, m1 = false;
        for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
            String n = e.nextElement().getName().toLowerCase(Locale.US);
            if (n.endsWith(".c1") || n.contains("-c1.")) c1 = true;
            if (n.endsWith(".m1") || n.contains("-m1.")) m1 = true;
        }
        return c1 && m1;
    }

    private static boolean isBios(String file) {
        String n = file.toLowerCase(Locale.US);
        return n.equals("000-lo.lo") || n.equals("sfix.sfix") || n.equals("sm1.sm1") || n.endsWith(".sp1")
                || n.startsWith("uni-bios") || n.startsWith("sp-1v1") || n.equals("vs-bios.rom") || n.equals("asia-s3.rom")
                || n.equals("japan-j3.bin") || n.equals("v2.bin") || n.equals("sp1.jipan.1024");
    }

    /** Muitos zips atuais trazem a BIOS junto: separa uma vez em roms/neogeo/neogeo.zip, que os jogos de Neo Geo pedem. */
    private static void neoBios(ZipFile z) throws IOException {
        File dest = new File(new File(Catalog.romsDir(), "neogeo"), "neogeo.zip");
        if (dest.exists()) return;
        Map<String, Source> b = new HashMap<String, Source>();
        for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
            ZipEntry ze = e.nextElement();
            if (!ze.isDirectory() && isBios(ze.getName())) b.put(ze.getName(), new Source(ze, false));
        }
        if (b.containsKey("sfix.sfix") && b.containsKey("000-lo.lo")) {
            dest.getParentFile().mkdirs();
            write(z, b, dest);
        }
    }

    /** true = um jogo novo no menu. */
    private static boolean fromZip(File f, List<String> tooNew) throws IOException {
        ZipFile z = new ZipFile(f);
        boolean done = false, neo = false;
        String asIs = null;
        try {
            if (fbAlpha() && isNeoGeo(z)) {
                neoBios(z);
                neo = true;
            }
        } finally {
            if (neo) z.close();
        }
        if (neo) {
            // o FB Alpha acha o jogo pelo nome do zip: "mslug (1).zip" tem de virar "mslug.zip"
            File d = new File(Catalog.romsDir(), "neogeo");
            d.mkdirs();
            File to = new File(d, gameName(f.getName()) + ".zip");
            if (to.exists() && !to.delete()) throw new IOException("ja existe");
            if (!f.renameTo(to)) throw new IOException("nao moveu");
            return true;
        }
        try {
            List<ZipEntry> roms = new ArrayList<ZipEntry>();
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                ZipEntry ze = e.nextElement();
                if (!ze.isDirectory() && folder(ze.getName()) != null) roms.add(ze);
            }
            if (!roms.isEmpty()) {
                // CD: leva o .cue e os .bin juntos. Cartucho: so a ROM (a maior, se vierem varias versoes)
                ZipEntry big = roms.get(0);
                boolean cd = false;
                for (ZipEntry ze : roms) {
                    if (ze.getSize() > big.getSize()) big = ze;
                    if (ze.getName().toLowerCase(Locale.US).endsWith(".cue")) cd = true;
                }
                if (cd) {
                    for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                        ZipEntry ze = e.nextElement();
                        if (!ze.isDirectory()) extract(z, ze, "psx");
                    }
                } else {
                    extract(z, big, folder(big.getName()));
                }
                done = true;
            } else if (fbArcade()) {
                // FB Alpha completo: o zip vai como veio, so com o nome limpo; o jogo precisa existir na lista dele
                String name = gameName(f.getName());
                if (Catalog.neoName(name) == null) tooNew.add(name);
                else asIs = name;
            } else {
                String g = arcade(z, gameName(f.getName()));
                if (g == null) tooNew.add(gameName(f.getName()));
                else done = true;
            }
        } finally {
            z.close();
        }
        if (asIs != null) {
            File d = new File(Catalog.romsDir(), "arcade/roms");
            d.mkdirs();
            File to = new File(d, asIs + ".zip");
            if (to.exists() && !to.delete()) throw new IOException("ja existe");
            if (!f.renameTo(to)) throw new IOException("nao moveu");
            return true;
        }
        if (done) f.delete();
        else moveTo(f, "nao_roda");
        return done;
    }

    private static String gameName(String file) {
        String n = file.toLowerCase(Locale.US);
        if (n.endsWith(".zip")) n = n.substring(0, n.length() - 4);
        int cut = n.indexOf(" (");
        return (cut > 0 ? n.substring(0, cut) : n).trim();
    }

    private static void extract(ZipFile z, ZipEntry ze, String dir) throws IOException {
        File d = new File(Catalog.romsDir(), dir);
        d.mkdirs();
        String name = new File(ze.getName().replace('\\', '/')).getName();
        InputStream in = z.getInputStream(ze);
        OutputStream out = new FileOutputStream(new File(d, name));
        try {
            copy(in, out, Long.MAX_VALUE);
        } finally {
            out.close();
            in.close();
        }
    }

    private static void copy(InputStream in, OutputStream out, long max) throws IOException {
        byte[] b = new byte[65536];
        long left = max;
        while (left > 0) {
            int n = in.read(b, 0, (int) Math.min(b.length, left));
            if (n <= 0) break;
            out.write(b, 0, n);
            left -= n;
        }
    }

    private static Map<String, List<Rom>> readDat() throws IOException {
        Map<String, List<Rom>> dat = new HashMap<String, List<Rom>>();
        BufferedReader r = new BufferedReader(new FileReader(new File(Catalog.romsDir(), "arcade/clrmame.dat")));
        try {
            String l;
            List<Rom> cur = null;
            while ((l = r.readLine()) != null) {
                l = l.trim();
                if (l.startsWith("name ") && cur == null) {
                    cur = new ArrayList<Rom>();
                    dat.put(l.substring(5).trim().toLowerCase(Locale.US), cur);
                } else if (l.startsWith("rom (") && cur != null) {
                    String[] t = l.split("\\s+");
                    String name = null, crc = null;
                    for (int i = 0; i + 1 < t.length; i++) {
                        if (t[i].equals("name")) name = t[i + 1];
                        else if (t[i].equals("crc")) crc = t[i + 1].toLowerCase(Locale.US);
                    }
                    if (name != null && crc != null) cur.add(new Rom(name, crc));
                } else if (l.equals(")")) {
                    cur = null;
                }
            }
        } finally {
            r.close();
        }
        return dat;
    }

    private static String hex(long v) { return String.format(Locale.US, "%08x", v); }

    /** CRC da entrada com as duas metades trocadas, sem carregar tudo na memoria. */
    private static String swappedCrc(ZipFile z, ZipEntry ze) throws IOException {
        CRC32 c = new CRC32();
        long half = ze.getSize() / 2;
        update(c, z, ze, half, ze.getSize() - half);
        update(c, z, ze, 0, half);
        return hex(c.getValue());
    }

    private static void update(CRC32 c, ZipFile z, ZipEntry ze, long from, long len) throws IOException {
        InputStream in = z.getInputStream(ze);
        try {
            skip(in, from);
            byte[] b = new byte[65536];
            long left = len;
            while (left > 0) {
                int n = in.read(b, 0, (int) Math.min(b.length, left));
                if (n <= 0) break;
                c.update(b, 0, n);
                left -= n;
            }
        } finally {
            in.close();
        }
    }

    private static void skip(InputStream in, long n) throws IOException {
        byte[] b = new byte[65536];
        while (n > 0) {
            int r = in.read(b, 0, (int) Math.min(b.length, n));
            if (r <= 0) break;
            n -= r;
        }
    }

    /** Refaz o zip para o MAME. Devolve o nome do jogo no MAME, ou null se esse conjunto nao serve. */
    private static String arcade(ZipFile z, String base) throws IOException {
        Map<String, List<Rom>> dat = readDat();
        List<Rom> game = dat.get(base);
        if (game == null) return null;
        Map<String, ZipEntry> byCrc = new HashMap<String, ZipEntry>();
        Map<String, ZipEntry> byName = new HashMap<String, ZipEntry>();
        List<ZipEntry> all = new ArrayList<ZipEntry>();
        for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
            ZipEntry ze = e.nextElement();
            if (ze.isDirectory()) continue;
            byCrc.put(hex(ze.getCrc()), ze);
            byName.put(ze.getName().toLowerCase(Locale.US), ze);
            all.add(ze);
        }
        Map<String, Source> out = new HashMap<String, Source>();
        List<Rom> missing = new ArrayList<Rom>();
        for (Rom r : game) {
            ZipEntry ze = byCrc.get(r.crc);
            if (ze != null) out.put(r.name, new Source(ze, false));
            else missing.add(r);
        }
        // Dump novo com as metades trocadas: so vale testar entradas que ninguem usou
        if (!missing.isEmpty() && missing.size() <= 16) {
            Map<String, ZipEntry> swapped = new HashMap<String, ZipEntry>();
            for (ZipEntry ze : all) {
                if (byCrc.get(hex(ze.getCrc())) == ze && !used(out, ze) && ze.getSize() >= 1024 * 1024)
                    swapped.put(swappedCrc(z, ze), ze);
            }
            for (int i = missing.size() - 1; i >= 0; i--) {
                ZipEntry ze = swapped.get(missing.get(i).crc);
                if (ze != null) {
                    out.put(missing.get(i).name, new Source(ze, true));
                    missing.remove(i);
                }
            }
        }
        String target = base;
        if (!missing.isEmpty()) {
            // Outra versao do mesmo jogo: um clone que so lista o que muda (tmnt do zip = tmntj aqui)
            target = null;
            String prefix = base.substring(0, Math.min(3, base.length()));
            for (Map.Entry<String, List<Rom>> g : dat.entrySet()) {
                if (g.getKey().equals(base) || !g.getKey().startsWith(prefix)) continue;
                List<Rom> roms = g.getValue();
                if (roms.size() < missing.size()) continue;
                boolean allHere = true;
                for (Rom r : roms) if (!byCrc.containsKey(r.crc)) allHere = false;
                if (allHere) {
                    target = g.getKey();
                    for (Rom r : roms) out.put(r.name, new Source(byCrc.get(r.crc), false));
                    break;
                }
            }
            if (target == null) return null;
        }
        File dir = new File(Catalog.romsDir(), "arcade/roms");
        dir.mkdirs();
        write(z, out, new File(dir, target + ".zip"));
        // Alguns zips novos trazem a BIOS de Neo Geo junto, que os jogos de Neo Geo pedem
        List<Rom> bios = dat.get("neogeo");
        File biosZip = new File(dir, "neogeo.zip");
        if (bios != null && !biosZip.exists()) {
            Map<String, Source> b = new HashMap<String, Source>();
            for (Rom r : bios) {
                ZipEntry ze = byCrc.get(r.crc);
                if (ze == null && r.name.equals("ng-sfix.rom")) ze = byName.get("sfix.sfix");
                if (ze == null && r.name.equals("ng-sm1.rom")) ze = byName.get("sm1.sm1");
                if (ze != null) b.put(r.name, new Source(ze, false));
            }
            if (b.size() == bios.size()) write(z, b, biosZip);
        }
        return target;
    }

    private static boolean used(Map<String, Source> out, ZipEntry ze) {
        for (Source s : out.values()) if (s.entry == ze) return true;
        return false;
    }

    private static void write(ZipFile z, Map<String, Source> files, File dest) throws IOException {
        File tmp = new File(dest.getParent(), dest.getName() + Envio.PARTIAL);
        ZipOutputStream o = new ZipOutputStream(new FileOutputStream(tmp));
        try {
            o.setLevel(1);
            for (Map.Entry<String, Source> f : files.entrySet()) {
                o.putNextEntry(new ZipEntry(f.getKey()));
                ZipEntry ze = f.getValue().entry;
                if (f.getValue().swap) {
                    long half = ze.getSize() / 2;
                    InputStream in = z.getInputStream(ze);
                    try {
                        skip(in, half);
                        copy(in, o, ze.getSize() - half);
                    } finally {
                        in.close();
                    }
                    in = z.getInputStream(ze);
                    try {
                        copy(in, o, half);
                    } finally {
                        in.close();
                    }
                } else {
                    InputStream in = z.getInputStream(ze);
                    try {
                        copy(in, o, Long.MAX_VALUE);
                    } finally {
                        in.close();
                    }
                }
                o.closeEntry();
            }
        } finally {
            o.close();
        }
        if (dest.exists()) dest.delete();
        if (!tmp.renameTo(dest)) throw new IOException("nao gravou");
    }
}
