#!/usr/bin/env bash
set -euo pipefail
: "${MEDIA_DIR:=.runtime/media}"
mkdir -p "$MEDIA_DIR"
make_sequence() {
  local name=$1 colors=$2 tones=$3
  IFS=, read -r c1 c2 c3 <<< "$colors"
  IFS=, read -r t1 t2 t3 <<< "$tones"
  ffmpeg -hide_banner -loglevel error -y \
    -f lavfi -i "color=c=$c1:s=640x360:r=30:d=1" -f lavfi -i "sine=frequency=$t1:sample_rate=48000:duration=1" \
    -f lavfi -i "color=c=$c2:s=640x360:r=30:d=1" -f lavfi -i "sine=frequency=$t2:sample_rate=48000:duration=1" \
    -f lavfi -i "color=c=$c3:s=640x360:r=30:d=1" -f lavfi -i "sine=frequency=$t3:sample_rate=48000:duration=1" \
    -filter_complex '[0:v][1:a][2:v][3:a][4:v][5:a]concat=n=3:v=1:a=1[v][a]' \
    -map '[v]' -map '[a]' -c:v libx264 -pix_fmt yuv420p -c:a aac -movflags +faststart "$MEDIA_DIR/$name.mp4"
}
make_sequence red red,yellow,green 440,660,990
make_sequence blue purple,blue,white 220,880,1100
ffmpeg -hide_banner -loglevel error -y -f lavfi -i 'testsrc2=s=360x640:r=24:d=3' -c:v libx264 -pix_fmt yuv420p "$MEDIA_DIR/silent.mp4"
ffmpeg -hide_banner -loglevel error -y -i "$MEDIA_DIR/red.mp4" -c copy -metadata:s:v:0 rotate=90 "$MEDIA_DIR/rotated.mov"
ffmpeg -hide_banner -loglevel error -y -i "$MEDIA_DIR/red.mp4" -vf "select='not(mod(n,3))',setpts=PTS+2/TB" -af 'asetpts=PTS+2/TB' -fps_mode vfr -c:v libx264 -c:a aac "$MEDIA_DIR/vfr-pts.mp4"
ffmpeg -hide_banner -loglevel error -y -f lavfi -i 'color=c=green:s=320x240:r=25:d=3' -f lavfi -i 'sine=frequency=660:sample_rate=44100:duration=0.5' -c:v libx264 -pix_fmt yuv420p -c:a aac "$MEDIA_DIR/short-audio.mp4"
printf 'not a media container' > "$MEDIA_DIR/corrupt.mp4"
