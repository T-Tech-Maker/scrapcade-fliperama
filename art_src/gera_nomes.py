"""Gera assets/fbneo.idx: nome do zip -> nome oficial, ano e empresa, para o menu e as capas de arcade e Neo Geo.
Base: libretro-database (metadat/fbneo-split/FBNeo - Arcade Games.dat). Linha: zip<TAB>nome<TAB>ano<TAB>empresa.
Uso: py -3.12 -I gera_nomes.py <FBNeo - Arcade Games.dat> <pasta assets>"""
import gzip
import os
import re
import sys

linhas, nome, ano, emp = [], None, "", ""
for l in open(sys.argv[1], encoding="utf-8", errors="replace"):
    m = re.match(r'\s*name "(.*)"', l)
    if m:
        nome, ano, emp = m.group(1), "", ""
    m = re.match(r'\s*releaseyear "(.*)"', l)
    if m:
        ano = m.group(1)
    m = re.match(r'\s*(?:publisher|developer) "(.*)"', l)
    if m and not emp:
        emp = m.group(1)
    m = re.match(r'\s*rom \( name (\S+)\.zip ', l)
    if m and nome:
        linhas.append(f"{m.group(1).lower()}\t{nome}\t{ano}\t{emp}\n")
        nome = None
destino = os.path.join(sys.argv[2], "fbneo.idx")
with gzip.open(destino, "wt", encoding="utf-8", compresslevel=9) as f:
    f.writelines(linhas)
print(len(linhas), os.path.getsize(destino))
