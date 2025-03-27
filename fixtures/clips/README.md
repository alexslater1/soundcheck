# fixtures/clips
Synthetic test clips made by `make clips` (`ops/scripts/make_clips.py`): 20 files, about 208 MB, generated in about three minutes. Gitignored; this README is the only tracked file.

All of it is original material from FFmpeg's own signal generators (test patterns, a Mandelbrot zoom, a game of life, colour bars) with a tone or pink noise and a drawn label, so it can be used freely in tests, demo seeding and load tests.

The set covers the pipeline's code paths: landscape, portrait and square; 480p to 1080p; 24, 25, 30 and 60 fps; 8 to 30 s; two clips without an audio track (09, 17); two stored sideways with a rotation flag, as phones record (15, 16); five larger than 16 MB, which upload as multipart (04, 05, 07, 19, 20).

`make clips-check` regenerates the set into a temporary directory and compares checksums with `SHA256SUMS`: the same script and the same FFmpeg build give byte-identical files. Checksums are not committed because another FFmpeg version may encode differently.
