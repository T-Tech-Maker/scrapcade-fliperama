"""Gera os recursos visuais do menu. Uso: py -3.12 -I gera_arte.py <pasta com os PNG de botoes do Xelu> <pasta assets>
 - botoes/: icones dos botoes (Xelu's Free Controller Prompts, CC0), reduzidos.
 - fundo.jpg: fundo proprio, gerado aqui por ondas de cor: ceu azul com faixas de luz e chao claro que reflete.
"""
import os
import sys

import numpy as np
from PIL import Image, ImageChops

ICONES = {
    "a": "XboxSeriesX_A", "b": "XboxSeriesX_B", "x": "XboxSeriesX_X", "y": "XboxSeriesX_Y",
    "lb": "XboxSeriesX_LB", "rb": "XboxSeriesX_RB", "select": "XboxSeriesX_View", "start": "XboxSeriesX_Menu",
    # o pacote so traz uma seta acesa por vez: junta duas
    "lr": ("XboxSeriesX_Dpad_Left", "XboxSeriesX_Dpad_Right"), "ud": ("XboxSeriesX_Dpad_Up", "XboxSeriesX_Dpad_Down"),
}


def botoes(orig, dest):
    os.makedirs(dest, exist_ok=True)
    achados = {}
    for raiz, _, nomes in os.walk(orig):
        for n in nomes:
            achados[os.path.splitext(n)[0]] = os.path.join(raiz, n)
    for curto, nome in ICONES.items():
        partes = nome if isinstance(nome, tuple) else (nome,)
        img = None
        for p in partes:
            atual = Image.open(achados[p]).convert("RGBA")
            img = atual if img is None else ImageChops.lighter(img, atual)
        img.resize((48, 48), Image.LANCZOS).save(os.path.join(dest, curto + ".png"), optimize=True)


def fundo(dest, w=800, h=480, horizonte=0.70):
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    u, v = x / w, y / h

    def cor(*rgb):
        return np.array(rgb, dtype=np.float32).reshape(1, 1, 3) / 255

    # ceu: azul profundo em cima, azul vivo perto do horizonte
    t = np.clip(v / horizonte, 0, 1)[..., None]
    img = cor(6, 14, 70) * (1 - t) + cor(30, 58, 190) * t

    # faixas de luz: ondas largas que se somam
    def faixa(y0, amp, freq, fase, larg):
        c = y0 + amp * np.sin(u * freq + fase) + amp * 0.4 * np.sin(u * freq * 2.3 + fase * 1.7)
        return np.exp(-((v - c) / larg) ** 2)

    img += cor(0, 190, 210) * (0.60 * faixa(0.26, 0.09, 4.0, 0.6, 0.085))[..., None]
    img += cor(150, 60, 230) * (0.55 * faixa(0.42, 0.07, 3.1, 2.4, 0.075))[..., None]
    img += cor(70, 120, 255) * (0.50 * faixa(0.14, 0.05, 5.2, 4.0, 0.06))[..., None]
    img += cor(255, 90, 190) * (0.22 * faixa(0.56, 0.05, 6.0, 1.2, 0.05))[..., None]

    # chao: claro, rosa e azul-piscina, com a luz do centro
    f = np.clip((v - horizonte) / (1 - horizonte), 0, 1)[..., None]
    chao = cor(96, 92, 205) * (1 - f) + cor(226, 170, 214) * f
    chao += cor(0, 120, 130) * (0.55 * np.exp(-((u - 0.20) / 0.22) ** 2))[..., None]
    chao += cor(0, 120, 120) * (0.45 * np.exp(-((u - 0.84) / 0.20) ** 2))[..., None]
    mistura = np.clip((v - horizonte + 0.05) / 0.10, 0, 1)[..., None]
    img = img * (1 - mistura) + chao * mistura

    img += np.random.default_rng(7).normal(0, 0.004, img.shape).astype(np.float32)   # evita faixas no degrade
    Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8)).save(dest, quality=88, optimize=True)


botoes(sys.argv[1], os.path.join(sys.argv[2], "botoes"))
fundo(os.path.join(sys.argv[2], "fundo.jpg"))
print("ok")
