"""Logo do ScrapCade: limpa o pixel art e gera as versoes usadas no sistema.
A arte original (JPEG 2000x1116) e pixel art numa grade de ~240x134 blocos (8,33 px cada), borrada pela compressao.
 1) Reconstroi a grade: a cor de cada bloco e a mediana do miolo dele -> blocos nitidos, sem ruido de JPEG.
 2) Brilho de neon (bloom) e vinheta.
 3) Saidas:
    scrapcade/logo_pixel.png        arte limpa, blocos de 8 px (para usar onde quiser)
    scrapcade/logo_800x480.png      versao da tela do aparelho, com neon
    ../assets/logo.jpg              abertura do ScrapCade
    ../assets/web/logo.jpg          cabecalho da pagina do PC
    ../scrapcadeboot.zip            animacao de boot do Android (aparece e pulsa)
Uso: py -3.12 logo_scrapcade.py <original.jpg>"""
import io
import os
import sys
import zipfile

import numpy as np
from PIL import Image, ImageChops, ImageFilter

AQUI = os.path.dirname(os.path.abspath(__file__))
SAIDA = os.path.join(AQUI, "scrapcade")
ASSETS = os.path.join(AQUI, "..", "assets")
PASSO = 2000 / 240          # tamanho do bloco na arte original


def fase(g):
    """Deslocamento da grade num eixo: onde a energia de borda mais coincide com as divisas dos blocos."""
    melhor, onde = -1.0, 0.0
    for f in np.arange(0, PASSO, 0.125):
        idx = np.round(f + np.arange(0, len(g) / PASSO + 1) * PASSO).astype(int) - 1
        idx = idx[(idx >= 0) & (idx < len(g))]
        s = g[idx].sum()
        if s > melhor:
            melhor, onde = s, f
    return onde


def reconstroi(a):
    cinza = a.mean(axis=2)
    fx = fase(np.abs(np.diff(cinza, axis=1)).sum(axis=0))
    fy = fase(np.abs(np.diff(cinza, axis=0)).sum(axis=1))
    h, w, _ = a.shape

    def divisas(f, n):
        ini = f - PASSO if f > 1.0 else f
        d = [ini]
        while d[-1] + PASSO < n + 0.5:
            d.append(d[-1] + PASSO)
        return [(max(0.0, x), min(float(n), x + PASSO)) for x in d if min(float(n), x + PASSO) - max(0.0, x) >= 2.0]

    cols, lins = divisas(fx, w), divisas(fy, h)
    out = np.zeros((len(lins), len(cols), 3), dtype=np.uint8)
    for j, (y0, y1) in enumerate(lins):
        ya, yb = int(round(y0)) + 2, int(round(y1)) - 2
        if yb <= ya:
            ya, yb = int(y0), max(int(y0) + 1, int(round(y1)))
        for i, (x0, x1) in enumerate(cols):
            xa, xb = int(round(x0)) + 2, int(round(x1)) - 2
            if xb <= xa:
                xa, xb = int(x0), max(int(x0) + 1, int(round(x1)))
            out[j, i] = np.median(a[ya:yb, xa:xb].reshape(-1, 3), axis=0)
    print(f"grade: fase x {fx:.2f}, y {fy:.2f}; {len(cols)} x {len(lins)} blocos")
    return Image.fromarray(out)


def estica_bordas(im, alt):
    """Leva a imagem para a altura da tela esticando so as faixas de cima e de baixo (grade do fundo); o letreiro nao deforma."""
    a = np.asarray(im, dtype=np.float32)
    h = a.shape[0]
    pad = (alt - h) / 2.0
    faixa = 70.0                      # o letreiro comeca depois disso
    y = np.arange(alt, dtype=np.float32)
    src = y - pad
    topo = y < faixa + pad
    src[topo] = faixa * (y[topo] / (faixa + pad)) ** ((faixa + pad) / faixa)
    base = y > alt - 1 - (faixa + pad)
    yb = alt - 1 - y[base]
    src[base] = (h - 1) - faixa * (yb / (faixa + pad)) ** ((faixa + pad) / faixa)
    src = np.clip(src, 0, h - 1)
    i0 = np.floor(src).astype(int)
    i1 = np.minimum(i0 + 1, h - 1)
    t = (src - i0)[:, None, None]
    return Image.fromarray(np.clip(a[i0] * (1 - t) + a[i1] * t, 0, 255).astype(np.uint8))


def brilho(im, raio):
    """So as partes claras e saturadas, borradas: o halo do neon."""
    a = np.asarray(im, dtype=np.float32) / 255
    forte = np.clip((a.max(axis=2) - 0.55) / 0.35, 0, 1)[..., None]
    claro = Image.fromarray((a * forte * 255).astype(np.uint8))
    perto = claro.filter(ImageFilter.GaussianBlur(raio))
    longe = claro.filter(ImageFilter.GaussianBlur(raio * 3))
    return np.asarray(perto, dtype=np.float32) * 0.75 + np.asarray(longe, dtype=np.float32) * 0.55


def vinheta(w, h, forca=0.30):
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    r2 = ((x - w / 2) / (w / 2)) ** 2 + ((y - h / 2) / (h / 2)) ** 2
    return (1 - forca * np.clip(r2 - 0.35, 0, None) / 1.65)[..., None]


def compoe(base, halo, vin, forca, ganho=1.0):
    a = np.asarray(base, dtype=np.float32)
    out = 255 - (255 - a) * (1 - np.clip(halo * forca / 255, 0, 1))      # mistura "tela": clareia sem estourar
    return Image.fromarray(np.clip(out * vin * ganho, 0, 255).astype(np.uint8))


def jpeg(im, q=88):
    b = io.BytesIO()
    im.save(b, "JPEG", quality=q, optimize=True)
    return b.getvalue()


os.makedirs(SAIDA, exist_ok=True)
os.makedirs(os.path.join(ASSETS, "web"), exist_ok=True)
orig = Image.open(sys.argv[1]).convert("RGB")
orig.save(os.path.join(SAIDA, "original.jpg"), quality=95)
pix = reconstroi(np.asarray(orig, dtype=np.float32))
pix.save(os.path.join(SAIDA, "logo_grade.png"))
grande = pix.resize((pix.width * 8, pix.height * 8), Image.NEAREST)
grande.save(os.path.join(SAIDA, "logo_pixel.png"), optimize=True)

# tela do aparelho: reduz a arte limpa com filtro (blocos de tamanho uniforme) e completa a altura pelas bordas
dez = pix.resize((pix.width * 10, pix.height * 10), Image.NEAREST)
larg = 800
tela = dez.resize((larg, round(larg * pix.height / pix.width)), Image.LANCZOS)
tela = estica_bordas(tela, 480)
halo, vin = brilho(tela, 5), vinheta(800, 480)
final = compoe(tela, halo, vin, 0.55)
final.save(os.path.join(SAIDA, "logo_800x480.png"), optimize=True)
open(os.path.join(ASSETS, "logo.jpg"), "wb").write(jpeg(final, 90))

# versao grande com neon, para usar fora do aparelho
g_halo = brilho(grande, 12)
compoe(grande, g_halo, vinheta(grande.width, grande.height), 0.55).save(os.path.join(SAIDA, "logo_neon.png"), optimize=True)

# cabecalho da pagina: so a faixa do letreiro
faixa = final.crop((0, 96, 800, 384))
open(os.path.join(ASSETS, "web", "logo.jpg"), "wb").write(jpeg(faixa, 86))

# animacao de boot: aparece (uma vez) e depois o neon pulsa (em laco). Poucos quadros: cada um custa memoria e tempo de boot.
FPS, N = 10, 10
quadros0 = [compoe(tela, halo, vin, 0.55, g) for g in (0.12, 0.3, 0.5, 0.72, 0.9)]
quadros1 = [compoe(tela, halo, vin, 0.55 + 0.45 * (0.5 - 0.5 * np.cos(2 * np.pi * i / N))) for i in range(N)]
zpath = os.path.join(AQUI, "..", "scrapcadeboot.zip")
with zipfile.ZipFile(zpath, "w", zipfile.ZIP_STORED) as z:      # o Android 2.3 so le quadros sem compressao no zip
    z.writestr("desc.txt", f"800 480 {FPS}\np 1 0 part0\np 0 0 part1\n")
    for i, q in enumerate(quadros0):
        z.writestr(f"part0/{i:03d}.jpg", jpeg(q))
    for i, q in enumerate(quadros1):
        z.writestr(f"part1/{i:03d}.jpg", jpeg(q))
print("boot:", os.path.getsize(zpath), "bytes;", "tela:", final.size, "; web:", faixa.size)
