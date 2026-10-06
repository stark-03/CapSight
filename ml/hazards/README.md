# Hazard detector (doors, stairs, poles, signs, potholes)

YOLOv8n's 80 COCO classes do not include these hazards (SRS FR-DET-02, FR-DET-04). CapSight runs a
**second, separate YOLOv8n** trained on them, next to the COCO model, so nothing the main model
already detects is lost. Walls are not here: they need the depth model (FR-OBS-04), planned next.

| id | class | public source | notes |
|---|---|---|---|
| 0 | door | Open Images V7 "Door" | |
| 1 | stairs | Open Images V7 "Stairs" | also contains escalators |
| 2 | pole | Open Images V7 "Street light" | lamp posts; boxes usually cover the post (median 4:1 tall) |
| 3 | sign | Open Images V7 "Traffic sign" + "Stop sign" | |
| 4 | pothole | RDD2022 class D40, India + Japan | India alone: 1530 images, 3187 potholes |

Public data only (team decision, 7 Oct 2026). Open Images labels are not exhaustive (some poles and
doors in the photos are unlabelled), which costs some accuracy; your own photos would improve it later.
Credit in the report: Open Images (annotations CC BY 4.0, images CC BY 2.0) and RDD2022 (CC BY 4.0,
Arya et al., CRDDC 2022).

## Steps

1. **Train in Google Colab** (this laptop has no NVIDIA GPU):
   open <https://colab.research.google.com> → File → Upload notebook → `train_hazards_colab.ipynb`,
   Runtime → Change runtime type → **T4 GPU**, Runtime → **Run all**. About 2-3 hours in total.
   At the end it downloads `best.pt` and `hazards_results.zip`.
2. **Put `best.pt` in this folder** (`ml/hazards/best.pt`).
3. **Export to LiteRT** on the laptop (same toolchain as the main model, CLAUDE.md §7.1):
   ```bash
   yolo export model=ml/hazards/best.pt format=litert imgsz=320
   # copy the .tflite to app/src/main/assets/hazards_320_fp32.tflite
   ```
   The app loads it automatically when the file is present; without it the app works as before.

## Files

- `build_dataset.py` - downloads and converts the data to YOLO format. Fetches only the needed parts
  of RDD2022's 13 GB zip. Quick local check:
  `python build_dataset.py --out /tmp/tiny --oi-split validation --per-class 8 --rdd-countries India --rdd-max 8`
- `train_hazards_colab.ipynb` - the Colab notebook (build dataset, train at 320, per-class mAP, download).
- `make_notebook.py` - regenerates the notebook after editing `build_dataset.py`.
