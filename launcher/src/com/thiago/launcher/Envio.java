package com.thiago.launcher;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Pagina na rede local (http://IP:8080) para mandar jogos do PC, e apagar jogos, sem cabo e sem programa.
 * De proposito NAO le nem lista nenhum arquivo do cartao alem da lista de jogos: o cartao veio de um
 * aparelho clinico, e nada dele pode sair por aqui. So escreve em roms/novos e so apaga o que e jogo.
 */
final class Envio implements Runnable {
    static final int PORT = 8080;
    static final String PARTIAL = ".envio";

    interface Host {
        /** Chegou arquivo novo, ou um jogo foi apagado. Chamado fora da thread da tela. */
        void changed();
    }

    private final Host host;
    private volatile List<Catalog.Game> games = new ArrayList<Catalog.Game>();
    private volatile boolean stopped;
    private ServerSocket server;

    Envio(Host host) { this.host = host; }

    void setGames(List<Catalog.Game> g) { games = g; }

    void start() { new Thread(this, "envio").start(); }

    void stop() {
        stopped = true;
        try { if (server != null) server.close(); } catch (IOException ignored) { }
    }

    public void run() {
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new java.net.InetSocketAddress(PORT));
        } catch (IOException e) {
            return;
        }
        while (!stopped) {
            final Socket s;
            try {
                s = server.accept();
            } catch (IOException e) {
                continue;
            }
            // cada pedido na sua thread: um envio longo nao trava a pagina
            new Thread(new Runnable() {
                public void run() {
                    try {
                        s.setSoTimeout(60000);
                        handle(s);
                    } catch (Exception ignored) {
                    } finally {
                        try { s.close(); } catch (IOException ignored) { }
                    }
                }
            }, "envio-pedido").start();
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0 && c != '\n') if (c != '\r') b.write(c);
        if (c < 0 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    private void handle(Socket s) throws IOException {
        InputStream in = s.getInputStream();
        OutputStream out = new BufferedOutputStream(s.getOutputStream());
        String first = readLine(in);
        if (first == null) return;
        String[] req = first.split(" ");
        if (req.length < 2) return;
        long length = 0;
        String l;
        while ((l = readLine(in)) != null && l.length() > 0) {
            if (l.toLowerCase(java.util.Locale.US).startsWith("content-length:")) length = Long.parseLong(l.substring(15).trim());
        }
        String method = req[0], path = req[1];
        if (method.equals("PUT") && path.startsWith("/envio/")) {
            reply(out, receive(in, path.substring(7), length));
        } else if (method.equals("PUT") && path.startsWith("/capa/")) {
            reply(out, cover(in, URLDecoder.decode(path.substring(6), "UTF-8"), length));
        } else if (method.equals("POST") && path.startsWith("/apaga/")) {
            reply(out, delete(URLDecoder.decode(path.substring(7), "UTF-8")));
        } else if (method.equals("GET") && path.equals("/logo.jpg")) {
            sendLogo(out);
        } else if (method.equals("GET") && path.equals("/arquivos")) {
            send(out, "text/html; charset=utf-8", Arquivos.page().getBytes("UTF-8"));
        } else if (method.equals("GET") && path.startsWith("/fs/lista")) {
            String j = Arquivos.list(Arquivos.query(path).get("p"));
            if (j == null) reply(out, "404 Nao encontrado");
            else send(out, "application/json; charset=utf-8", j.getBytes("UTF-8"));
        } else if (method.equals("GET") && path.startsWith("/fs/baixar")) {
            download(out, Arquivos.resolve(Arquivos.query(path).get("p")));
        } else if (method.equals("PUT") && path.startsWith("/fs/enviar")) {
            java.util.Map<String, String> q = Arquivos.query(path);
            reply(out, receiveTo(in, Arquivos.resolve(q.get("a")), q.get("n"), length));
        } else if (method.equals("POST") && path.startsWith("/fs/")) {
            String op = path.substring(4, path.indexOf('?') > 0 ? path.indexOf('?') : path.length());
            String r = Arquivos.run(op, Arquivos.query(path));
            if (r.startsWith("200")) host.changed();
            reply(out, r);
        } else if (method.equals("GET") && path.equals("/")) {
            byte[] page = page().getBytes("UTF-8");
            out.write(("HTTP/1.0 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + page.length
                    + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
            out.write(page);
        } else {
            reply(out, "404 Nao encontrado");
        }
        out.flush();
    }

    private static void send(OutputStream out, String type, byte[] body) throws IOException {
        out.write(("HTTP/1.0 200 OK\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
        out.write(body);
    }

    /** Baixar um arquivo das pastas de jogos (Arquivos.resolve ja barrou o resto do cartao). */
    private static void download(OutputStream out, File f) throws IOException {
        if (f == null || !f.isFile()) { reply(out, "404 Nao encontrado"); return; }
        String name = f.getName().replace("\"", "");
        out.write(("HTTP/1.0 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: " + f.length()
                + "\r\nContent-Disposition: attachment; filename=\"" + name + "\"\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
        InputStream in = new java.io.FileInputStream(f);
        try {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        } finally {
            in.close();
        }
    }

    /** Envio direto para uma pasta escolhida no gerenciador (nao passa pela caixa de entrada). */
    private String receiveTo(InputStream in, File dir, String name, long length) throws IOException {
        if (dir == null || !dir.isDirectory()) return "404 Pasta nao encontrada";
        if (name == null || name.length() == 0 || name.contains("/") || name.contains("\\") || name.startsWith(".")) return "400 Nome invalido";
        android.os.StatFs fs = new android.os.StatFs(dir.getPath());
        if (length > (long) fs.getAvailableBlocks() * fs.getBlockSize()) return "507 Cartao cheio";
        File tmp = new File(dir, name + PARTIAL);
        OutputStream f = new FileOutputStream(tmp);
        long left = length;
        try {
            byte[] b = new byte[65536];
            while (left > 0) {
                int n = in.read(b, 0, (int) Math.min(b.length, left));
                if (n <= 0) break;
                f.write(b, 0, n);
                left -= n;
            }
        } finally {
            f.close();
        }
        File to = new File(dir, name);
        if (left > 0 || (to.exists() && !to.delete()) || !tmp.renameTo(to)) {
            tmp.delete();
            return "500 Envio incompleto";
        }
        host.changed();
        return "200 OK";
    }

    private static void reply(OutputStream out, String status) throws IOException {
        out.write(("HTTP/1.0 " + status + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
    }

    private String receive(InputStream in, String encoded, long length) throws IOException {
        // so o nome do arquivo: nenhuma pasta vinda de fora
        String name = new File(URLDecoder.decode(encoded, "UTF-8").replace('\\', '/')).getName();
        if (name.length() == 0 || name.startsWith(".") || name.endsWith(PARTIAL)) return "400 Nome invalido";
        File dir = Organizer.inbox();
        dir.mkdirs();
        android.os.StatFs fs = new android.os.StatFs(dir.getPath());
        if (length > (long) fs.getAvailableBlocks() * fs.getBlockSize()) return "507 Cartao cheio";
        File tmp = new File(dir, name + PARTIAL);
        OutputStream f = new FileOutputStream(tmp);
        long left = length;
        try {
            byte[] b = new byte[65536];
            while (left > 0) {
                int n = in.read(b, 0, (int) Math.min(b.length, left));
                if (n <= 0) break;
                f.write(b, 0, n);
                left -= n;
            }
        } finally {
            f.close();
        }
        if (left > 0 || !tmp.renameTo(new File(dir, name))) {
            tmp.delete();
            return "500 Envio incompleto";
        }
        host.changed();
        return "200 OK";
    }

    /** Troca a capa de um jogo da lista: images/<jogo>.png, ao lado do jogo. O Android le a imagem pelo conteudo, seja PNG ou JPG. */
    private String cover(InputStream in, String key, long length) throws IOException {
        if (length <= 0 || length > 16L * 1024 * 1024) return "400 Imagem invalida";
        for (Catalog.Game g : games) {
            if (!g.key().equals(key)) continue;
            String n = g.rom.getName();
            int dot = n.lastIndexOf('.');
            File dir = new File(g.rom.getParentFile(), "images");
            dir.mkdirs();
            File tmp = new File(dir, n + PARTIAL), to = new File(dir, (dot > 0 ? n.substring(0, dot) : n) + ".png");
            OutputStream f = new FileOutputStream(tmp);
            long left = length;
            try {
                byte[] b = new byte[65536];
                while (left > 0) {
                    int r = in.read(b, 0, (int) Math.min(b.length, left));
                    if (r <= 0) break;
                    f.write(b, 0, r);
                    left -= r;
                }
            } finally {
                f.close();
            }
            if (left > 0) {
                tmp.delete();
                return "500 Envio incompleto";
            }
            to.delete();
            if (!tmp.renameTo(to)) return "500 Nao gravou";
            host.changed();
            return "200 OK";
        }
        return "404 Nao encontrado";
    }

    /** So apaga arquivo que esta na lista de jogos e dentro de roms. O .cue leva junto o .bin de mesmo nome. */
    private String delete(String key) {
        String roms = Catalog.romsDir().getPath() + "/";
        for (Catalog.Game g : games) {
            if (!g.key().equals(key) || !key.startsWith(roms) || !g.rom.isFile()) continue;
            if (!g.rom.delete()) return "500 Nao apagou";
            String n = key.toLowerCase(java.util.Locale.US);
            if (n.endsWith(".cue")) new File(key.substring(0, key.length() - 4) + ".bin").delete();
            host.changed();
            return "200 OK";
        }
        return "404 Nao encontrado";
    }

    private static String html(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static final String CSS = "*{box-sizing:border-box}"
            + "body{font:16px system-ui,-apple-system,Segoe UI,Roboto,sans-serif;margin:0;background:#0b0520;color:#f2f0ff}"
            + "main{max-width:860px;margin:auto;padding:16px}a{color:#19e6ff}"
            + ".logo{display:block;width:100%;max-width:520px;margin:0 auto 10px;border-radius:12px}"
            + "nav{display:flex;gap:8px;justify-content:center;margin:0 0 14px}"
            + "nav a{padding:8px 18px;border-radius:999px;background:#1d1447;text-decoration:none;color:#f2f0ff}"
            + "nav a.on{background:#19e6ff;color:#0b0520;font-weight:700}h2{font-size:18px;margin:18px 0 8px}"
            + "button{border:0;border-radius:8px;padding:8px 12px;min-height:36px;cursor:pointer;background:#2c2260;color:#fff;font:inherit;font-size:14px}"
            + "button.v,.x{background:#e0356b}button.a{background:#7dff3a;color:#0b0520;font-weight:700}"
            + "table{width:100%;border-collapse:collapse;background:#17103a;border-radius:12px;overflow:hidden}"
            + "td{padding:9px 12px;border-bottom:1px solid #2c2260;vertical-align:middle}tr:last-child td{border-bottom:0}"
            + ".c,td.s{color:#a59fcf;font-size:13px}td.s{white-space:nowrap}.b,td.o{text-align:right}.b{white-space:nowrap}"
            + ".b button,td.o button{margin:2px 0 2px 4px}.n{cursor:pointer;color:#19e6ff;font-weight:600;word-break:break-word}"
            + "#d{display:block;border:2px dashed #19e6ff;border-radius:14px;padding:30px 16px;text-align:center;margin:8px 0 10px;cursor:pointer;background:#120a30}"
            + "#d.o{background:#1d1447}input[type=file]{display:none}#s div,#msg{margin:4px 0;font-size:14px;color:#ffd34e;min-height:20px}"
            + "#cam{margin:8px 0;font-size:15px;word-break:break-word}#cam a{cursor:pointer}.bar{display:flex;flex-wrap:wrap;gap:8px;margin:8px 0}"
            + "#clip{background:#1d1447;border:1px solid #19e6ff;padding:10px 12px;border-radius:10px;display:none;margin:8px 0}"
            + "@media(max-width:640px){main{padding:10px}td{display:block;border:0;padding:3px 12px}"
            + "tr{display:block;border-bottom:1px solid #2c2260;padding:8px 0}.b,td.o{text-align:left;white-space:normal}"
            + ".b button,td.o button{margin:4px 6px 0 0}}";

    /** Inicio comum das paginas: estilo que se ajusta ao celular e ao PC, logo e abas. */
    static String head(String title, boolean files) {
        return "<!DOCTYPE html><html lang=pt-br><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<title>" + title + "</title><style>" + CSS + "</style><main><img class=logo src=/logo.jpg alt=ScrapCade>"
                + "<nav><a href=/" + (files ? "" : " class=on") + ">Jogos</a><a href=/arquivos" + (files ? " class=on" : "") + ">Arquivos</a></nav>";
    }

    /** A logo do cabecalho, guardada dentro do app. */
    private static void sendLogo(OutputStream out) throws IOException {
        InputStream in = Theme.ctx.getAssets().open("web/logo.jpg");
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        try {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        } finally {
            in.close();
        }
        out.write(("HTTP/1.0 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: " + b.size()
                + "\r\nCache-Control: max-age=86400\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
        out.write(b.toByteArray());
    }

    private String page() {
        StringBuilder rows = new StringBuilder();
        for (Catalog.Game g : games) {
            String meta = Meta.line(g);
            rows.append("<tr><td>").append(html(g.title)).append("<div class=c>").append(html(g.sys.name))
                    .append(meta.length() > 0 ? "  •  " + html(meta) : "").append("</div></td><td class=b>");
            rows.append("<button class=p data-k=\"").append(html(g.key())).append("\">trocar capa</button>");
            if (!Catalog.isXash(g.rom))
                rows.append("<button class=x data-k=\"").append(html(g.key())).append("\" data-t=\"").append(html(g.title)).append("\">apagar</button>");
            rows.append("</td></tr>");
        }
        return head("ScrapCade", false)
                + "<label id=d>Arraste os jogos para cá, ou toque para escolher<input id=f type=file multiple></label><div id=s></div>"
                + "<div class=c>Cada jogo vai sozinho para o console certo, e a capa é baixada sozinha.</div>"
                + "<h2>Jogos (" + games.size() + ")</h2><table>" + rows + "</table></main><script>"
                + "var d=document.getElementById('d'),s=document.getElementById('s'),q=[],on=0;"
                + "function go(){if(on||!q.length){if(!on&&s.dataset.fez){s.dataset.fez='';setTimeout(function(){location.reload()},4000)}return}on=1;"
                + "var f=q.shift(),l=document.createElement('div');s.appendChild(l);var x=new XMLHttpRequest;"
                + "x.open('PUT','/envio/'+encodeURIComponent(f.name));"
                + "x.upload.onprogress=function(e){l.textContent=f.name+': '+Math.round(e.loaded*100/f.size)+'%'};"
                + "x.onload=function(){l.textContent=f.name+(x.status==200?': enviado':': erro, '+x.statusText);s.dataset.fez=1;on=0;go()};"
                + "x.onerror=function(){l.textContent=f.name+': erro de rede';on=0;go()};x.send(f)}"
                + "function add(fs){for(var i=0;i<fs.length;i++)q.push(fs[i]);go()}"
                + "document.getElementById('f').onchange=function(e){add(e.target.files)};"
                + "d.ondragover=function(e){e.preventDefault();d.className='o'};d.ondragleave=function(){d.className=''};"
                + "d.ondrop=function(e){e.preventDefault();d.className='';add(e.dataTransfer.files)};"
                + "document.querySelectorAll('.x').forEach(function(b){b.onclick=function(){"
                + "if(!confirm('Apagar '+b.dataset.t+' do cartão? Não dá para desfazer.'))return;"
                + "var x=new XMLHttpRequest;x.open('POST','/apaga/'+encodeURIComponent(b.dataset.k));"
                + "x.onload=function(){location.reload()};x.send()}});"
                + "document.querySelectorAll('.p').forEach(function(b){b.onclick=function(){"
                + "var i=document.createElement('input');i.type='file';i.accept='image/png,image/jpeg';i.onchange=function(){"
                + "var x=new XMLHttpRequest;x.open('PUT','/capa/'+encodeURIComponent(b.dataset.k));"
                + "x.onload=function(){b.textContent=x.status==200?'capa trocada':'erro'};x.send(i.files[0])};i.click()}});"
                + "</script></html>";
    }
}
