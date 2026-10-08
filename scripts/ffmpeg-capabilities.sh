#!/usr/bin/env bash
set -euo pipefail
ffmpeg -version | head -n 1
encoders=$(ffmpeg -hide_banner -encoders 2>/dev/null)
filters=$(ffmpeg -hide_banner -filters 2>/dev/null)
for encoder in libx264 aac; do [[ "$encoders" == *" $encoder "* ]] || exit 1; done
for filter in trim atrim setpts asetpts scale crop pad fps concat aresample apad; do [[ "$filters" == *" $filter "* ]] || exit 1; done
ffprobe -version | head -n 1
