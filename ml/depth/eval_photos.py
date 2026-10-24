"""Scores the team's classroom photos with MiDaS small - the evaluation quoted in README.md.

Run: ~/miniconda3/envs/blenav/bin/python ml/depth/eval_photos.py
"""
import glob
from pathlib import Path

import numpy as np
from ai_edge_litert.interpreter import Interpreter
from PIL import Image

HERE = Path(__file__).resolve().parent
MEAN = np.array([0.485, 0.456, 0.406], np.float32)
STD = np.array([0.229, 0.224, 0.225], np.float32)

it = Interpreter(model_path=str(HERE / "midas_v2_1_small_256.tflite"), num_threads=4)
it.allocate_tensors()
inp, out = it.get_input_details()[0], it.get_output_details()[0]
for p in sorted(glob.glob(str(HERE.parent / "dataset" / "raw" / "*.jp*g"))):
    x = ((np.asarray(Image.open(p).convert("RGB").resize((256, 256)), np.float32) / 255.0 - MEAN) / STD)[None]
    it.set_tensor(inp["index"], x)
    it.invoke()
    d = it.get_tensor(out["index"])[0].squeeze()
    floor = np.median(d[220:250, 96:160])            # floor patch just ahead, bottom-centre
    band = d[60:180]                                  # body-height band (skip ceiling and floor)
    zones = [np.percentile(band[:, a:b], 90) / (floor + 1e-6) for a, b in ((0, 85), (85, 171), (171, 256))]
    print("L %.2f  C %.2f  R %.2f   %s" % (*zones, Path(p).name))
