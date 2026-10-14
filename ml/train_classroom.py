"""
CapSight - train a classroom-specific YOLOv8n from the team's own photos.

Pipeline (CPU only is fine):
  1. label   : YOLO-World (open-vocabulary detector) draws boxes on every photo in
               ml/dataset/raw/ for the CLASSES below and writes YOLO-format labels.
               Preview images with the boxes go to ml/dataset/preview/ for checking.
  2. train   : fine-tunes ml/yolov8n.pt (COCO-pretrained) on those labels at 320 px.
  3. export  : writes a TFLite model + labels file to ml/export/ for the Android app.

Run with the Phase-2 conda env (has ultralytics):
  ~/miniconda3/envs/blenav/bin/python ml/train_classroom.py all
  ~/miniconda3/envs/blenav/bin/python ml/train_classroom.py label     # one step at a time
"""
from __future__ import annotations

import random
import shutil
import sys
from pathlib import Path

ML = Path(__file__).resolve().parent
RAW = ML / "dataset" / "raw"
YOLO_DIR = ML / "dataset" / "yolo"
PREVIEW = ML / "dataset" / "preview"
RUNS = ML / "runs"
EXPORT = ML / "export"

# Final classes the app will see, in this order (index = class id).
CLASSES = ["person", "chair", "desk", "bench", "door", "board", "bag", "bottle", "laptop", "stairs",
           "pillar", "dustbin", "podium"]

# Text prompts for YOLO-World -> final class. Several prompts may map to one class.
PROMPTS = {
    "person": "person",
    "chair": "chair",
    "desk": "desk",
    "table": "desk",
    "bench": "bench",
    "door": "door",
    "wooden door": "door",
    "whiteboard": "board",
    "blackboard": "board",
    "backpack": "bag",
    "bag": "bag",
    "bottle": "bottle",
    "laptop": "laptop",
    "staircase": "stairs",
    "pillar": "pillar",
    "concrete column": "pillar",
    "dustbin": "dustbin",
    "trash can": "dustbin",
    "podium": "podium",
    "lectern": "podium",
}

LABEL_CONF = 0.15      # YOLO-World confidence needed to keep a pseudo-label
IMG_EXT = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}
SEED = 7


def images_in(folder: Path) -> list[Path]:
    return sorted(p for p in folder.rglob("*") if p.suffix.lower() in IMG_EXT)


VIDEO_EXT = {".mp4", ".mov", ".3gp", ".mkv", ".avi", ".webm"}
FRAME_EVERY_S = 0.5    # one photo every half second of video


def extract_video_frames() -> None:
    """Turn every video in ml/dataset/raw/ into photos (frames_<video>/), once."""
    import cv2

    for video in sorted(p for p in RAW.iterdir() if p.suffix.lower() in VIDEO_EXT):
        out = RAW / f"frames_{video.stem}"
        if out.exists():
            continue
        out.mkdir()
        cap = cv2.VideoCapture(str(video))
        fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
        step = max(1, round(fps * FRAME_EVERY_S))
        idx = saved = 0
        while True:
            ok, frame = cap.read()
            if not ok:
                break
            if idx % step == 0:
                cv2.imwrite(str(out / f"{video.stem}_{idx:06d}.jpg"), frame, [cv2.IMWRITE_JPEG_QUALITY, 92])
                saved += 1
            idx += 1
        cap.release()
        print(f"Video {video.name}: saved {saved} frames to {out.name}/")


def label() -> None:
    from ultralytics import YOLOWorld

    extract_video_frames()
    photos = images_in(RAW)
    if not photos:
        sys.exit(f"No photos in {RAW}. Copy the classroom images there first.")
    print(f"Labelling {len(photos)} photos with YOLO-World...")

    prompts = list(PROMPTS)
    model = YOLOWorld("yolov8s-worldv2.pt")
    model.set_classes(prompts)

    for split in ("train", "val"):
        (YOLO_DIR / "images" / split).mkdir(parents=True, exist_ok=True)
        (YOLO_DIR / "labels" / split).mkdir(parents=True, exist_ok=True)
    PREVIEW.mkdir(parents=True, exist_ok=True)

    random.Random(SEED).shuffle(photos)
    n_val = max(1, len(photos) // 7)          # about 15% for validation
    counts = {c: 0 for c in CLASSES}
    for i, photo in enumerate(photos):
        split = "val" if i < n_val else "train"
        r = model.predict(str(photo), conf=LABEL_CONF, imgsz=640, verbose=False)[0]
        lines = []
        for cls_idx, xywhn in zip(r.boxes.cls.tolist(), r.boxes.xywhn.tolist()):
            final = CLASSES.index(PROMPTS[prompts[int(cls_idx)]])
            counts[CLASSES[final]] += 1
            lines.append(f"{final} " + " ".join(f"{v:.6f}" for v in xywhn))
        stem = f"{i:04d}_{photo.stem}"
        shutil.copy(photo, YOLO_DIR / "images" / split / f"{stem}{photo.suffix.lower()}")
        (YOLO_DIR / "labels" / split / f"{stem}.txt").write_text("\n".join(lines))
        r.save(filename=str(PREVIEW / f"{stem}.jpg"))
        print(f"  [{i + 1}/{len(photos)}] {photo.name}: {len(lines)} boxes")

    (YOLO_DIR / "data.yaml").write_text(
        f"path: {YOLO_DIR}\ntrain: images/train\nval: images/val\n"
        f"names:\n" + "".join(f"  {i}: {c}\n" for i, c in enumerate(CLASSES))
    )
    print("Boxes per class:", counts)
    print(f"Check the previews in {PREVIEW} before training.")


def train(epochs: int = 40) -> Path:
    from ultralytics import YOLO

    data = YOLO_DIR / "data.yaml"
    if not data.exists():
        sys.exit("No dataset yet. Run the 'label' step first.")
    model = YOLO(str(ML / "yolov8n.pt"))
    model.train(data=str(data), epochs=epochs, imgsz=320, batch=16, device="cpu",
                workers=4, patience=12, project=str(RUNS), name="classroom",
                exist_ok=True, seed=SEED, verbose=False)
    best = RUNS / "classroom" / "weights" / "best.pt"
    print(f"Best weights: {best}")
    return best


def export(best: Path | None = None) -> None:
    from ultralytics import YOLO

    best = best or RUNS / "classroom" / "weights" / "best.pt"
    if not best.exists():
        sys.exit("No trained model yet. Run the 'train' step first.")
    out = YOLO(str(best)).export(format="tflite", imgsz=320)
    EXPORT.mkdir(exist_ok=True)
    src = Path(out)
    # export() returns the saved-model folder or the file, depending on the version
    tflites = [src] if src.suffix == ".tflite" else sorted(src.glob("*float32.tflite"))
    if not tflites:
        sys.exit(f"Export produced no float32 .tflite in {src}")
    shutil.copy(tflites[0], EXPORT / "yolov8n_classroom_320_fp32.tflite")
    (EXPORT / "classroom_labels.txt").write_text("\n".join(CLASSES) + "\n")
    print(f"Exported to {EXPORT}: yolov8n_classroom_320_fp32.tflite + classroom_labels.txt")


if __name__ == "__main__":
    step = sys.argv[1] if len(sys.argv) > 1 else "all"
    if step == "label":
        label()
    elif step == "train":
        train(int(sys.argv[2]) if len(sys.argv) > 2 else 40)
    elif step == "export":
        export()
    elif step == "all":
        label()
        export(train())
    else:
        sys.exit("usage: train_classroom.py [label|train [epochs]|export|all]")
