#!/usr/bin/env python3
"""
Build the CapSight HAZARD dataset (YOLO format) from public data only.

Classes (FR-DET-02 / FR-DET-04 - things YOLOv8n's 80 COCO classes do not cover):
    0 door      Open Images  "Door"
    1 stairs    Open Images  "Stairs"
    2 pole      Open Images  "Street light"  (lamp posts - the closest public "pole" class)
    3 sign      Open Images  "Traffic sign" + "Stop sign"
    4 pothole   RDD2022      class D40, India + Japan road images (CC BY 4.0)

Sources (no account needed):
    Open Images V7 boxes  https://storage.googleapis.com/openimages/  (CC BY 4.0 annotations,
                          images CC BY 2.0 - credit both in the project report)
    RDD2022               https://figshare.com/articles/dataset/21431547 - one 13 GB zip; only the
                          needed files are fetched with HTTP range requests (remotezip).

Usage (Colab does this for you, see train_hazards_colab.ipynb):
    pip install remotezip pillow
    python build_dataset.py --out hazards --per-class 3000
    python build_dataset.py --out tiny --oi-split validation --per-class 10 --rdd-max 10   # quick check

Output:
    <out>/images/{train,val}/*.jpg   <out>/labels/{train,val}/*.txt   <out>/data.yaml
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import io
import os
import random
import re
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed

CLASSES = ["door", "stairs", "pole", "sign", "pothole"]

# Open Images label id -> our class index.
OI_LABELS = {
    "/m/02dgv": 0,   # Door
    "/m/01lynh": 1,  # Stairs
    "/m/033rq4": 2,  # Street light
    "/m/01mqdt": 3,  # Traffic sign
    "/m/02pv19": 3,  # Stop sign
}
OI_CSV = {
    "train": "https://storage.googleapis.com/openimages/v6/oidv6-train-annotations-bbox.csv",
    "validation": "https://storage.googleapis.com/openimages/v5/validation-annotations-bbox.csv",
    "test": "https://storage.googleapis.com/openimages/v5/test-annotations-bbox.csv",
}
OI_IMAGE = "https://open-images-dataset.s3.amazonaws.com/{split}/{image_id}.jpg"

RDD_ZIP = "https://ndownloader.figshare.com/files/38030910"
RDD_POTHOLE = "D40"
POTHOLE = CLASSES.index("pothole")

USER_AGENT = "CapSight-dataset-builder/1.0 (final-year project, AITM Belagavi)"


# ------------------------------------------------------------------ helpers
def log(msg: str):
    print(msg, flush=True)


def fetch(url: str, retries: int = 4, timeout: int = 60) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.read()
        except Exception as exc:  # network hiccups are normal at this volume
            if attempt == retries - 1:
                raise
            time.sleep(1.5 * (attempt + 1))
            last = exc
    raise RuntimeError(f"unreachable: {last}")


def download_file(url: str, path: str):
    """Download once; reuse the cached copy on reruns (the train CSV is 2.2 GB)."""
    if os.path.exists(path) and os.path.getsize(path) > 0:
        log(f"  using cached {path}")
        return
    log(f"  downloading {url}")
    tmp = path + ".part"
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=120) as r, open(tmp, "wb") as f:
        total = int(r.headers.get("Content-Length") or 0)
        done = 0
        last = time.time()
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            f.write(chunk)
            done += len(chunk)
            if time.time() - last > 10:
                pct = f" ({100 * done / total:.0f}%)" if total else ""
                log(f"    {done / 1e6:.0f} MB{pct}")
                last = time.time()
    os.replace(tmp, path)


def save_image(data: bytes, path: str, max_side: int):
    """Write the JPEG, shrunk so its long side is at most max_side (labels are normalised)."""
    if max_side > 0:
        try:
            from PIL import Image
            img = Image.open(io.BytesIO(data))
            if max(img.size) > max_side:
                img = img.convert("RGB")
                img.thumbnail((max_side, max_side))
                img.save(path, "JPEG", quality=90)
                return
        except ImportError:
            pass
    with open(path, "wb") as f:
        f.write(data)


def split_of(name: str, val_frac: float) -> str:
    """Deterministic train/val split by file name."""
    h = int(hashlib.md5(name.encode()).hexdigest()[:8], 16) / 0xFFFFFFFF
    return "val" if h < val_frac else "train"


def write_sample(out: str, name: str, data: bytes, boxes: list, val_frac: float, max_side: int):
    """boxes: [(cls, cx, cy, w, h)] normalised 0-1."""
    split = split_of(name, val_frac)
    save_image(data, os.path.join(out, "images", split, name + ".jpg"), max_side)
    with open(os.path.join(out, "labels", split, name + ".txt"), "w") as f:
        for c, cx, cy, w, h in boxes:
            f.write(f"{c} {cx:.6f} {cy:.6f} {w:.6f} {h:.6f}\n")


def exists(out: str, name: str) -> bool:
    return any(os.path.exists(os.path.join(out, "labels", s, name + ".txt")) for s in ("train", "val"))


# --------------------------------------------------------------- Open Images
def open_images(args) -> dict:
    log(f"\n[Open Images] split={args.oi_split}")
    csv_path = os.path.join(args.cache, f"oi-{args.oi_split}-bbox.csv")
    download_file(OI_CSV[args.oi_split], csv_path)

    boxes = defaultdict(list)            # image id -> [(cls, cx, cy, w, h)]
    with open(csv_path, newline="") as f:
        for row in csv.DictReader(f):
            cls = OI_LABELS.get(row["LabelName"])
            if cls is None:
                continue
            # Drawings of doors are not doors; a "group" box covers many objects at once.
            if row["IsDepiction"] == "1" or row["IsGroupOf"] == "1":
                continue
            x0, x1 = float(row["XMin"]), float(row["XMax"])
            y0, y1 = float(row["YMin"]), float(row["YMax"])
            if x1 - x0 <= 0 or y1 - y0 <= 0:
                continue
            boxes[row["ImageID"]].append((cls, (x0 + x1) / 2, (y0 + y1) / 2, x1 - x0, y1 - y0))

    by_class = defaultdict(list)
    for image_id, bs in boxes.items():
        for c in {b[0] for b in bs}:
            by_class[c].append(image_id)
    rng = random.Random(args.seed)
    chosen = set()
    for c in range(len(CLASSES)):
        ids = sorted(by_class.get(c, []))
        rng.shuffle(ids)
        chosen.update(ids[: args.per_class])
        log(f"  {CLASSES[c]:8s} {len(by_class.get(c, [])):6d} images available, using {min(len(ids), args.per_class)}")

    todo = [i for i in sorted(chosen) if not exists(args.out, f"oi_{i}")]
    log(f"  downloading {len(todo)} images ({len(chosen) - len(todo)} already present)")
    ok = failed = 0

    def one(image_id):
        data = fetch(OI_IMAGE.format(split=args.oi_split, image_id=image_id))
        write_sample(args.out, f"oi_{image_id}", data, boxes[image_id], args.val_frac, args.max_side)

    with ThreadPoolExecutor(args.workers) as ex:
        futures = {ex.submit(one, i): i for i in todo}
        for n, fut in enumerate(as_completed(futures), 1):
            try:
                fut.result()
                ok += 1
            except Exception as exc:
                failed += 1
                if failed <= 5:
                    log(f"  skip {futures[fut]}: {exc}")
            if n % 500 == 0:
                log(f"    {n}/{len(todo)}")
    log(f"  Open Images done: {ok} new, {failed} failed")
    return {i: boxes[i] for i in chosen}


# ---------------------------------------------------------------- RDD2022
def rdd_potholes(args) -> int:
    """RDD2022 is a zip of per-country zips. Fetch only the wanted country zips (India 0.5 GB,
    Japan 1.1 GB) out of the 13 GB archive with HTTP range requests, then read them locally."""
    import zipfile
    try:
        from remotezip import RemoteZip
    except ImportError:
        sys.exit("pip install remotezip   (fetches only the needed parts of the 13 GB zip)")
    log(f"\n[RDD2022 potholes] countries={args.rdd_countries}")
    found = 0
    with RemoteZip(RDD_ZIP, headers={"User-Agent": USER_AGENT}) as outer:
        inner_names = {os.path.splitext(os.path.basename(n))[0]: n for n in outer.namelist() if n.endswith(".zip")}
        for country in args.rdd_countries:
            if country not in inner_names:
                log(f"  {country}: not in RDD2022 (have {sorted(inner_names)})")
                continue
            local_zip = os.path.join(args.cache, f"RDD2022_{country}.zip")
            if not (os.path.exists(local_zip) and os.path.getsize(local_zip) > 0):
                size = outer.getinfo(inner_names[country]).file_size
                log(f"  {country}: fetching {size / 1e9:.2f} GB")
                with outer.open(inner_names[country]) as src, open(local_zip + ".part", "wb") as dst:
                    done, last = 0, time.time()
                    while True:
                        chunk = src.read(1 << 22)
                        if not chunk:
                            break
                        dst.write(chunk)
                        done += len(chunk)
                        if time.time() - last > 10:
                            log(f"    {done / 1e6:.0f} MB ({100 * done / size:.0f}%)")
                            last = time.time()
                os.replace(local_zip + ".part", local_zip)
            else:
                log(f"  {country}: using cached {local_zip}")
            found += _potholes_from_country_zip(zipfile.ZipFile(local_zip), country, args)
    log(f"  RDD2022 done: {found} pothole images")
    return found


def _potholes_from_country_zip(z, country: str, args) -> int:
    xml_re = re.compile(r"(^|/)train/annotations/xmls/([^/]+)\.xml$")
    names = set(z.namelist())
    xmls = sorted(n for n in names if xml_re.search(n))
    log(f"  {country}: {len(xmls)} annotated images, scanning for potholes ({RDD_POTHOLE})")
    written = 0
    for xml_name in xmls:
        root = ET.fromstring(z.read(xml_name))
        size = root.find("size")
        w = float(size.findtext("width"))
        h = float(size.findtext("height"))
        bs = []
        for obj in root.iter("object"):
            if obj.findtext("name") != RDD_POTHOLE:
                continue
            bb = obj.find("bndbox")
            x0, y0 = float(bb.findtext("xmin")), float(bb.findtext("ymin"))
            x1, y1 = float(bb.findtext("xmax")), float(bb.findtext("ymax"))
            x0, x1 = max(0.0, min(x0, x1)), min(w, max(x0, x1))
            y0, y1 = max(0.0, min(y0, y1)), min(h, max(y0, y1))
            if x1 - x0 > 1 and y1 - y0 > 1:
                bs.append((POTHOLE, (x0 + x1) / 2 / w, (y0 + y1) / 2 / h, (x1 - x0) / w, (y1 - y0) / h))
        if not bs:
            continue
        stem = os.path.splitext(os.path.basename(xml_name))[0]
        name = f"rdd_{stem}"
        if not exists(args.out, name):
            img_name = xml_name.replace("/annotations/xmls/", "/images/")[:-4] + ".jpg"
            if img_name not in names:
                continue
            write_sample(args.out, name, z.read(img_name), bs, args.val_frac, args.max_side)
        written += 1
        if args.rdd_max and written >= args.rdd_max:
            break
    log(f"  {country}: {written} images with potholes")
    return written


# -------------------------------------------------------------------- main
def summary(out: str):
    counts = {s: [0] * len(CLASSES) for s in ("train", "val")}
    images = {}
    for s in ("train", "val"):
        d = os.path.join(out, "labels", s)
        files = [f for f in os.listdir(d) if f.endswith(".txt")]
        images[s] = len(files)
        for fn in files:
            with open(os.path.join(d, fn)) as f:
                for line in f:
                    counts[s][int(line.split()[0])] += 1
    log("\n[summary]  boxes per class (train / val)")
    for c, name in enumerate(CLASSES):
        log(f"  {c} {name:8s} {counts['train'][c]:7d} / {counts['val'][c]:6d}")
    log(f"  images: {images['train']} train, {images['val']} val")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", required=True, help="output dataset folder")
    ap.add_argument("--cache", default=None, help="where to keep the Open Images CSV (default: <out>/_cache)")
    ap.add_argument("--oi-split", default="train", choices=sorted(OI_CSV))
    ap.add_argument("--per-class", type=int, default=3000, help="max Open Images images per class")
    ap.add_argument("--rdd-countries", nargs="+", default=["India", "Japan"])
    ap.add_argument("--rdd-max", type=int, default=0, help="max pothole images per country (0 = all)")
    ap.add_argument("--val-frac", type=float, default=0.1)
    ap.add_argument("--max-side", type=int, default=640, help="shrink images to this long side (0 = keep)")
    ap.add_argument("--workers", type=int, default=16)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--skip-oi", action="store_true")
    ap.add_argument("--skip-rdd", action="store_true")
    args = ap.parse_args()
    args.cache = args.cache or os.path.join(args.out, "_cache")

    for d in ("images/train", "images/val", "labels/train", "labels/val"):
        os.makedirs(os.path.join(args.out, d), exist_ok=True)
    os.makedirs(args.cache, exist_ok=True)

    if not args.skip_oi:
        open_images(args)
    if not args.skip_rdd:
        rdd_potholes(args)

    with open(os.path.join(args.out, "data.yaml"), "w") as f:
        f.write(f"path: {os.path.abspath(args.out)}\ntrain: images/train\nval: images/val\n")
        f.write("names:\n" + "".join(f"  {i}: {n}\n" for i, n in enumerate(CLASSES)))
    summary(args.out)
    log(f"\nDataset ready: {os.path.join(os.path.abspath(args.out), 'data.yaml')}")


if __name__ == "__main__":
    main()
