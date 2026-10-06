#!/usr/bin/env python3
"""Regenerate train_hazards_colab.ipynb with build_dataset.py embedded (run after editing either)."""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
script = open(os.path.join(HERE, "build_dataset.py")).read()


def md(text):
    return {"cell_type": "markdown", "metadata": {}, "source": text.strip("\n").splitlines(keepends=True)}


def code(text):
    return {"cell_type": "code", "metadata": {}, "execution_count": None, "outputs": [],
            "source": text.strip("\n").splitlines(keepends=True)}


cells = [
    md("""
# CapSight - train the hazard detector (doors, stairs, poles, signs, potholes)

YOLOv8n knows 80 COCO classes but not these hazards (SRS FR-DET-02 / FR-DET-04). This notebook trains a
**separate small YOLOv8n** on public data only, so the main detector keeps everything it already knows.

| class | source |
|---|---|
| door, stairs | Open Images V7 |
| pole | Open Images "Street light" (lamp posts) |
| sign | Open Images "Traffic sign" + "Stop sign" |
| pothole | RDD2022 road-damage dataset (India + Japan, class D40) |

**Before you start:** Runtime → Change runtime type → **T4 GPU**. Then Runtime → Run all.
Building the dataset takes ~20-30 min, training ~1-2 h. Keep the tab open.

At the end `best.pt` downloads to your computer - put it in `ml/hazards/` in the CapSight repo and ask
Claude to export it to LiteRT and add it to the app.
"""),
    code("""
!nvidia-smi -L || echo "NO GPU - set Runtime > Change runtime type > T4 GPU, then run again"
"""),
    code("""
!pip -q install "ultralytics==8.4.121" remotezip
"""),
    md("""
### Keep results on Google Drive (recommended)
Colab can disconnect during long training. With `USE_DRIVE = True` the training runs are saved to
`My Drive/capsight_hazards/` and you can resume (last cell). Allow access when asked.
"""),
    code("""
USE_DRIVE = True
RUNS = "/content/runs"
if USE_DRIVE:
    from google.colab import drive
    drive.mount("/content/drive")
    RUNS = "/content/drive/MyDrive/capsight_hazards/runs"
DATA = "/content/hazards"
print("runs ->", RUNS)
"""),
    md("### 1. Build the dataset (the same script as `ml/hazards/build_dataset.py` in the repo)"),
    code("%%writefile build_dataset.py\n" + script),
    code("""
!python build_dataset.py --out {DATA} --per-class 3000 --rdd-countries India Japan
"""),
    md("""
### 2. Train
Starts from the COCO-pretrained YOLOv8n (transfer learning) at **320x320**, the size the app runs.
"""),
    code("""
from ultralytics import YOLO
model = YOLO("yolov8n.pt")
model.train(data=f"{DATA}/data.yaml", imgsz=320, epochs=80, patience=20, batch=64,
            project=RUNS, name="hazards", exist_ok=True, plots=True, seed=0)
"""),
    md("### 3. Check accuracy per class"),
    code("""
best = f"{RUNS}/hazards/weights/best.pt"
metrics = YOLO(best).val(data=f"{DATA}/data.yaml", imgsz=320)
names = ["door", "stairs", "pole", "sign", "pothole"]
print(f"{'class':10s} {'mAP50':>6s} {'mAP50-95':>9s}")
for i, n in enumerate(names):
    print(f"{n:10s} {metrics.box.ap50[i]:6.3f} {metrics.box.ap[i]:9.3f}")
print(f"{'all':10s} {metrics.box.map50:6.3f} {metrics.box.map:9.3f}")
"""),
    md("### 4. Download `best.pt` (and the training plots)"),
    code("""
import shutil
from google.colab import files
shutil.make_archive("/content/hazards_results", "zip", f"{RUNS}/hazards")
files.download(best)
files.download("/content/hazards_results.zip")
"""),
    md("""
### If Colab disconnected during training
Re-run the first four cells (GPU check, install, Drive, dataset - the dataset step reuses what it can),
then run this to continue from the last saved epoch:
"""),
    code("""
YOLO(f"{RUNS}/hazards/weights/last.pt").train(resume=True)
"""),
]

nb = {
    "cells": cells,
    "metadata": {
        "accelerator": "GPU",
        "colab": {"provenance": [], "gpuType": "T4"},
        "kernelspec": {"display_name": "Python 3", "name": "python3"},
        "language_info": {"name": "python"},
    },
    "nbformat": 4,
    "nbformat_minor": 0,
}
out = os.path.join(HERE, "train_hazards_colab.ipynb")
with open(out, "w") as f:
    json.dump(nb, f, indent=1)
print("wrote", out)
