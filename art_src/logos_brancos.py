"""Gera assets/logos_brancos/<id>.png: o logo de cada console em branco, recortado, para todos ficarem iguais.
Logo com selo escuro e letra clara (Mega Drive) fica so com a parte clara; os outros viram silhueta branca.
Uso: py -3.12 logos_brancos.py <assets>"""
import os
import sys

from PIL import Image

PELA_LUZ = {"md", "gba"}      # selo preto com letras brancas: a silhueta viraria um borrao
assets = sys.argv[1]
src, out = os.path.join(assets, "logos"), os.path.join(assets, "logos_brancos")
os.makedirs(out, exist_ok=True)
for n in sorted(os.listdir(src)):
    if n == "pc.png":      # MS-DOS nao representa Half-Life: o menu escreve o nome
        continue
    im = Image.open(os.path.join(src, n)).convert("RGBA")
    px = im.load()
    w, h = im.size
    res = Image.new("RGBA", (w, h), (255, 255, 255, 0))
    rp = res.load()
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if os.path.splitext(n)[0] in PELA_LUZ:
                lum = (r * 299 + g * 587 + b * 114) // 1000
                a = a * max(0, lum - 90) // 165
            rp[x, y] = (255, 255, 255, min(255, a))
    box = res.getbbox()
    if box:
        res = res.crop(box)
    res.save(os.path.join(out, n), optimize=True)
    print(n, res.size)

