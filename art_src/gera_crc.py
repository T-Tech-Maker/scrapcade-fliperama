"""Gera assets/crc/<id>.idx: para cada jogo, CRC -> nome oficial, ano, empresa e genero (classes Meta e Capas).
Base: libretro-database (No-Intro + metadat/releaseyear, developer, publisher, genre).
Linha: CRC nome<TAB>ano<TAB>empresa<TAB>genero (campos vazios quando a base nao tem).
Uso: py -3.12 -I gera_crc.py <pasta de cache> <pasta assets> [so estes ids, ex.: n64]"""
import gzip
import os
import re
import sys
import urllib.parse
import urllib.request

SISTEMAS = {
    "gb": "Nintendo - Game Boy",
    "gbc": "Nintendo - Game Boy Color",
    "gba": "Nintendo - Game Boy Advance",
    "nes": "Nintendo - Nintendo Entertainment System",
    "snes": "Nintendo - Super Nintendo Entertainment System",
    "md": "Sega - Mega Drive - Genesis",
    "sms": "Sega - Master System - Mark III",
    "gg": "Sega - Game Gear",
    "n64": "Nintendo - Nintendo 64",
}
RAIZ = "https://raw.githubusercontent.com/libretro/libretro-database/master/metadat/"
cache, assets = sys.argv[1], sys.argv[2]
SO = set(sys.argv[3:])
os.makedirs(os.path.join(assets, "crc"), exist_ok=True)


def baixa(tipo, nome):
    dat = os.path.join(cache, (nome if tipo == "no-intro" else f"{tipo} - {nome}") + ".dat")
    if not os.path.exists(dat):
        try:
            urllib.request.urlretrieve(RAIZ + tipo + "/" + urllib.parse.quote(nome) + ".dat", dat)
        except Exception as e:
            print("   sem", tipo, "para", nome, "-", e)
            return None
    return dat


def campo(dat, chave):
    """{CRC: valor} de um dat de metadados."""
    res, valor, crcs = {}, None, []
    if not dat:
        return res
    for l in open(dat, encoding="utf-8", errors="replace"):
        m = re.match(r'\s*' + chave + r' "(.*)"', l)
        if m:
            valor = m.group(1)
        crcs += re.findall(r'crc ([0-9A-Fa-f]{8})', l)
        if l.strip() == ")":
            if valor:
                for c in crcs:
                    res[c.upper()] = valor.replace("\t", " ")
            valor, crcs = None, []
    return res


for curto, nome in SISTEMAS.items():
    if SO and curto not in SO:
        continue
    nomes = campo(baixa("no-intro", nome), "name")
    ano = campo(baixa("releaseyear", nome), "releaseyear")
    dev = campo(baixa("developer", nome), "developer")
    pub = campo(baixa("publisher", nome), "publisher")
    gen = campo(baixa("genre", nome), "genre")
    linhas = [f"{c} {n}\t{ano.get(c, '')}\t{dev.get(c) or pub.get(c, '')}\t{gen.get(c, '')}\n" for c, n in nomes.items()]
    destino = os.path.join(assets, "crc", curto + ".idx")
    with gzip.open(destino, "wt", encoding="utf-8", compresslevel=9) as f:
        f.writelines(linhas)
    com = sum(1 for c in nomes if c in ano or c in dev or c in pub or c in gen)
    print(f"{curto}: {len(linhas)} jogos, {com} com dados, {os.path.getsize(destino)} bytes")
