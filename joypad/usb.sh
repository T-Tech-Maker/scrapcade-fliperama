# Cabo USB no PC. Quem chama e o joyd, como root. Uso: usb.sh liga | desliga
# liga: tira o cartao do Android e entrega ao PC, que o ve como pen drive (mesmo caminho do "Ativar USB" do Android).
# desliga: devolve o cartao; o fliperama organiza o que chegou em "Jogos novos".
# Sem "[": o shell do joyd nao acha esse comando no Android 2.3.
export PATH=/sbin:/system/sbin:/system/bin:/system/xbin
S=/mnt/sdcard
case "$1" in
    liga)
        sync
        vdc volume unmount $S force
        sleep 1
        vdc volume share $S ums
        ;;
    desliga)
        vdc volume unshare $S ums
        sleep 1
        vdc volume mount $S
        ;;
esac
