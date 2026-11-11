# Instala o joyd no iChroma (rodar como root via adb shell sh install_joyd.sh)
# - copia o binario para /system/xbin/joyd
# - pendura "joyd -d" no fim de /system/etc/marvell_bt.sh (servico mv_bt, roda como root no boot)
# - reinicia o daemon
echo "== parando joyd antigo"
for p in $(ps | grep joyd | grep -v grep | while read u pid rest; do echo $pid; done); do
  echo "matando $p"; kill $p
done
sleep 2

echo "== /system (precisa estar rw)"
mount | grep system
mount -o remount,rw ubi0:system /system 2>/dev/null

echo "== copiando binario"
cat /data/local/tmp/joyd2 > /system/xbin/joyd
chmod 755 /system/xbin/joyd
ls -l /system/xbin/joyd

echo "== layout de teclas do controle virtual"
cat /data/local/tmp/joyd_USB_gamepad.kl > /system/usr/keylayout/joyd_USB_gamepad.kl
chmod 644 /system/usr/keylayout/joyd_USB_gamepad.kl
ls -l /system/usr/keylayout/

echo "== abertura do Half-Life (comando xash do joyd)"
cat /data/local/tmp/xash.sh > /data/local/xash.sh
chmod 755 /data/local/xash.sh
cat /data/local/tmp/usb.sh > /data/local/usb.sh
chmod 755 /data/local/usb.sh
cat /data/local/tmp/n64.sh > /data/local/n64.sh
chmod 755 /data/local/n64.sh

echo "== boot (mv_bt)"
if grep -q joyd /system/etc/marvell_bt.sh; then
  echo "ja estava no marvell_bt.sh"
else
  echo "/system/xbin/joyd -d" >> /system/etc/marvell_bt.sh
fi
cat /system/etc/marvell_bt.sh
sync

echo "== iniciando"
/system/xbin/joyd -d
sleep 3
ps | grep joyd | grep -v grep
echo "-- log"
cat /data/local/tmp/joyd.log
echo "-- dispositivos de entrada"
cat /proc/bus/input/devices | grep Name
sleep 2
echo "-- como o Android ve o controle"
dumpsys window | grep -A6 "joyd"
