#!/usr/bin/env bash
# Builds the files the lab plays, in the directory given. Takes a few minutes and
# about 6GB. Each one exists because films differ in a way that changes how they fail.
set -eu
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
DIR=$1
mkdir -p "$DIR"
cd "$DIR"

VIDEO='testsrc2=s=1280x720:r=24,noise=alls=28:allf=t'
TONE='sine=frequency=440:sample_rate=48000'
NOISE='anoisesrc=r=48000:a=0.2:c=white'
X264=(-c:v libx264 -preset ultrafast -pix_fmt yuv420p -g 96 -keyint_min 96)
cbr() { echo "-b:v $1 -minrate $1 -maxrate $1 -bufsize $1"; }

# The reference: 32Mbps, MKV, one selected audio track and two heavy unselected ones,
# the way a remux carries its other languages.
[ -f movie.mkv ] || ffmpeg -y -hide_banner -loglevel error \
  -f lavfi -i "$VIDEO" -f lavfi -i "$TONE" -f lavfi -i "$NOISE" -t 480 \
  -map 0:v -map 1:a -map 2:a -map 2:a "${X264[@]}" $(cbr 18M) \
  -c:a:0 aac -b:a:0 192k -ac:a:0 2 -c:a:1 pcm_s24le -ac:a:1 6 -c:a:2 pcm_s24le -ac:a:2 6 \
  -disposition:a:0 default -disposition:a:1 0 -disposition:a:2 0 movie.mkv

# A 4K remux's bitrate without a 4K decode: the bytes are in tracks nobody decodes, so
# what it stresses is the budget and the link rather than this machine's decoder.
[ -f high.mkv ] || ffmpeg -y -hide_banner -loglevel error \
  -f lavfi -i "$VIDEO" -f lavfi -i "$TONE" -f lavfi -i "$NOISE" -t 180 \
  -map 0:v -map 1:a -map 2:a -map 2:a -map 2:a -map 2:a -map 2:a -map 2:a "${X264[@]}" $(cbr 18M) \
  -c:a:0 aac -b:a:0 192k -ac:a:0 2 -c:a:1 pcm_s24le -ac:a:1 8 -c:a:2 pcm_s24le -ac:a:2 8 \
  -c:a:3 pcm_s24le -ac:a:3 8 -c:a:4 pcm_s24le -ac:a:4 8 -c:a:5 pcm_s24le -ac:a:5 8 \
  -c:a:6 pcm_s24le -ac:a:6 8 -disposition:a:0 default \
  -disposition:a:1 0 -disposition:a:2 0 -disposition:a:3 0 -disposition:a:4 0 -disposition:a:5 0 -disposition:a:6 0 \
  high.mkv

# A small encode: the whole film can fit in the heap, so damage sits far ahead of the
# playhead for minutes before it is reached.
[ -f low.mkv ] || ffmpeg -y -hide_banner -loglevel error \
  -f lavfi -i "$VIDEO" -f lavfi -i "$TONE" -t 300 -map 0:v -map 1:a "${X264[@]}" $(cbr 3M) \
  -c:a aac -b:a 128k -ac 2 low.mkv

# MP4, a different demuxer entirely, with its index at the end as most encoders leave it.
[ -f movie.mp4 ] || ffmpeg -y -hide_banner -loglevel error \
  -f lavfi -i "$VIDEO" -f lavfi -i "$TONE" -t 300 -map 0:v -map 1:a "${X264[@]}" $(cbr 10M) \
  -c:a aac -b:a 192k -ac 2 movie.mp4

# The same with the index up front, as "faststart" files have it.
[ -f fast.mp4 ] || ffmpeg -y -hide_banner -loglevel error -i movie.mp4 -c copy -movflags +faststart fast.mp4

# MKV with no index at all: nothing can seek in it, which a skip past damage relies on.
[ -f nocues.mkv ] || ffmpeg -y -hide_banner -loglevel error \
  -f lavfi -i "$VIDEO" -f lavfi -i "$TONE" -t 180 -map 0:v -map 1:a "${X264[@]}" $(cbr 10M) \
  -c:a aac -b:a 128k -ac 2 -f matroska -write_crc32 0 -reserve_index_space 0 pipe:1 > nocues.mkv

# Every audio block for a stretch of film before any video for it, so the allocator can
# fill with the video queue empty.
[ -f interleaved.mkv ] || python3 -I "$HERE/make_bad_interleave.py" movie.mkv interleaved.mkv 240 120

ls -la "$DIR"
