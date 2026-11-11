# Abre um jogo de Nintendo 64 no Mupen64Plus AE 2.4.4. Quem chama e o joyd, como root, no comando "n64 <rom>":
# so root escreve nas preferencias do emulador. O emulador nao aceita o jogo por Intent direto na tela do jogo
# (pelo VIEW ele para no menu dele), entao o jogo vai para pathSelectedGame e abre a GameActivity.
# Ao sair (botao central), o emulador guarda o ponto em /sdcard/mupen64plus/AutoSaves e continua dali na volta.
# Sem "[": o shell do joyd nao acha esse comando no Android 2.3.
export PATH=/sbin:/system/sbin:/system/bin:/system/xbin:/bin
PKG=paulscode.android.mupen64plusae
P=/data/data/$PKG/shared_prefs/${PKG}_preferences.xml
ROM="$1"
T=/data/local/n64.tmp

# fecha o que estiver aberto: so assim ele le as preferencias de novo
for i in 1 2; do
    ps | while read u p r; do case "$r" in *$PKG) kill -9 $p;; esac; done
    sleep 1
done

# primeira vez: o emulador ainda nao criou as preferencias, abre o menu dele
ls $P > /dev/null 2>&1 || { am start -n $PKG/.MainActivity > /dev/null; exit 0; }

# troca o jogo e garante o resto da configuracao (controle do joyd, sem botoes na tela, sem contador de quadros)
grep -v -E 'name="(pathSelectedGame|touchscreenEnabled|inputMapString1|inputEnabled1|videoFpsRefresh|videoResolution|pluginAudio)"' $P | grep -v '</map>' > $T
# se o grep falhou, nao estraga as preferencias (aconteceu no teste de 10/11/2026: sem /bin no PATH sobrou so o fim do arquivo)
grep -q 'name="pluginRsp"' $T || { rm $T; am start -n $PKG/.MainActivity > /dev/null; exit 1; }
echo "<string name=\"pathSelectedGame\">$ROM</string>" >> $T
# sem modulo de som: o aparelho e mudo (libaudio com stub) e o SDL gastava CPU misturando audio. Medido no DK64:
# 3 threads a menos e a abertura andou mais rapido no mesmo tempo
echo '<string name="pluginAudio"></string>' >> $T
echo '<boolean name="touchscreenEnabled" value="false" />' >> $T
echo '<boolean name="inputEnabled1" value="true" />' >> $T
# N64: analogico esquerdo como direcional (teclas 19-22; o eixo analogico nao e reconhecido pelo Android 2.3); A=A, X=B, LT=Z, RB=R, LB=L, Y/B/L3/RT = C cima/baixo/esq/dir
echo '<string name="inputMapString1">16:22,17:21,18:20,19:19,7:23,6:99,5:104,4:108,12:103,13:102,11:100,10:101,9:106,8:105</string>' >> $T
echo '<string name="videoFpsRefresh">0</string>' >> $T
echo '<string name="videoResolution">360</string>' >> $T  # 360 medido: DK64 de 15 para 22 quadros/s; 240 deu 18 e a imagem escurece
echo '</map>' >> $T
cat $T > $P
rm $T

am start -n $PKG/.GameActivity > /dev/null
