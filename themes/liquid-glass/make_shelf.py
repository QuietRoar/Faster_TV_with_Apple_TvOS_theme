"""Renders the liquid-glass theme's top-shelf pictures: one mesh gradient per app, in its brand colours.

Soft colour blobs are blended in linear light, the lower part fades to near black so the top row stays readable,
and a faint grain of about one 8-bit step is added so the TV shows no bands. Run from the repo root:

    tools/venv/bin/python themes/liquid-glass/make_shelf.py     (needs numpy and Pillow in the venv)
"""
import os
import numpy as np
from PIL import Image

W, H = 1920, 1080
OUT = os.path.join(os.path.dirname(__file__), "shelf")
rng = np.random.default_rng(26)
y, x = np.mgrid[0:H, 0:W].astype(np.float32)
x /= W
y /= H


def lin(hex_colour):
    c = np.array([int(hex_colour[i:i + 2], 16) for i in (1, 3, 5)], np.float32) / 255
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def render(base, blobs, floor=0.06):
    img = np.broadcast_to(lin(base), (H, W, 3)).copy()
    for cx, cy, rx, ry, colour, strength in blobs:
        d = ((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2
        a = (strength * np.exp(-d))[..., None]
        img = img * (1 - a) + lin(colour) * a
    # Lower half towards near black (the top row sits there), then a light vignette at the edges.
    fade = np.clip((y - 0.42) / 0.5, 0, 1) ** 1.4
    img *= (1 - fade * (1 - floor))[..., None]
    vignette = 1 - 0.35 * (((x - 0.5) * 1.6) ** 2 + ((y - 0.35) * 1.2) ** 2)
    img *= np.clip(vignette, 0.55, 1)[..., None]
    srgb = np.where(img <= 0.0031308, img * 12.92, 1.055 * np.clip(img, 0, None) ** (1 / 2.4) - 0.055)
    srgb = srgb * 255 + rng.uniform(-0.9, 0.9, (H, W, 1))   # grain against banding, the same on all channels
    return Image.fromarray(np.clip(np.round(srgb), 0, 255).astype(np.uint8))


SHELF = {
    # Apple TV: graphite and black with a cool silver light.
    "com.apple.atve.androidtv.appletv": ("#050507", [
        (0.72, 0.18, 0.55, 0.40, "#5d6470", 0.85), (0.25, 0.05, 0.45, 0.30, "#2b3038", 0.8),
        (0.55, 0.40, 0.35, 0.25, "#9aa6b8", 0.25)]),
    # JioHotstar: deep navy into electric blue, violet and magenta.
    "in.startv.hotstar": ("#05061a", [
        (0.15, 0.10, 0.45, 0.40, "#1037d6", 0.9), (0.55, 0.20, 0.40, 0.35, "#6a1fd1", 0.85),
        (0.90, 0.30, 0.35, 0.35, "#e0218a", 0.8), (0.40, 0.45, 0.40, 0.20, "#2d0f6e", 0.6)]),
    # Netflix: black with a deep crimson glow.
    "com.netflix.ninja": ("#030000", [
        (0.70, 0.05, 0.60, 0.45, "#b00610", 0.9), (0.20, 0.30, 0.45, 0.35, "#4a0207", 0.8),
        (0.85, 0.35, 0.30, 0.25, "#e50914", 0.35)]),
    # Prime Video: midnight navy into Prime blue.
    "com.amazon.amazonvideo.livingroom": ("#02060f", [
        (0.75, 0.10, 0.55, 0.40, "#0a6fd6", 0.9), (0.25, 0.20, 0.45, 0.35, "#0b2a5c", 0.85),
        (0.90, 0.30, 0.30, 0.30, "#00a8e1", 0.55)]),
    # YouTube: near black with a warm red and ember glow.
    "com.google.android.youtube.tv": ("#050202", [
        (0.22, 0.08, 0.45, 0.38, "#b0001a", 0.85), (0.85, 0.12, 0.40, 0.35, "#ff5a1f", 0.8),
        (0.58, 0.30, 0.30, 0.22, "#ff2a3a", 0.45), (0.45, 0.48, 0.40, 0.18, "#3a0606", 0.7)]),
    # ZEE5: deep purple into magenta.
    "com.graymatrix.did": ("#07020f", [
        (0.30, 0.10, 0.55, 0.40, "#5a14b8", 0.9), (0.80, 0.20, 0.40, 0.35, "#c42aa8", 0.75),
        (0.55, 0.42, 0.35, 0.22, "#2c0a5e", 0.7)]),
}

if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for name in os.listdir(OUT):
        os.remove(os.path.join(OUT, name))
    for package, (base, blobs) in SHELF.items():
        render(base, blobs).save(os.path.join(OUT, package + ".jpg"), quality=95, subsampling=0, optimize=True)
        print(package)
