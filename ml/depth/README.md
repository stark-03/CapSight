# Depth model (FR-OBS-04 / FR-PATH-03)

**Model:** Intel MiDaS v2.1 small, official TFLite ("v2-1-small-lite") from
<https://www.kaggle.com/models/intel/midas> (MIT licence). 66 MB, float32.
Downloaded 22 Oct 2026 with:
`curl -L -o midas.tar.gz https://www.kaggle.com/api/v1/models/intel/midas/tfLite/v2-1-small-lite/1/download`
(the archive contains `1.tflite`, saved here as `midas_v2_1_small_256.tflite`; git-ignored).

**I/O:** input `[1, 256, 256, 3]` float32 RGB, ImageNet-normalised (mean 0.485/0.456/0.406,
std 0.229/0.224/0.225), NHWC. Output `[1, 256, 256, 1]` float32 **relative inverse depth**
(bigger = nearer; no metric scale). About 27 ms per frame on the laptop CPU (4 threads).

**How CapSight uses it (conservative, decided 22 Oct 2026):** score each zone as
`p90(inverse depth in the zone's body-height band) / median(inverse depth of the floor patch just
ahead)`. A score of ~1.0 means "as near as the floor right in front" (about arm's length).
Only a sustained centre score >= 1.0 (about 1 s), not already explained by a YOLO detection,
produces "Stop. Obstacle ahead." Lower scores are shown in the debug view only.

**Why so conservative - evaluation on the team's 12 classroom photos (`eval_photos.py`):**
| photo | ahead | centre p90 score |
|---|---|---|
| closed door at ~1 m | near | 1.01 |
| desk + chair at ~1 m | near | 1.04 |
| podium at ~1.5 m | near | 0.90 |
| wall with a window at ~1 m | near | 0.91-0.93 (glass reads as far) |
| long open corridor | clear | 0.46 |
| corridor with a parapet + pillars on the left | clear | 0.92 (pillars read as near) |
| open hall | clear | 0.43 |
A lower threshold (0.85) would catch the podium and the window walls but also false-alarm in the
pillared corridor, so the default favours no false "Stop" in corridors; tune on site.
