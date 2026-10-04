#!/bin/bash
# Boulder: die Referenzbilder fuer TsZifferblattReferenzTest neu machen - aus
# dem Emulator (QEMU, emery, 64 Farben) mit dem Pruefbau TS_DEMO der Firmware.
#
# WARUM TS_DEMO: der Bau zeigt feste Werte (Sa 3.10.2026 15:45, 22/24/12 Grad,
# UV 3, 5523 Schritte, 4230 m, Puls 62, Schlaf 7h 23m / 2h 5m, Akku 80 %) -
# nur so ist jedes Bild wiederholbar und der Test kann denselben Zustand in
# den Nachbau geben. Ein Release mit TS_DEMO bricht der Bau ab (pebble.h).
#
# Aufruf: erzeugen.sh   (PEBBLEOS = Firmware-Checkout, Vorgabe ~/Projects/pebbleos)
# Braucht: SDK unter ~/pebbleos-sdk-0.1.8, die venv des Firmware-Checkouts.
# Ablauf: Bau mit -DTS_DEMO, QEMU frisch starten, fuer jede Zeile von
# liste.txt (name|24/12|km/mi|Optionen von "pbl feed timestyle") die Werte
# clock24h, unitsDistance und tsBedrock so wie Boulder setzen (BlobDB mit
# Zeitstempel), Licht an (Taste zurueck), Bild per screendump. Schreibt die
# PNGs und referenzen.txt (name|24/12|km/mi|tsBedrock-Hex) in diesen Ordner.
set -eu
HIER="$(cd "$(dirname "$0")" && pwd)"
FW="${PEBBLEOS:-$HOME/Projects/pebbleos}"
BAU="$FW/build/ts-referenzen"
PORT=47344
MON="$BAU/mon.sock"
cd "$FW"
. "$HOME/pebbleos-sdk-0.1.8/env.sh"
. .venv/bin/activate
[ -f "$BAU/build.ninja" ] || pbl configure -b "$BAU" --board qemu_emery -DCMAKE_C_FLAGS=-DTS_DEMO >/dev/null
pbl build -b "$BAU" >/dev/null
cmake --build "$BAU" --target qemu_image_micro qemu_image_spi >/dev/null
cp "$BAU/qemu_spi_flash.bin" "$BAU/spi.bin"
rm -f "$MON"
qemu-pebble -rtc base=localtime -monitor "unix:$MON,server=on,wait=off" \
  -serial null -serial "tcp::$PORT,server=on,wait=off" -serial "tcp::$((PORT + 1)),server=on,wait=off" \
  -machine pebble-emery,audiodev=snd0 -audiodev none,id=snd0 \
  -kernel "$BAU/pebbleos.elf" -drive "if=mtd,format=raw,file=$BAU/spi.bin" -display none &
QEMU=$!
trap 'kill $QEMU 2>/dev/null' EXIT
sleep 25

monitor() {  # monitor <befehl>...: Befehle an den QEMU-Monitor, je 0,6 s Pause
  python3 - "$MON" "$@" <<'PY'
import socket, sys, time
s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
s.connect(sys.argv[1])
for b in sys.argv[2:]:
    s.sendall((b + "\n").encode())
    time.sleep(0.6)
PY
}

setzen() {  # setzen <schluessel> <hex> ...: Uhreinstellungen wie Boulder, mit Zeitstempel
  python3 - "$PORT" "$@" <<'PY'
import sys, time
sys.path.insert(0, "tools/libs/pbl-cli")
from pbl import emulator
from pbl.feeds import BlobDb, Watch
w = Watch(emulator.connect_watch("127.0.0.1", int(sys.argv[1])))
for i in range(2, len(sys.argv), 2):
    w.blobdb_insert_with_timestamp(BlobDb.SETTINGS, sys.argv[i].encode() + b"\0",
                                   bytes.fromhex(sys.argv[i + 1]), int(time.time()))
PY
}

kodieren() {  # kodieren <Optionen>: der tsBedrock-Wert wie "pbl feed timestyle"
  python3 - "$@" <<'PY'
import sys, argparse
sys.path.insert(0, "tools/libs/pbl-cli")
from pbl.feeds import timestyle
p = argparse.ArgumentParser()
timestyle.TimeStyle.add_arguments(None, p)
print(timestyle.kodieren(p.parse_args(sys.argv[1:])).hex())
PY
}

# Aus dem Startmenue zum Zifferblatt.
monitor "sendkey left" "sendkey left"
: > "$HIER/referenzen.txt"
while IFS='|' read -r name stunden einheit optionen; do
  [ -z "$name" ] && continue
  case "$name" in \#*) continue ;; esac
  # shellcheck disable=SC2086
  hex=$(kodieren $optionen)
  [ "$stunden" = "24" ] && h24=01 || h24=00
  [ "$einheit" = "km" ] && km=00 || km=01
  setzen clock24h "$h24" unitsDistance "$km" tsBedrock "$hex"
  sleep 1.5
  monitor "sendkey left" "screendump $HIER/$name.png -f png"
  echo "$name|$stunden|$einheit|$hex" >> "$HIER/referenzen.txt"
  echo "$name"
done < "$HIER/liste.txt"
