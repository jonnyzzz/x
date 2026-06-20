#!/usr/bin/env sh
set -eu

: "${DISPLAY:=host.docker.internal:0}"
export DISPLAY

twm >/tmp/twm.log 2>&1 &
sleep 1
xlogo -geometry 360x240+120+100 >/tmp/xlogo.log 2>&1 &
xclock -geometry 360x240+320+260 >/tmp/xclock.log 2>&1 &
xeyes -geometry 360x240+560+420 >/tmp/xeyes.log 2>&1 &

tail -f /tmp/twm.log /tmp/xlogo.log /tmp/xclock.log /tmp/xeyes.log
