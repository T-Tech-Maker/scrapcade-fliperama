# Tutorial de instalação

Este tutorial mostra como montar o ScrapCade num iChroma II (ou aparelho parecido com Android 2.3.3 e root).
**Leia antes de começar.** Mexer no sistema do aparelho pode deixá-lo inutilizável, e a garantia do fabricante deixa de valer.

## Antes de começar

- **Use só aparelho sem dados de paciente.** Apague qualquer dado clínico antes, e faça cópia da memória interna se for mexer no sistema.
- Você precisa de: computador com Windows, cabo USB, cartão microSD, rede Wi-Fi ou cabo de rede, e acesso root no aparelho.
- Os emuladores e os jogos **não vêm neste repositório**. Você precisa instalá-los e ter os jogos de sua propriedade. Veja a seção "Emuladores" abaixo.

## 1. Ferramentas no computador

1. **Android SDK Platform-Tools** (`adb`) e **build-tools 37** (`aapt`, `d8`, `zipalign`, `apksigner`).
2. **JDK** com `javac` e `keytool` no PATH.
3. **Zig 0.17** (compilador C para o driver). Coloque em `C:\zig\zig.exe` ou no PATH.
4. **Python 3.12** com a biblioteca Pillow, se for usar os scripts de `art_src`.

## 2. Ligar o adb pela rede

No aparelho, o sistema precisa ter o `adb` pela rede na porta 5555 (veja `MAPA_DO_CARTAO.md`, no repositório privado, seção FLIPERAMA 1). Depois, no computador:

```bash
adb connect IP_DO_APARELHO:5555
adb devices
```

Troque `IP_DO_APARELHO` pelo IP que aparece na barra de cima do menu.

## 3. Compilar e instalar o menu (launcher)

```powershell
cd launcher
.\build.ps1
adb -s IP_DO_APARELHO:5555 install -r launcher.apk
```

O `build.ps1` gera o `launcher.apk` assinado para Android 2.3. Se o SDK não estiver em `%LOCALAPPDATA%\Android\Sdk`, defina a variável `ANDROID_HOME`.

## 4. Compilar e instalar o driver do controle (joyd)

```powershell
cd joypad
.\build.ps1
adb -s IP_DO_APARELHO:5555 push joyd /data/local/tmp/joyd2
adb -s IP_DO_APARELHO:5555 push install_joyd.sh /data/local/tmp/
adb -s IP_DO_APARELHO:5555 push joyd_USB_gamepad.kl xash.sh usb.sh n64.sh /data/local/tmp/
adb -s IP_DO_APARELHO:5555 shell "sh /data/local/tmp/install_joyd.sh"
```

O `install_joyd.sh` copia o driver para `/system/xbin`, põe o controle no layout de teclas e faz o driver iniciar com o sistema.

## 5. Pastas no cartão

Crie no cartão (com o menu aberto no aparelho ou pelo adb):

```
roms/            biblioteca, dividida por console pelo próprio menu
Jogos novos/     entrada: copie os jogos aqui
```

O menu cria as pastas de console (`psx`, `gba`, `n64`, `megadrive` e assim por diante) sozinho quando você copia os jogos.

## 6. Emuladores

Instale os emuladores separadamente. Eles não vêm neste repositório:

| Uso | Emulador | Onde conseguir |
|---|---|---|
| PS1, SNES, Mega Drive, NES, Arcade, Neo Geo | RetroArch 0.9.9.6 + núcleos libretro | F-Droid / buildbot.libretro.com |
| Nintendo 64 | Mupen64Plus AE 2.4.4 | F-Droid (arquivo `paulscode.android.mupen64plusae_363.apk`) |
| Game Boy / GBA | GBCoid, My Boy! Free | Mercado de apps antigo ou arquivos oficiais |
| Doom e similares | RetroArch com PrBoom | o mesmo RetroArch |
| Half-Life | Xash3D 0.17.1 + dados do jogo | xash3d.su |

Os núcleos do RetroArch precisam ir para `/data/local/cores/`. No aparelho, alguns núcleos só carregam com a biblioteca `libcx.so`, que está em `emuladores/cores/libcx.c` no repositório privado.

## 7. Testar

1. Copie um jogo de PS1 para `Jogos novos/`.
2. Abra o menu. Em uns segundos ele move o jogo para `roms/psx` e baixa a capa, se houver rede.
3. Escolha o jogo com o controle e aperte **A**.

Para voltar ao menu, aperte **Select + Start** (ou o botão central do controle).

## Problemas comuns

- **Controle não responde:** confira no computador se o aparelho vê o controle com `adb shell cat /proc/bus/input/devices`. O log do driver fica em `/data/local/tmp/joyd.log`.
- **Jogo não abre:** veja o log do RetroArch com `adb logcat -d -s RetroArch:*`.
- **Aparelho reinicia ao abrir jogo com som:** o áudio original do aparelho faz isso. Esta versão deixa o som desligado de propósito.

## Antes de publicar ou compartilhar

Nunca publique ROMs, BIOS, firmware, backups ou dados de aparelhos de terceiros. Este repositório contém só o código e a documentação.
