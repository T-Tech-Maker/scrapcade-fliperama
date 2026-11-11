# Simula um botao no controle virtual do joyd (passa pelo joyd_USB_gamepad.kl).
# Uso: sh press.sh <codigo-linux>   ex.: 304 = BTN_A, 305 = BTN_B, 103 = cima
DEV=$(cat /proc/bus/input/devices | grep -A4 "joyd USB gamepad" | grep Handlers | sed 's/.*\(event[0-9]*\).*/\1/')
sendevent /dev/input/$DEV 1 $1 1
sendevent /dev/input/$DEV 0 0 0
sendevent /dev/input/$DEV 1 $1 0
sendevent /dev/input/$DEV 0 0 0
echo "apertado $1 em /dev/input/$DEV"
