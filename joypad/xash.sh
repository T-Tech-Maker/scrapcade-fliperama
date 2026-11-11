# Abre o Half-Life (Xash3D). Quem chama e o joyd, como root, no comando "xash": a tela do jogo nao e exportada
# para o fliperama, e so root libera o /dev/input/eventN do mouse para o app ler.
# Sem "[": o shell do joyd nao acha esse comando no Android 2.3.
export PATH=/sbin:/system/sbin:/system/bin:/system/xbin
PKG=in.celest.xash3d.hl
TELA=$PKG/org.libsdl.app.SDLActivity

# Ja esta aberto: so traz de volta. Com CLEAR_TOP o Android recriaria a janela por cima do motor em execucao.
ps | while read u p r; do case "$r" in *$PKG) exit 3;; esac; done
case $? in
    3) am start -n $TELA > /dev/null; exit 0;;
esac

# O motor le um mouse so. Mouse USB de verdade tem prioridade; sem ele, o analogico direito (mouse virtual do joyd).
REAL=""
VIRT=""
nome=""
while read t resto; do
    case "$t" in
        N:) nome="$resto";;
        H:)
            ev=""; rato=""
            for w in $resto; do
                case "$w" in
                    *event*) ev=${w#Handlers=};;
                    *mouse*) rato=1;;
                esac
            done
            case "$nome" in
                *"joyd mouse"*) VIRT=$ev; chmod 666 /dev/input/$ev;;
                *TouchScreen*) ;;
                *) case "$rato" in 1) REAL=$ev; chmod 666 /dev/input/$ev;; esac;;
            esac;;
    esac
done < /proc/bus/input/devices
EV=${REAL:-$VIRT}

# CLEAR_TOP: sem ele o Android ignora os argumentos novos
# touch_enable fica aqui porque o motor nao guarda esse valor ao regravar o config.cfg
am start -f 0x04000000 -n $TELA -e argv "+touch_enable 0 +evdev_mousepath /dev/input/$EV +evdev_mouseopen" > /dev/null
