"""Troca o logo do kernel (struct linux_logo CLUT224, 800x480) e remonta o zImage com o mesmo LZMA.
E o logo que o driver s3cfb mostra entre o bootloader e a animacao do Android.
O LZMA novo precisa caber exatamente no lugar do antigo (o resto do zImage fica onde estava): por isso a foto
vai menor, centrada num fundo liso, que quase nao ocupa espaco comprimido.
Uso: kernel_logo.py <zImage ou dump do mtd2> <foto> <saida zImage> [largura da foto, padrao 400]
Confere: o zImage novo descomprime para o vmlinux original com so o logo trocado."""
import lzma
import struct
import sys

from PIL import Image

LOAD = 0xC0008000          # endereco do inicio do vmlinux na memoria
FILTROS = [{"id": lzma.FILTER_LZMA1, "preset": 9 | lzma.PRESET_EXTREME, "dict_size": 1 << 26,
            "lc": 3, "lp": 0, "pb": 2, "nice_len": 64}]
src, img, out = sys.argv[1:4]
larg = int(sys.argv[4]) if len(sys.argv) > 4 else 400
cores = int(sys.argv[5]) if len(sys.argv) > 5 else 0      # menos cores = comprime melhor

z = bytearray(open(src, "rb").read())
assert struct.unpack_from("<I", z, 0x24)[0] == 0x016F2818, "nao e zImage"
z = z[:struct.unpack_from("<I", z, 0x2C)[0]]
ini = z.find(b"\x5d\x00\x00\x00\x04\xff\xff\xff\xff\xff\xff\xff\xff")
d = lzma.LZMADecompressor(format=lzma.FORMAT_ALONE)
v = bytearray(d.decompress(bytes(z[ini:])))
tam_antigo = len(z) - ini - len(d.unused_data)

p = v.find(struct.pack("<IIII", 3, 800, 480, 222)[:12])
tipo, w, h, ncor, clut_va, data_va = struct.unpack_from("<6I", v, p)
clut, data = clut_va - LOAD, data_va - LOAD
base = min(v[data:data + w * h])
print(f"LZMA antigo {tam_antigo} bytes; logo {w}x{h}, {ncor} cores, primeiro indice {base}")

foto = Image.open(img).convert("RGB")
alt = round(larg * foto.height / foto.width)
foto = foto.resize((larg, alt), Image.NEAREST if larg % foto.width == 0 else Image.LANCZOS)   # pixel art ampliado sem filtro comprime bem
tela = Image.new("RGB", (w, h), (0, 0, 0))
tela.paste(foto, ((w - larg) // 2, (h - alt) // 2))
q = tela.quantize(colors=min(cores or ncor - 1, ncor - 1), method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)
pal = q.getpalette()[:ncor * 3]
pal += [0] * (ncor * 3 - len(pal))
v[clut:clut + ncor * 3] = bytes(pal)
v[data:data + w * h] = bytes(i + base for i in q.tobytes())

novo = lzma.compress(bytes(v), format=lzma.FORMAT_ALONE, filters=FILTROS)
print(f"foto {larg}x{alt}: LZMA novo {len(novo)} bytes, sobra {tam_antigo - len(novo)}")
if len(novo) > tam_antigo:
    sys.exit("nao cabe: use uma largura menor")
assert novo[:5] == z[ini:ini + 5], "cabecalho LZMA diferente"
z[ini:ini + tam_antigo] = novo + b"\x00" * (tam_antigo - len(novo))
assert lzma.LZMADecompressor(format=lzma.FORMAT_ALONE).decompress(bytes(z[ini:])) == bytes(v)
open(out, "wb").write(z)
q.convert("RGB").save(out + ".png")
print("ok:", out, len(z), "bytes")
