#!/usr/bin/env python3
"""Offline vision scorer for map2mc renders — the MEASUREMENT step, not part of the mod.

Geometry answers facts (where / how tall / occluded — the mod has those); vision answers TASTE.
This tool samples frames from a rendered clip and scores each one with LOCAL models:
  - aesthetic  : LAION aesthetic head on a CLIP ViT-L/14 embedding (~0–10 "is this pretty")
  - style_match: cosine similarity to a set of reference frames (the target look), if --style given
  - dead-color : a cheap heuristic flag for "empty" frames (one flat colour / no edges — the
                 distant-strip-on-grass case), which complements the model.

It emits a JSON scorecard (per-frame, per-segment, summary). Nothing here runs in the mod or any
runtime loop — closed-loop shot selection comes LATER and only if these numbers earn the user's trust.

Degrades gracefully: if the models/weights can't load, it still runs the heuristic and records which
scores are missing rather than crashing. No paid APIs.
"""
from __future__ import annotations

import argparse
import datetime as _dt
import glob
import json
import os
import shutil
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path

import numpy as np
from PIL import Image

# Pinned for reproducibility (recorded in the scorecard's model_versions).
BACKBONE = "openai/clip-vit-large-patch14"            # CLIP ViT-L/14 (768-dim image embeddings)
AESTHETIC_URL = (
    "https://github.com/christophschuhmann/improved-aesthetic-predictor/"
    "raw/main/sac+logos+ava1-l14-linearMSE.pth"
)
AESTHETIC_NAME = "improved-aesthetic-predictor:sac+logos+ava1-l14"
FFMPEG = os.environ.get("MAP2MC_FFMPEG", shutil.which("ffmpeg") or "ffmpeg")
CACHE = Path.home() / ".cache" / "map2mc-vision"


# ── frame sampling (reuses the project's ffmpeg fps-filter approach) ─────────────────────────────
def extract_frames(video: str, fps: float, outdir: str, scale: int = 640) -> list[tuple[float, str]]:
    os.makedirs(outdir, exist_ok=True)
    pattern = os.path.join(outdir, "frame_%05d.jpg")
    cmd = [FFMPEG, "-hide_banner", "-loglevel", "error", "-i", video,
           "-vf", f"fps={fps},scale={scale}:-2", "-q:v", "4", pattern]
    subprocess.run(cmd, check=True)
    files = sorted(glob.glob(os.path.join(outdir, "frame_*.jpg")))
    # ffmpeg fps filter emits frame i (1-indexed) at ~ (i-1)/fps seconds.
    return [((i) / fps, f) for i, f in enumerate(files)]


def load_frames_dir(frames_dir: str, fps: float) -> list[tuple[float, str]]:
    files = sorted(glob.glob(os.path.join(frames_dir, "*.jpg")) + glob.glob(os.path.join(frames_dir, "*.png")))
    return [((i) / fps, f) for i, f in enumerate(files)]


# ── dead/low-info heuristic (no model) ───────────────────────────────────────────────────────────
def dead_metrics(path: str) -> dict:
    """Return {'edge_density':.., 'top_color_frac':.., 'dead': bool}. Cheap; catches 'empty' frames."""
    img = Image.open(path).convert("RGB").resize((96, 54))
    a = np.asarray(img, dtype=np.float32)
    gray = a.mean(axis=2)
    gx = np.abs(np.diff(gray, axis=1))
    gy = np.abs(np.diff(gray, axis=0))
    # fraction of adjacent-pixel pairs with a meaningful brightness step (detail vs flat)
    edge_density = float(((gx > 12).mean() + (gy > 12).mean()) / 2.0)
    # coverage of the top colour bins (coarse 4×4×4 quantisation) — flat scenes are a few colours
    q = (a // 64).astype(np.int32)
    codes = q[..., 0] * 16 + q[..., 1] * 4 + q[..., 2]
    counts = np.bincount(codes.ravel(), minlength=64)
    top_color_frac = float(np.sort(counts)[-3:].sum() / counts.sum())  # top-3 bins
    dead = edge_density < 0.06 and top_color_frac > 0.90
    return {"edge_density": round(edge_density, 4), "top_color_frac": round(top_color_frac, 4), "dead": dead}


# ── models (CLIP-L/14 backbone + LAION aesthetic head); lazy + graceful ──────────────────────────
class Models:
    def __init__(self, device: str | None):
        import torch  # noqa
        from transformers import CLIPModel, CLIPProcessor  # noqa
        self.torch = torch
        if device is None:
            device = "cuda" if torch.cuda.is_available() else "cpu"
        self.device = device
        self.clip = CLIPModel.from_pretrained(BACKBONE).to(device).eval()
        self.proc = CLIPProcessor.from_pretrained(BACKBONE)
        self.aesthetic_head = self._load_aesthetic_head(device)

    def _load_aesthetic_head(self, device):
        torch, nn = self.torch, self.torch.nn
        CACHE.mkdir(parents=True, exist_ok=True)
        local = CACHE / "aesthetic_l14.pth"
        if not local.exists():
            urllib.request.urlretrieve(AESTHETIC_URL, local)
        state = torch.load(local, map_location="cpu", weights_only=True)
        # The published predictor is a 768→1024→128→64→16→1 MLP (keys: layers.N.*).
        mlp = nn.Sequential(
            nn.Linear(768, 1024), nn.Dropout(0.2),
            nn.Linear(1024, 128), nn.Dropout(0.2),
            nn.Linear(128, 64), nn.Dropout(0.1),
            nn.Linear(64, 16),
            nn.Linear(16, 1),
        )
        # strip a possible "layers." prefix so it maps onto the Sequential
        cleaned = { (k[len("layers."):] if k.startswith("layers.") else k): v for k, v in state.items() }
        mlp.load_state_dict(cleaned)
        return mlp.to(device).eval()

    def embed(self, paths: list[str], batch: int = 8):
        torch = self.torch
        out = []
        with torch.no_grad():
            for i in range(0, len(paths), batch):
                imgs = [Image.open(p).convert("RGB") for p in paths[i:i + batch]]
                inp = self.proc(images=imgs, return_tensors="pt").to(self.device)
                # Explicit vision tower + projection (stable across transformers versions; the
                # 768-dim projected embedding is what both the aesthetic head and CLIP similarity use).
                vis = self.clip.vision_model(pixel_values=inp["pixel_values"])
                f = self.clip.visual_projection(vis.pooler_output)
                f = f / f.norm(dim=-1, keepdim=True)
                out.append(f.float().cpu())
        return torch.cat(out) if out else torch.empty(0, 768)

    def aesthetic(self, embs):
        torch = self.torch
        with torch.no_grad():
            s = self.aesthetic_head(embs.to(self.device)).squeeze(-1).cpu().numpy()
        return s


def try_load_models(device: str | None):
    """Return (Models, None) or (None, reason) — never raises."""
    try:
        return Models(device), None
    except Exception as e:  # noqa: BLE001 — degrade gracefully, report the reason
        return None, f"{type(e).__name__}: {e}"


# ── aggregation ──────────────────────────────────────────────────────────────────────────────────
def _mean(xs):
    xs = [x for x in xs if x is not None]
    return round(float(np.mean(xs)), 4) if xs else None


def segment(frames: list[dict], seconds: float) -> list[dict]:
    segs, cur, start = [], [], None
    for fr in frames:
        if start is None:
            start = fr["t"]
        if fr["t"] - start >= seconds and cur:
            segs.append(_summarize_segment(cur))
            cur, start = [], fr["t"]
        cur.append(fr)
    if cur:
        segs.append(_summarize_segment(cur))
    return segs


def _summarize_segment(frs):
    flags = sorted({fl for fr in frs for fl in fr["flags"]})
    return {
        "t_start": round(frs[0]["t"], 2), "t_end": round(frs[-1]["t"], 2), "n": len(frs),
        "aesthetic": _mean([fr["aesthetic"] for fr in frs]),
        "style_match": _mean([fr["style_match"] for fr in frs]),
        "dead_frac": round(sum(1 for fr in frs if "dead_color" in fr["flags"]) / len(frs), 3),
        "flags": flags,
    }


def contiguous_spans(frames, predicate, label):
    spans, run = [], None
    for fr in frames:
        if predicate(fr):
            run = run or {"t_start": fr["t"], "t_end": fr["t"], "label": label}
            run["t_end"] = fr["t"]
        elif run:
            spans.append({**run, "t_start": round(run["t_start"], 2), "t_end": round(run["t_end"], 2)})
            run = None
    if run:
        spans.append({**run, "t_start": round(run["t_start"], 2), "t_end": round(run["t_end"], 2)})
    return spans


# ── main ─────────────────────────────────────────────────────────────────────────────────────────
def main(argv=None):
    ap = argparse.ArgumentParser(description="Offline aesthetic + style-match scorer for map2mc renders.")
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--video", help="rendered clip to score")
    src.add_argument("--frames", help="directory of pre-extracted frames")
    ap.add_argument("--style", help="directory of reference frames defining the target look (optional)")
    ap.add_argument("--out", required=True, help="scorecard JSON output path")
    ap.add_argument("--fps", type=float, default=1.5, help="frame sample rate (default 1.5, cap 2)")
    ap.add_argument("--segment-seconds", type=float, default=8.0)
    ap.add_argument("--device", default=None, help="cuda|cpu (default: cuda if available, else cpu)")
    ap.add_argument("--off-style-threshold", type=float, default=0.20,
                    help="flag a frame off_style when its max style_match is below this")
    args = ap.parse_args(argv)
    args.fps = min(args.fps, 2.0)

    tmp = None
    try:
        if args.video:
            tmp = tempfile.mkdtemp(prefix="map2mc-vision-")
            ts_paths = extract_frames(args.video, args.fps, tmp)
        else:
            ts_paths = load_frames_dir(args.frames, args.fps)
        if not ts_paths:
            sys.exit("no frames to score")

        paths = [p for _, p in ts_paths]
        models, model_err = try_load_models(args.device)
        scores_missing = []

        aesthetic = [None] * len(paths)
        style_max = [None] * len(paths)
        style_mean = [None] * len(paths)
        if models is not None:
            embs = models.embed(paths)
            aesthetic = [round(float(s), 3) for s in models.aesthetic(embs)]
            if args.style:
                ref_paths = sorted(glob.glob(os.path.join(args.style, "*.jpg"))
                                   + glob.glob(os.path.join(args.style, "*.png")))
                if ref_paths:
                    refs = models.embed(ref_paths)
                    sims = (embs @ refs.T)  # cosine (both already L2-normalised)
                    style_max = [round(float(r.max()), 4) for r in sims]
                    style_mean = [round(float(r.mean()), 4) for r in sims]
                else:
                    scores_missing.append("style_match (no reference frames in --style dir)")
        else:
            scores_missing.append(f"aesthetic + style_match (models unavailable: {model_err})")

        frames = []
        for idx, (t, p) in enumerate(ts_paths):
            dm = dead_metrics(p)
            flags = []
            if dm["dead"]:
                flags.append("dead_color")
            sm = style_max[idx]
            if sm is not None and sm < args.off_style_threshold:
                flags.append("off_style")
            frames.append({
                "t": round(t, 2),
                "aesthetic": aesthetic[idx],
                "style_match": sm,
                "style_match_mean": style_mean[idx],
                "edge_density": dm["edge_density"],
                "top_color_frac": dm["top_color_frac"],
                "flags": flags,
            })

        segs = segment(frames, args.segment_seconds)
        worst = sorted([s for s in segs if s["aesthetic"] is not None],
                       key=lambda s: s["aesthetic"])[:3]
        try:
            import torch
            torch_ver = torch.__version__
        except Exception:  # noqa: BLE001
            torch_ver = None
        scorecard = {
            "tool": "map2mc/tools/vision/score.py",
            "video": args.video or args.frames,
            "generated_at": _dt.datetime.now().isoformat(timespec="seconds"),
            "model_versions": {
                "backbone": BACKBONE if models else None,
                "aesthetic": AESTHETIC_NAME if models else None,
                "device": getattr(models, "device", None),
                "torch": torch_ver,
            },
            "params": {"fps": args.fps, "segment_seconds": args.segment_seconds,
                       "off_style_threshold": args.off_style_threshold,
                       "style_refs": bool(args.style)},
            "frames": frames,
            "segments": segs,
            "summary": {
                "n_frames": len(frames),
                "mean_aesthetic": _mean([f["aesthetic"] for f in frames]),
                "mean_style_match": _mean([f["style_match"] for f in frames]),
                "worst_segments": worst,
                "dead_spans": contiguous_spans(frames, lambda f: "dead_color" in f["flags"], "dead_color"),
                "off_style_spans": contiguous_spans(frames, lambda f: "off_style" in f["flags"], "off_style"),
                "scores_missing": scores_missing,
            },
        }
        Path(args.out).write_text(json.dumps(scorecard, indent=2))
        s = scorecard["summary"]
        print(f"[score] {len(frames)} frames | mean_aesthetic={s['mean_aesthetic']} "
              f"mean_style_match={s['mean_style_match']} | dead_spans={len(s['dead_spans'])} "
              f"| -> {args.out}")
        if scores_missing:
            print("[score] MISSING: " + "; ".join(scores_missing), file=sys.stderr)
    finally:
        if tmp:
            shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    main()
