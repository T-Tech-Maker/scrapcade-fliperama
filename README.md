# ScrapCade: fliperama no Android 2.3

Menu de jogos (estilo "capas em carrossel") e driver de controle para um aparelho Android 2.3.3 com 512 MB de RAM,
processador S5PV210 de 1 GHz e saída de vídeo de 800x480, que foi reaproveitado como fliperama.

## O que tem aqui

- `launcher/`: o app de menu (Java, Android 2.3). Carrossel de capas pela GPU, favoritos, mais jogados, fundo do jogo,
  gerenciador de arquivos pelo controle e pela página no navegador (porta 8080), e organizador da pasta "Jogos novos".
- `joypad/`: `joyd`, o driver do controle em C sem libc (roda como root no aparelho). Lê comandos de um arquivo
  (`game`, `ra <núcleo> <jogo>`, `n64 <jogo>`, `xash`, `hora`, `gpu`, `reboot`), converte o controle USB em teclas
  e abre os emuladores.
- `art_src/`: scripts que geram a arte e os índices de dados (CRC, nomes, metadados) a partir de fontes públicas.

## Emuladores usados

Nenhum emulador ou jogo está neste repositório. Os emuladores são instalados à parte:
RetroArch 0.9.9.6 com núcleos libretro (FB Alpha 2012, SNES9x 2005, PicoDrive, QuickNES, PCSX ReARMed, PrBoom),
Mupen64Plus AE 2.4.4, My Boy! Free e Xash3D.

## Limitações conhecidas

- O som está desligado: a biblioteca de áudio original do aparelho reinicia o sistema ao abrir um jogo com áudio.
- Nintendo 64 roda a 17–39 quadros por segundo, conforme o jogo.
- Final Fight (versão EUA de 1990) não roda no FB Alpha 2012.

## Como compilar

Precisa de Android SDK (build-tools 37), JDK e, para o driver, Zig 0.17.

```powershell
cd launcher; .\build.ps1      # gera launcher.apk
cd joypad;   .\build.ps1      # gera joyd (ARM estático)
```

O aparelho precisa estar com root e com o sistema alterado para aceitar o driver. Este repositório não traz isso.

## Licenças

- Ícones de sistema: libretro/retroarch-assets, CC-BY 4.0 (ver `launcher/assets/icons/CREDITOS.txt`).
- Botões: Xelu's Free Controller Prompts, CC0.
- Índices de CRC e de nomes: derivados de bases públicas (ver `art_src/gera_crc.py`). **Conferir a licença antes de publicar.**
