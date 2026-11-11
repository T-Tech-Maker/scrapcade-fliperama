package com.thiago.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Environment;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Sistemas do fliperama, ROMs e emuladores.
 * ROMs: /sdcard/roms/<sistema>/... ou soltas em /sdcard/roms (pela extensao).
 * A tela de capas mostra os jogos de todos os sistemas juntos; o sistema vira filtro.
 */
final class Catalog {
    private static final String XASH_PKG = "in.celest.xash3d.hl";
    /** O Half-Life nao e um arquivo: a pasta com os dados do jogo faz o papel de ROM. */
    private static final File XASH_DIR = new File(Environment.getExternalStorageDirectory(), "xash/valve");

    static boolean isXash(File f) { return XASH_DIR.equals(f); }

    /** Um jogo na tela de capas. */
    static final class Game {
        final Sys sys;
        final File rom;
        final String title;
        Game(Sys sys, File rom) { this.sys = sys; this.rom = rom; this.title = title(rom); }
        String key() { return rom.getAbsolutePath(); }
        String emuLabel() {
            if (isXash(rom)) return "Xash3D";
            return sys.emuLabel != null ? sys.emuLabel : "sem emulador";
        }
    }

    static final class Emu {
        final String pkg, cls, mime;
        final String core;   // nucleo do RetroArch: "psx" (vem no app) ou um arquivo de /data/local/cores
        Emu(String pkg, String cls, String mime) { this(pkg, cls, mime, "psx"); }
        Emu(String pkg, String cls, String mime, String core) { this.pkg = pkg; this.cls = cls; this.mime = mime; this.core = core; }
    }

    static final class Sys {
        final int color, icon;
        final String id, logo, name;
        final String[] exts, dirs;
        final Emu[] emus;
        final List<File> roms = new ArrayList<File>();
        Emu installed;
        String emuLabel;

        Sys(String id, String logo, String name, int color, int icon, String[] exts, String[] dirs, Emu... emus) {
            this.id = id; this.logo = logo; this.name = name;
            this.color = color; this.icon = icon; this.exts = exts; this.dirs = dirs; this.emus = emus;
        }

        boolean accepts(String fileName) {
            String n = fileName.toLowerCase(Locale.US);
            for (String e : exts) if (n.endsWith(e)) return true;
            return false;
        }
    }

    static final String N64_PKG = "paulscode.android.mupen64plusae";

    private Catalog() { }

    private static String[] a(String... s) { return s; }

    private static List<Sys> all() {
        List<Sys> l = new ArrayList<Sys>();
        l.add(new Sys("gba", "GBA", "Game Boy Advance", 0xFF6C47C9, Art.ICON_WIDE,
                a(".gba"), a("gba"),
                new Emu("com.fastemulator.gbafree", "com.fastemulator.gba.EmulatorActivity", "application/x-gba-rom"),
                new Emu("com.fastemulator.gba", "com.fastemulator.gba.EmulatorActivity", "application/x-gba-rom")));
        l.add(new Sys("gbc", "GBC", "Game Boy Color", 0xFF2E9E62, Art.ICON_TALL,
                a(".gb", ".gbc"), a("gbc", "gb"),
                new Emu("com.androidemu.gbc", "com.androidemu.gbc.EmulatorActivity", "application/octet-stream"),
                new Emu("com.fastemulator.gbcfree", null, null),
                new Emu("com.fastemulator.gbc", null, null)));
        l.add(new Sys("nes", "NES", "Nintendinho", 0xFFC93636, Art.ICON_PAD,
                a(".nes"), a("nes", "famicom"),
                // QuickNES: o Nesoid deu tela preta com a ROM de teste, que funciona nos nucleos do RetroArch
                new Emu("org.retroarch", "org.retroarch.browser.RetroActivity", null, "quicknes"),
                new Emu("com.androidemu.nes", "com.androidemu.nes.EmulatorActivity", "application/octet-stream"),
                new Emu("com.nostalgiaemulators.neslite", null, null),
                new Emu("com.nostalgiaemulators.nesfull", null, null)));
        l.add(new Sys("snes", "SNES", "Super Nintendo", 0xFF5560C2, Art.ICON_PAD,
                a(".smc", ".sfc"), a("snes", "supernintendo"),
                new Emu("org.retroarch", "org.retroarch.browser.RetroActivity", null, "snes9x2005"),
                new Emu("com.explusalpha.Snes9xPlus", null, null),
                new Emu("com.bubblezapgames.supergnes_lite", null, null),
                new Emu("com.bubblezapgames.supergnes", null, null),
                new Emu("com.androidemu.snes", null, null)));
        // Nintendo 64 pelo Mupen64Plus AE 2.4.4 (F-Droid, o ultimo que roda no Android 2.3). Quem abre e o joyd:
        // grava o jogo nas preferencias do emulador e abre direto a tela do jogo (joypad/n64.sh)
        l.add(new Sys("n64", "N64", "Nintendo 64", 0xFF1E9C4A, Art.ICON_PAD,
                a(".z64", ".n64", ".v64"), a("n64", "nintendo64"),
                new Emu(N64_PKG, N64_PKG + ".MainActivity", null)));
        l.add(new Sys("md", "MEGA", "Mega Drive", 0xFF2B68C8, Art.ICON_PAD,
                a(".md", ".gen", ".smd", ".bin", ".sms", ".gg"), a("megadrive", "genesis", "md"),
                new Emu("org.retroarch", "org.retroarch.browser.RetroActivity", null, "picodrive"),
                new Emu("com.explusalpha.MdEmu", null, null),
                new Emu("com.androidemu.gens", null, null)));
        l.add(new Sys("psx", "PS1", "PlayStation", 0xFF7D8494, Art.ICON_PAD,
                a(".cue", ".pbp", ".iso", ".img"), a("psx", "ps1", "playstation"),
                new Emu("org.retroarch", "org.retroarch.browser.RetroActivity", null),
                new Emu("com.emulator.fpse", null, null),
                new Emu("com.epsxe.ePSXe", null, null)));
        // Jogos de computador: .wad (Doom e parecidos) pelo PrBoom. O Half-Life entra em load(), e abre pelo Xash3D.
        l.add(new Sys("pc", "PC", "Jogos de computador", 0xFF8C2F2F, Art.ICON_PC,
                a(".wad"), a("doom", "pc"),
                new Emu("org.retroarch", "org.retroarch.browser.RetroActivity", null, "prboom")));
        // Arcade pelo FB Alpha 2012 completo (conjuntos atuais, abre direto). Desde 10/11 substitui o MAME4droid:
        // o Android 2.3 reextraia os 12 MB de bibliotecas dele a cada boot (9 s).
        l.add(new Sys("arcade", "ARCADE", "Fliperama", 0xFFD9822B,
                Art.ICON_PAD, a(), a("arcade", "mame"),
                new Emu("org.retroarch", "org.retroarch.browser.RetroActivity", null, "fbalpha2012"),
                new Emu("com.seleuco.mame4droid", null, null),
                new Emu("com.seleuco.mame4all", null, null)));
        // Abre direto e aceita os conjuntos atuais, com a BIOS em roms/neogeo/neogeo.zip
        l.add(new Sys("neogeo", "NEO GEO", "Neo Geo", 0xFF2F7DD1, Art.ICON_PAD, a(), a("neogeo"),
                new Emu("org.retroarch", "org.retroarch.browser.RetroActivity", null, "fbalpha2012_neogeo")));
        return l;
    }

    /** Capa do jogo: images/<rom>.png (ou -image/-thumb, .jpg), igual as pastas do Batocera. */
    static File cover(File rom) {
        String n = rom.getName();
        int dot = n.lastIndexOf('.');
        String base = dot > 0 ? n.substring(0, dot) : n;
        File dir = rom.getParentFile();
        String[] subs = {"images", "media", "covers"};
        String[] suf = {".png", "-image.png", "-thumb.png", ".jpg", "-image.jpg", "-thumb.jpg"};
        for (String sd : subs) {
            File sdir = new File(dir, sd);
            if (!sdir.isDirectory()) continue;
            for (String sf : suf) {
                File f = new File(sdir, base + sf);
                if (f.isFile()) return f;
            }
        }
        return null;
    }

    /**
     * Imagem de fundo do jogo (uma cena dele): images/<rom>-fundo.png, que o proprio menu baixa,
     * ou <rom>-fanart, o nome que o Batocera e o Skraper usam.
     */
    static File backdrop(File rom) {
        String n = rom.getName();
        int dot = n.lastIndexOf('.');
        String base = dot > 0 ? n.substring(0, dot) : n;
        File dir = rom.getParentFile();
        String[] subs = {"images", "media"};
        String[] suf = {"-fundo.png", "-fundo.jpg", "-fanart.png", "-fanart.jpg"};
        for (String sd : subs) {
            File sdir = new File(dir, sd);
            if (!sdir.isDirectory()) continue;
            for (String sf : suf) {
                File f = new File(sdir, base + sf);
                if (f.isFile()) return f;
            }
        }
        return null;
    }

    static File romsDir() { return new File(Environment.getExternalStorageDirectory(), "roms"); }

    /** Pede ao joyd (root) que execute um comando: o app nao e root. Ver check_cmd() no joyd.c */
    static void sendCmd(String cmd) {
        try {
            File dir = romsDir();
            File tmp = new File(dir, ".joyd_cmd.tmp");
            java.io.FileWriter w = new java.io.FileWriter(tmp);
            w.write(cmd + "\n");
            w.close();
            tmp.renameTo(new File(dir, ".joyd_cmd"));
        } catch (Exception ignored) { }
    }

    /** Varre ROMs e emuladores e devolve os sistemas que tem jogo. */
    static List<Sys> load(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        List<Sys> sys = all();
        // deixa pronta a pasta de cada console, para quem for colocar jogos pelo PC
        if (romsDir().isDirectory()) {
            for (Sys s : sys) new File(romsDir(), s.dirs[0]).mkdirs();
            new File(romsDir(), "arcade/roms").mkdirs();
            Organizer.inbox().mkdirs();
        }
        File[] top = romsDir().listFiles();
        if (top != null) {
            for (File f : top) {
                if (f.isDirectory()) {
                    Sys s = byDir(sys, f.getName());
                    if (s != null) addDir(s, f, 0);
                } else {
                    for (Sys s : sys) if (s.accepts(f.getName())) { s.roms.add(f); break; }
                }
            }
        }
        if (new File(XASH_DIR, "liblist.gam").isFile() && installed(pm, XASH_PKG)) {
            for (Sys s : sys) if ("pc".equals(s.id)) s.roms.add(XASH_DIR);
        }
        List<Sys> out = new ArrayList<Sys>();
        for (Sys s : sys) {
            for (Emu e : s.emus) {
                try {
                    s.emuLabel = pm.getApplicationLabel(pm.getApplicationInfo(e.pkg, 0)).toString();
                    s.installed = e;
                    break;
                } catch (Exception ignored) { }
            }
            if (!s.roms.isEmpty()) out.add(s);
        }
        return out;
    }

    private static boolean installed(PackageManager pm, String pkg) {
        try {
            pm.getApplicationInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Todos os jogos dos sistemas dados, em ordem de nome. */
    static List<Game> games(List<Sys> sys) {
        List<Game> l = new ArrayList<Game>();
        for (Sys s : sys) for (File f : s.roms) l.add(new Game(s, f));
        Collections.sort(l, new Comparator<Game>() {
            public int compare(Game x, Game y) { return x.title.compareToIgnoreCase(y.title); }
        });
        return l;
    }

    // Pastas que os emuladores criam dentro da pasta de jogos: um .zip ali nao e jogo (samples, artwork...)
    private static final String[] NOT_GAMES = {"images", "media", "covers", "saves", "samples", "artwork", "frontend",
            "cfg", "hi", "inp", "memcard", "nvram", "skins", "snap"};

    private static void addDir(Sys s, File d, int depth) {
        File[] fs = d.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String n = f.getName().toLowerCase(Locale.US);
            if (f.isDirectory()) {
                if (depth < 1 && !java.util.Arrays.asList(NOT_GAMES).contains(n)) addDir(s, f, depth + 1);
            } else if ((s.accepts(n) || n.endsWith(".zip")) && !n.equals("neogeo.zip")) {   // neogeo.zip e a BIOS
                s.roms.add(f);
            }
        }
    }

    private static Sys byDir(List<Sys> l, String name) {
        String n = name.toLowerCase(Locale.US);
        for (Sys s : l) for (String d : s.dirs) if (d.equals(n)) return s;
        return null;
    }

    private static java.util.HashMap<String, String> arcadeNames;

    /**
     * Nome real do jogo de arcade: "robby" -> "Robby Roto". Vem de arcade/gamelist.txt, que o proprio
     * MAME4droid cria ao abrir a primeira vez. Linha: nome   "Descricao". Sem o arquivo, fica o nome da ROM.
     */
    static String arcadeName(String rom) {
        if (arcadeNames == null) {
            java.util.HashMap<String, String> m = new java.util.HashMap<String, String>();
            java.io.BufferedReader r = null;
            try {
                r = new java.io.BufferedReader(new java.io.FileReader(new File(romsDir(), "arcade/gamelist.txt")));
                String l;
                while ((l = r.readLine()) != null) {
                    int a = l.indexOf('"'), b = l.lastIndexOf('"'), sp = l.indexOf(' ');
                    if (sp > 0 && a > sp && b > a) m.put(l.substring(0, sp).toLowerCase(Locale.US), l.substring(a + 1, b));
                }
            } catch (Exception ignored) {
            } finally {
                try { if (r != null) r.close(); } catch (Exception ignored) { }
            }
            // so guarda se leu: o cartao pode nao ter montado ainda, e o fliperama recarrega a lista quando monta
            if (m.isEmpty()) return null;
            arcadeNames = m;
        }
        return arcadeNames.get(rom.toLowerCase(Locale.US));
    }

    private static final java.util.HashMap<String, String> neoNames = new java.util.HashMap<String, String>();
    private static final java.util.HashMap<String, String[]> neoExtras = new java.util.HashMap<String, String[]>();

    /** {ano, empresa} do jogo de arcade ou Neo Geo, ou null se o conjunto nao esta no indice. */
    static synchronized String[] neoExtra(String zipBase) {
        neoName(zipBase);
        return neoExtras.get(zipBase.toLowerCase(Locale.US));
    }

    /** Nome oficial do jogo de Neo Geo pelo nome do zip (assets/fbneo.idx). Le o indice uma vez por jogo novo. */
    static synchronized String neoName(String zipBase) {
        String key = zipBase.toLowerCase(Locale.US);
        if (neoNames.containsKey(key)) return neoNames.get(key);
        neoNames.put(key, null);
        java.util.HashSet<String> want = new java.util.HashSet<String>();
        want.add(key);
        String[] here = new File(romsDir(), "neogeo").list();
        if (here != null) for (String z : here) {
            String lower = z.toLowerCase(Locale.US);
            if (lower.endsWith(".zip")) want.add(lower.substring(0, lower.length() - 4));
        }
        java.io.BufferedReader r = null;
        try {
            r = new java.io.BufferedReader(new java.io.InputStreamReader(new java.util.zip.GZIPInputStream(
                    Theme.ctx.getAssets().open("fbneo.idx")), "UTF-8"));
            String l;
            while ((l = r.readLine()) != null) {
                String[] f = l.split("\t", -1);      // zip, nome, ano, empresa
                if (f.length < 2 || !want.contains(f[0])) continue;
                neoNames.put(f[0], f[1]);
                neoExtras.put(f[0], new String[] {f.length > 2 ? f[2] : "", f.length > 3 ? f[3] : ""});
            }
        } catch (Exception ignored) {
        } finally {
            try { if (r != null) r.close(); } catch (Exception ignored) { }
        }
        return neoNames.get(key);
    }

    static String title(File f) {
        if (isXash(f)) return "Half-Life";
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        if (n.equalsIgnoreCase("freedoom1")) return "Freedoom: Phase 1";
        if (n.equalsIgnoreCase("freedoom2")) return "Freedoom: Phase 2";
        File parent = f.getParentFile();
        if (parent != null && "neogeo".equals(parent.getName())) {
            String real = neoName(n);
            if (real != null) n = real;
        }
        if (parent != null && parent.getPath().contains("/arcade")) {
            String real = neoName(n);
            if (real == null) real = arcadeName(n);
            if (real != null) n = real;
        }
        n = n.replace('_', ' ').trim();
        // "Crash Bandicoot (Europe, Australia)" -> "Crash Bandicoot"
        int tag = n.indexOf(" (");
        int tag2 = n.indexOf(" [");
        if (tag2 > 0 && (tag < 0 || tag2 < tag)) tag = tag2;
        if (tag > 0) n = n.substring(0, tag).trim();
        // "Legend of Zelda, The - Link's Awakening" -> "The Legend of Zelda - Link's Awakening"
        n = n.replaceFirst("^(.*?), The( - .*)?$", "The $1$2").replace("null", "");
        if (n.length() > 0) n = Character.toUpperCase(n.charAt(0)) + n.substring(1);
        return n;
    }

    static String size(long b) {
        if (b >= 1024L * 1024L) return String.format(Locale.US, "%.1f MB", b / 1048576.0);
        return String.format(Locale.US, "%d KB", (b + 1023) / 1024);
    }

    /** true enquanto o RetroArch ou o Xash3D esta aberto: o joyd fica em "modo jogo" (B = circulo, LT = L2). */
    static boolean gameMode;

    /**
     * RetroArch 0.9.9.6: a tela do jogo e privada, entao quem abre e o joyd (root).
     * Teclas: codigos Android; BUTTON_Z(101) e BUTTON_L2(104) vem do modo jogo do joyd.
     */
    static long launchedAt;
    private static String launchedPkg = "org.retroarch";

    /** Voltou ao menu pouco depois de abrir e o emulador nem esta mais rodando: o jogo nao abriu (arquivo com defeito). */
    static boolean gameFailed(Context ctx) {
        if (android.os.SystemClock.uptimeMillis() - launchedAt > 20000) return false;
        android.app.ActivityManager am = (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        List<android.app.ActivityManager.RunningAppProcessInfo> ps = am.getRunningAppProcesses();
        if (ps != null) for (android.app.ActivityManager.RunningAppProcessInfo p : ps)
            if (launchedPkg.equals(p.processName)) return false;
        return true;
    }

    private static String launchRetroArch(Context ctx, Sys s, Emu e, File rom) {
        try {
            // O FB Alpha le o proprio zip; os outros nucleos precisam do jogo solto
            if (rom.getName().toLowerCase(Locale.US).endsWith(".zip") && !e.core.startsWith("fbalpha")) {
                rom = unzipRom(s, rom);
                if (rom == null) return "Esse .zip não tem um jogo de " + s.name + ", ou é grande demais. Descompacte no PC.";
            }
            String path = rom.getAbsolutePath();
            for (char c : new char[] {'"', '\\', '$', '`'})
                if (path.indexOf(c) >= 0) return "Renomeie o arquivo: tire do nome o símbolo " + c;
            if (!"psx".equals(e.core) && !new File("/data/local/cores/" + e.core + ".so").isFile())
                return "Falta o emulador " + e.core + " neste aparelho";
            File saves = new File(romsDir(), "saves"), bios = new File(romsDir(), "bios");
            saves.mkdirs();
            bios.mkdirs();
            java.io.FileWriter w = new java.io.FileWriter(new File(romsDir(), ".retroarch.cfg"));
            w.write("audio_enable = \"false\"\nvideo_font_enable = \"false\"\ninput_overlay_enable = \"false\"\n"
                    + "video_vsync = \"true\"\nvideo_threaded = \"false\"\nvideo_smooth = \"true\"\n"
                    + "rewind_enable = \"false\"\ninput_autodetect_enable = \"false\"\n"
                    // trocar de jogo mata o processo: o cartao de memoria tem de ir para o SD sozinho
                    + "autosave_interval = \"10\"\n"
                    // sem som nada segura a velocidade: jogo PAL (50 Hz) rodava 19% rapido na tela de 60 Hz
                    + "fastforward_ratio = \"1.0\"\n"
                    // GT2 trava na tela de aviso com o recompilador: abre de saves/<rom>.state.auto (MAPA_DO_CARTAO, FLIPERAMA 6).
                    // So carrega; nao ligar savestate_auto_save, que sobrescreveria esse estado ao sair.
                    + "savestate_auto_load = \"true\"\n"
                    + "savefile_directory = \"" + saves.getAbsolutePath() + "\"\n"
                    + "savestate_directory = \"" + saves.getAbsolutePath() + "\"\n"
                    + "system_directory = \"" + bios.getAbsolutePath() + "\"\n"
                    // No PrBoom o botao X do RetroPad confirma no menu e atira: fica no A do controle (e o que vinha no A vai para o Y)
                    + ("prboom".equals(e.core)
                            ? "input_player1_x_btn = \"23\"\ninput_player1_b_btn = \"100\"\n"
                            : "input_player1_b_btn = \"23\"\ninput_player1_x_btn = \"100\"\n")
                    + "input_player1_a_btn = \"101\"\n"
                    + "input_player1_y_btn = \"99\"\n"
                    + "input_player1_l_btn = \"102\"\ninput_player1_r_btn = \"103\"\n"
                    + "input_player1_l2_btn = \"104\"\ninput_player1_r2_btn = \"105\"\n"
                    + "input_player1_select_btn = \"109\"\ninput_player1_start_btn = \"108\"\n"
                    + "input_player1_up_btn = \"19\"\ninput_player1_down_btn = \"20\"\n"
                    + "input_player1_left_btn = \"21\"\ninput_player1_right_btn = \"22\"\n");
            w.close();
            gameMode = true;
            launchedPkg = "org.retroarch";
            launchedAt = android.os.SystemClock.uptimeMillis();
            sendCmd("ra " + e.core + " " + path);
            return null;
        } catch (Exception ex) {
            return "Não consegui abrir o RetroArch: " + ex.getMessage();
        }
    }

    /** Os nucleos do RetroArch nao abrem .zip: descompacta o jogo em roms/.cache. Devolve null se nao achou ou se e grande. */
    private static File unzipRom(Sys s, File zip) throws java.io.IOException {
        java.util.zip.ZipFile z = new java.util.zip.ZipFile(zip);
        try {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry ze = en.nextElement();
                if (ze.isDirectory() || !s.accepts(ze.getName())) continue;
                if (ze.getSize() > 64L * 1024 * 1024) return null;
                File dir = new File(romsDir(), ".cache");
                dir.mkdirs();
                File out = new File(dir, new File(ze.getName()).getName());
                if (out.isFile() && out.length() == ze.getSize()) return out;
                java.io.InputStream in = z.getInputStream(ze);
                java.io.FileOutputStream o = new java.io.FileOutputStream(out);
                try {
                    byte[] b = new byte[65536];
                    int n;
                    while ((n = in.read(b)) > 0) o.write(b, 0, n);
                } finally {
                    o.close();
                    in.close();
                }
                return out;
            }
            return null;
        } finally {
            z.close();
        }
    }

    /** O relogio da placa vem em 2011: acerta pela rede quando o fliperama abre. Sem rede, tenta por uns 10 min e desiste. */
    static void syncClock() {
        new Thread(new Runnable() {
            public void run() {
                for (int t = 0; t < 20; t++) {
                    long now = ntp();
                    if (now > 0 && romsDir().isDirectory()) {
                        sendCmd("hora " + now);
                        return;
                    }
                    try { Thread.sleep(30000); } catch (InterruptedException e) { return; }
                }
            }
        }, "relogio").start();
    }

    private static long ntp() {
        java.net.DatagramSocket s = null;
        try {
            byte[] b = new byte[48];
            b[0] = 0x1b;                     // cliente, NTP versao 3
            s = new java.net.DatagramSocket();
            s.setSoTimeout(5000);
            s.send(new java.net.DatagramPacket(b, 48, java.net.InetAddress.getByName("pool.ntp.org"), 123));
            java.net.DatagramPacket r = new java.net.DatagramPacket(b, 48);
            s.receive(r);
            long sec = ((b[40] & 0xffL) << 24) | ((b[41] & 0xffL) << 16) | ((b[42] & 0xffL) << 8) | (b[43] & 0xffL);
            return sec - 2208988800L;        // de 1900 para 1970
        } catch (Exception e) {
            return 0;
        } finally {
            if (s != null) s.close();
        }
    }

    /**
     * O MAME4droid nao abre um jogo direto, mas guarda em frontend/mame_v2.cfg qual fica marcado na lista dele.
     * A lista segue a ordem do gamelist.txt, so com os jogos que existem na pasta: assim o jogo escolhido ja vem marcado.
     */
    private static File lastArcade;

    private static void preselectArcade(File rom) {
        File arcade = new File(romsDir(), "arcade");
        String want = rom.getName().toLowerCase(Locale.US);
        java.util.HashSet<String> have = new java.util.HashSet<String>();
        String[] names = new File(arcade, "roms").list();
        if (names != null) for (String n : names) have.add(n.toLowerCase(Locale.US));
        java.io.BufferedReader r = null;
        try {
            r = new java.io.BufferedReader(new java.io.FileReader(new File(arcade, "gamelist.txt")));
            String l;
            int pos = 0;
            while ((l = r.readLine()) != null) {
                int sp = l.indexOf(' ');
                if (sp <= 0) continue;
                String zip = l.substring(0, sp).toLowerCase(Locale.US) + ".zip";
                if (!have.contains(zip)) continue;
                if (zip.equals(want)) {
                    java.io.FileWriter w = new java.io.FileWriter(new File(arcade, "frontend/mame_v2.cfg"));
                    w.write(pos + "\n");
                    w.close();
                    return;
                }
                pos++;
            }
        } catch (Exception ignored) {
            // sem o arquivo o usuario so escolhe na lista
        } finally {
            try { if (r != null) r.close(); } catch (Exception ignored) { }
        }
    }

    /** Abre a ROM no emulador. Devolve null se abriu, ou uma mensagem. */
    static String launch(Context ctx, Sys s, File rom) {
        if (isXash(rom)) {
            // quem abre e o joyd: a tela do jogo nao e exportada e ele libera o mouse virtual (joypad/xash.sh)
            gameMode = true;
            launchedPkg = XASH_PKG;
            launchedAt = android.os.SystemClock.uptimeMillis();
            sendCmd("xash");
            return null;
        }
        if (s.installed == null) return "Nenhum emulador de " + s.name + " instalado";
        Emu e = s.installed;
        PackageManager pm = ctx.getPackageManager();
        // Outro jogo de arcade: com o MAME ainda aberto, o Android so traria de volta o jogo anterior
        if ("arcade".equals(s.id) && e.pkg.startsWith("com.seleuco") && !rom.equals(lastArcade)) {
            ((android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE)).killBackgroundProcesses(e.pkg);
            try { Thread.sleep(400); } catch (InterruptedException ignored) { }
            preselectArcade(rom);
            lastArcade = rom;
        }
        if ("org.retroarch".equals(e.pkg)) return launchRetroArch(ctx, s, e, rom);
        if (N64_PKG.equals(e.pkg)) {
            String path = rom.getAbsolutePath();
            for (char c : new char[] {'"', '\\', '$', '`', '<', '>', '&'})
                if (path.indexOf(c) >= 0) return "Renomeie o arquivo: tire do nome o símbolo " + c;
            gameMode = true;
            launchedPkg = N64_PKG;
            launchedAt = android.os.SystemClock.uptimeMillis();
            sendCmd("n64 " + path);
            return null;
        }
        Uri u = Uri.fromFile(rom);
        List<Intent> tries = new ArrayList<Intent>();
        if (e.cls != null) {
            Intent i = new Intent(Intent.ACTION_VIEW);
            if (e.mime != null) i.setDataAndType(u, e.mime); else i.setData(u);
            i.setClassName(e.pkg, e.cls);
            tries.add(i);
        }
        Intent v = new Intent(Intent.ACTION_VIEW);
        v.setData(u);
        v.setPackage(e.pkg);
        tries.add(v);
        Intent o = new Intent(Intent.ACTION_VIEW);
        o.setDataAndType(u, "application/octet-stream");
        o.setPackage(e.pkg);
        tries.add(o);
        for (Intent i : tries) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (!pm.queryIntentActivities(i, 0).isEmpty()) {
                try { ctx.startActivity(i); return null; } catch (Exception ignored) { }
            }
        }
        Intent m = pm.getLaunchIntentForPackage(e.pkg);
        if (m != null) {
            m.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(m);
            return "Aperte A três vezes para começar.\nSe pedir \"type OK\": esquerda, depois direita.\nPara voltar ao fliperama: Select + Start";
        }
        return "Não consegui abrir o emulador";
    }
}
