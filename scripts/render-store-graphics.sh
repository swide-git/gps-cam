#!/bin/bash
# Renders the Google Play graphics with headless Chrome (no ImageMagick needed):
#   docs/images/gps_cam_icon.svg            -> docs/images/gps_cam_icon.png (512x512, store icon)
#   docs/images/play_feature_graphic.html   -> docs/images/play_feature_graphic.png (1024x500)
set -euo pipefail
cd "$(dirname "$0")/../docs/images"
chrome="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
render() { # source, output, width, height
  "$chrome" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 \
    --default-background-color=00000000 --window-size="$3,$4" --screenshot="$PWD/$2" "file://$PWD/$1" 2>/dev/null
}
render gps_cam_icon.svg gps_cam_icon.png 512 512
render play_feature_graphic.html play_feature_graphic.png 1024 500
file gps_cam_icon.png play_feature_graphic.png
