#!/bin/sh
. "$(dirname "$0")/control-test-lib.inc"
cfg="$ROOT/opt/solarus/pads.ini"; mkdir -p "$(dirname "$cfg")"
printf 'up=button 8\ndown=button 9\nleft=button 10\nright=button 11\npause=button 13\naction=button 0\nattack=button 1\nitem_1=button 2\nitem_2=button 3\n' > "$cfg"
set_solarus_input_binds
primed set_solarus_input_binds
for pair in up:10 down:13 left:11 right:12 pause:8 action:0 attack:1 item_1:2 item_2:3; do exact "$cfg" "${pair%:*}=button ${pair#*:}"; done
repeat set_solarus_input_binds
echo 'F9 Solarus stored native template passed'
