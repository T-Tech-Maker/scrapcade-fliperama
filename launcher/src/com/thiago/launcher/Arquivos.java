package com.thiago.launcher;

import android.os.Environment;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Operacoes do gerenciador de arquivos, usadas pela pagina (http://IP:8080/arquivos) e pela tela do aparelho
 * (ArquivosTela): listar, renomear, mover, copiar, clonar, criar pasta e apagar.
 * So enxerga as pastas de jogos e o pen drive. Todo caminho e resolvido (canonicalPath) e precisa cair dentro de uma
 * das raizes abaixo: nada mais do cartao aparece ou pode ser mexido por aqui.
 */
final class Arquivos {
    private static final String USB = "Pen drive";
    private static final String[] ROOTS = {"roms", "Jogos novos", "xash", USB};

    /** Bytes ja copiados pela operacao em andamento, para a tela mostrar o progresso. */
    static volatile long progress;

    static final class Entry {
        final String name, path;
        final boolean dir;
        final long size;
        Entry(String name, String path, boolean dir, long size) { this.name = name; this.path = path; this.dir = dir; this.size = size; }
    }

    private Arquivos() { }

    private static File base(String root) {
        File f = USB.equals(root) ? new File("/mnt/usb") : new File(Environment.getExternalStorageDirectory(), root);
        try {
            return f.getCanonicalFile();
        } catch (IOException e) {
            return f;
        }
    }

    /** Caminho como a pagina e a tela mostram ("roms/psx/jogo.cue") para arquivo, ou null se sair das raizes. */
    static File resolve(String rel) {
        if (rel == null) return null;
        rel = rel.replace('\\', '/');
        while (rel.startsWith("/")) rel = rel.substring(1);
        int cut = rel.indexOf('/');
        String root = cut < 0 ? rel : rel.substring(0, cut), rest = cut < 0 ? "" : rel.substring(cut + 1);
        for (String r : ROOTS) {
            if (!r.equals(root)) continue;
            try {
                String b = base(r).getPath();
                File f = new File(base(r), rest).getCanonicalFile();
                if (f.getPath().equals(b) || f.getPath().startsWith(b + "/")) return f;
            } catch (IOException ignored) { }
        }
        return null;
    }

    private static boolean isRoot(File f) {
        for (String r : ROOTS) if (base(r).getPath().equals(f.getPath())) return true;
        return false;
    }

    /** O inverso de resolve(). */
    static String rel(File f) {
        String p;
        try {
            p = f.getCanonicalPath();
        } catch (IOException e) {
            p = f.getAbsolutePath();
        }
        for (String r : ROOTS) {
            String b = base(r).getPath();
            if (p.equals(b)) return r;
            if (p.startsWith(b + "/")) return r + p.substring(b.length());
        }
        return p;
    }

    static Map<String, String> query(String path) throws IOException {
        Map<String, String> m = new HashMap<String, String>();
        int q = path.indexOf('?');
        if (q < 0) return m;
        for (String kv : path.substring(q + 1).split("&")) {
            int e = kv.indexOf('=');
            if (e > 0) m.put(kv.substring(0, e), URLDecoder.decode(kv.substring(e + 1), "UTF-8"));
        }
        return m;
    }

    /** Conteudo de uma pasta, pastas primeiro. "" = as raizes que existem. null = pasta invalida ou que sumiu. */
    static List<Entry> entries(String rel) {
        List<Entry> l = new ArrayList<Entry>();
        if (rel == null || rel.length() == 0) {
            for (String r : ROOTS) {
                File b = base(r);
                String[] in = b.list();
                if (b.isDirectory() && (!USB.equals(r) || (in != null && in.length > 0))) l.add(new Entry(r, r, true, 0));
            }
            return l;
        }
        File d = resolve(rel);
        if (d == null || !d.isDirectory()) return null;
        File[] fs = d.listFiles();
        if (fs == null) return l;
        String at = rel(d);
        for (File f : fs) {
            String n = f.getName();
            if (n.startsWith(".") || n.endsWith(Envio.PARTIAL)) continue;      // arquivos internos do menu e envios pela metade
            l.add(new Entry(n, at + "/" + n, f.isDirectory(), f.isDirectory() ? 0 : f.length()));
        }
        Collections.sort(l, new Comparator<Entry>() {
            public int compare(Entry a, Entry b) {
                if (a.dir != b.dir) return a.dir ? -1 : 1;
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return l;
    }

    private static String js(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 32) b.append(String.format(Locale.US, "\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    /** A mesma lista em JSON, para a pagina. */
    static String list(String rel) {
        List<Entry> l = entries(rel);
        if (l == null) return null;
        StringBuilder b = new StringBuilder("[");
        for (Entry e : l) {
            if (b.length() > 1) b.append(',');
            b.append("{\"n\":").append(js(e.name)).append(",\"p\":").append(js(e.path))
                    .append(",\"d\":").append(e.dir).append(",\"s\":").append(e.size).append('}');
        }
        return b.append(']').toString();
    }

    private static void copy(File a, File b) throws IOException {
        if (a.isDirectory()) {
            if (!b.mkdirs() && !b.isDirectory()) throw new IOException("nao criou " + b);
            File[] fs = a.listFiles();
            if (fs != null) for (File f : fs) copy(f, new File(b, f.getName()));
            return;
        }
        InputStream in = new FileInputStream(a);
        OutputStream out = new FileOutputStream(b);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                progress += n;
            }
        } finally {
            out.close();
            in.close();
        }
        b.setLastModified(a.lastModified());
    }

    private static boolean delete(File f) {
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) for (File c : fs) delete(c);
        }
        return f.delete();
    }

    /** "Jogo.zip" -> "Jogo (copia).zip", "Jogo (copia 2).zip"... sem sobrescrever nada. */
    private static File freeName(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; ; i++) {
            f = new File(dir, base + (i == 1 ? " (cópia)" : " (cópia " + i + ")") + ext);
            if (!f.exists()) return f;
        }
    }

    private static boolean badName(String n) {
        return n == null || n.length() == 0 || n.contains("/") || n.contains("\\") || n.equals(".") || n.equals("..");
    }

    /** Executa uma operacao (a = origem, b = pasta de destino, n = nome). Devolve o status HTTP: "200 OK" ou o erro. */
    static String run(String op, Map<String, String> q) throws IOException {
        progress = 0;
        File a = resolve(q.get("a"));
        if (a == null || !a.exists()) return "404 Nao encontrado";
        if (op.equals("pasta")) {
            String n = q.get("n");
            if (badName(n) || !a.isDirectory()) return "400 Nome invalido";
            return new File(a, n).mkdir() ? "200 OK" : "409 Ja existe";
        }
        if (isRoot(a)) return "403 Pasta principal nao pode ser mexida";
        if (op.equals("apagar")) return delete(a) ? "200 OK" : "500 Nao apagou";
        if (op.equals("renomear")) {
            String n = q.get("n");
            if (badName(n)) return "400 Nome invalido";
            File to = new File(a.getParentFile(), n);
            if (to.exists()) return "409 Ja existe";
            return a.renameTo(to) ? "200 OK" : "500 Nao renomeou";
        }
        if (op.equals("clonar")) {
            copy(a, freeName(a.getParentFile(), a.getName()));
            return "200 OK";
        }
        File dest = resolve(q.get("b"));
        if (dest == null || !dest.isDirectory()) return "400 Destino invalido";
        if (dest.getPath().equals(a.getPath()) || dest.getPath().startsWith(a.getPath() + "/")) return "400 Destino dentro da origem";
        File to = freeName(dest, a.getName());
        if (op.equals("copiar")) {
            copy(a, to);
            return "200 OK";
        }
        if (op.equals("mover")) {
            if (a.renameTo(to)) return "200 OK";
            copy(a, to);          // outro disco (pen drive): copia e apaga
            return delete(a) ? "200 OK" : "500 Copiou, mas nao apagou a origem";
        }
        return "404 Operacao desconhecida";
    }

    static String page() {
        return Envio.head("ScrapCade - arquivos", true)
                + "<div id=cam></div>"
                + "<div class=bar><button onclick='nova()'>Nova pasta</button><button onclick=\"document.getElementById('up').click()\">"
                + "Enviar arquivos para esta pasta</button><input id=up type=file multiple></div>"
                + "<div id=clip></div><div id=msg></div><table id=t></table></main><script>"
                + "var cur='',clip=null;function $(i){return document.getElementById(i)}"
                + "function tam(s){return s>1048576?(s/1048576).toFixed(1)+' MB':Math.ceil(s/1024)+' KB'}"
                + "function req(m,u,body,ok){var x=new XMLHttpRequest;x.open(m,u);x.onload=function(){if(x.status==200)ok(x);else $('msg').textContent='Erro: '+x.statusText};x.send(body||null)}"
                + "function op(o,a,b,n){$('msg').textContent='Aguarde...';var u='/fs/'+o+'?a='+encodeURIComponent(a)+(b!=null?'&b='+encodeURIComponent(b):'')"
                + "+(n!=null?'&n='+encodeURIComponent(n):'');req('POST',u,null,function(){$('msg').textContent='Feito.';abre(cur)})}"
                + "function abre(p){cur=p;req('GET','/fs/lista?p='+encodeURIComponent(p),null,function(x){var l=JSON.parse(x.responseText),h='';"
                + "var c='<a onclick=\"abre(\\'\\')\">Início</a>',acc='';if(p)p.split('/').forEach(function(s){acc+=(acc?'/':'')+s;"
                + "c+=' / <a onclick=\"abre(\\''+acc.replace(/'/g,\"\\\\'\")+'\\')\">'+esc(s)+'</a>'});$('cam').innerHTML=c;"
                + "if(!l.length)h='<tr><td>Pasta vazia</td></tr>';l.forEach(function(f,i){h+='<tr><td><span class=n data-i='+i+'>'+(f.d?'📁 ':'')+esc(f.n)+'</span></td>'"
                + "+'<td class=s>'+(f.d?'':tam(f.s))+'</td><td class=o>'+(p?(f.d?'':'<button data-o=baixar data-i='+i+'>baixar</button>')"
                + "+'<button data-o=renomear data-i='+i+'>renomear</button><button data-o=recortar data-i='+i+'>mover</button>'"
                + "+'<button data-o=copiarclip data-i='+i+'>copiar</button><button data-o=clonar data-i='+i+'>clonar</button>'"
                + "+'<button class=v data-o=apagar data-i='+i+'>apagar</button>':'')+'</td></tr>'});$('t').innerHTML=h;"
                + "Array.prototype.forEach.call(document.querySelectorAll('[data-i]'),function(e){e.onclick=function(){acao(e.dataset.o,l[e.dataset.i])}});mostraClip()})}"
                + "function esc(s){return s.replace(/&/g,'&amp;').replace(/</g,'&lt;')}"
                + "function acao(o,f){if(!o){if(f.d)abre(f.p);return}"
                + "if(o=='baixar')location.href='/fs/baixar?p='+encodeURIComponent(f.p);"
                + "else if(o=='renomear'){var n=prompt('Novo nome:',f.n);if(n&&n!=f.n)op('renomear',f.p,null,n)}"
                + "else if(o=='recortar'||o=='copiarclip'){clip={o:o=='recortar'?'mover':'copiar',f:f};mostraClip()}"
                + "else if(o=='clonar')op('clonar',f.p);"
                + "else if(o=='apagar'){if(confirm('Apagar '+f.n+(f.d?' e tudo dentro dela':'')+'? Não dá para desfazer.'))op('apagar',f.p)}}"
                + "function mostraClip(){var c=$('clip');if(!clip){c.style.display='none';return}c.style.display='block';"
                + "c.innerHTML=(clip.o=='mover'?'Mover ':'Copiar ')+'<b>'+esc(clip.f.n)+'</b>: abra a pasta de destino e '"
                + "+(cur?'<button class=a onclick=\"cola()\">colar aqui</button> ':'escolha uma pasta. ')+'<button onclick=\"clip=null;mostraClip()\">cancelar</button>'}"
                + "function cola(){var c=clip;clip=null;op(c.o,c.f.p,cur)}"
                + "function nova(){if(!cur){alert('Abra uma pasta primeiro.');return}var n=prompt('Nome da pasta nova:');if(n)op('pasta',cur,null,n)}"
                + "$('up').onchange=function(e){if(!cur){alert('Abra uma pasta primeiro.');return}var fs=Array.prototype.slice.call(e.target.files);"
                + "(function vai(){var f=fs.shift();if(!f){abre(cur);return}var x=new XMLHttpRequest;x.open('PUT','/fs/enviar?a='+encodeURIComponent(cur)+'&n='+encodeURIComponent(f.name));"
                + "x.upload.onprogress=function(ev){$('msg').textContent=f.name+': '+Math.round(ev.loaded*100/f.size)+'%'};x.onload=vai;x.send(f)})()};"
                + "abre('roms')</script></html>";
    }
}
