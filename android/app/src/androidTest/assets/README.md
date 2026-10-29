`media-studio.mp4` is a synthetic four-second test pattern with a quiet sine-wave audio track. It contains no user media. Generate it with FFmpeg:

```sh
ffmpeg -f lavfi -i testsrc2=size=320x180:rate=15 -f lavfi -i sine=frequency=440:sample_rate=44100 -t 4 -c:v libx264 -crf 28 -pix_fmt yuv420p -c:a aac -b:a 32k -movflags +faststart -y media-studio.mp4
```
