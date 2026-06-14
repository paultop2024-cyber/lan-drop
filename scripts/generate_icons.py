#!/usr/bin/env python3
from __future__ import annotations

import math
import subprocess
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter


ROOT = Path(__file__).resolve().parents[1]
ANDROID_RES = ROOT / "android" / "app" / "src" / "main" / "res"
MACOS_DIR = ROOT / "macos"
ASSET_DIR = ROOT / "assets" / "icon"


def rounded_mask(size: int, radius: int) -> Image.Image:
    mask = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(mask)
    draw.rounded_rectangle((0, 0, size, size), radius=radius, fill=255)
    return mask


def vertical_gradient(size: int, top: tuple[int, int, int], bottom: tuple[int, int, int]) -> Image.Image:
    image = Image.new("RGBA", (size, size))
    pixels = image.load()
    for y in range(size):
        ratio = y / max(size - 1, 1)
        r = round(top[0] * (1 - ratio) + bottom[0] * ratio)
        g = round(top[1] * (1 - ratio) + bottom[1] * ratio)
        b = round(top[2] * (1 - ratio) + bottom[2] * ratio)
        for x in range(size):
            pixels[x, y] = (r, g, b, 255)
    return image


def add_soft_shape(base: Image.Image, bbox: tuple[int, int, int, int], color: tuple[int, int, int, int], blur: int) -> None:
    layer = Image.new("RGBA", base.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    draw.ellipse(bbox, fill=color)
    base.alpha_composite(layer.filter(ImageFilter.GaussianBlur(blur)))


def draw_wifi_arc(draw: ImageDraw.ImageDraw, center: tuple[int, int], radius: int, start: int, end: int, width: int, fill) -> None:
    x, y = center
    draw.arc((x - radius, y - radius, x + radius, y + radius), start=start, end=end, width=width, fill=fill)


def make_icon(size: int, round_icon: bool = False) -> Image.Image:
    scale = size / 1024

    def s(value: float) -> int:
        return round(value * scale)

    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    bg = vertical_gradient(size, (12, 124, 246), (8, 203, 178))
    add_soft_shape(bg, (s(-140), s(-170), s(560), s(430)), (255, 255, 255, 46), s(42))
    add_soft_shape(bg, (s(500), s(530), s(1180), s(1160)), (7, 63, 159, 58), s(50))

    mask = Image.new("L", (size, size), 0)
    mask_draw = ImageDraw.Draw(mask)
    if round_icon:
        mask_draw.ellipse((0, 0, size, size), fill=255)
    else:
        mask = rounded_mask(size, s(224))
    canvas.paste(bg, (0, 0), mask)

    shadow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    sd = ImageDraw.Draw(shadow)
    sd.rounded_rectangle((s(160), s(270), s(690), s(652)), radius=s(54), fill=(0, 33, 85, 78))
    sd.rounded_rectangle((s(653), s(198), s(852), s(720)), radius=s(72), fill=(0, 33, 85, 82))
    canvas.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(s(28))))

    draw = ImageDraw.Draw(canvas)

    # Desktop display
    draw.rounded_rectangle((s(132), s(238), s(682), s(636)), radius=s(64), fill=(242, 249, 255, 255))
    draw.rounded_rectangle((s(170), s(282), s(644), s(568)), radius=s(34), fill=(17, 75, 166, 255))
    draw.rounded_rectangle((s(204), s(314), s(610), s(532)), radius=s(26), fill=(28, 181, 226, 255))
    draw.rounded_rectangle((s(356), s(634), s(456), s(710)), radius=s(24), fill=(232, 245, 255, 255))
    draw.rounded_rectangle((s(274), s(704), s(540), s(754)), radius=s(25), fill=(232, 245, 255, 255))

    # Android phone, deliberately generic with no brand cue.
    draw.rounded_rectangle((s(626), s(160), s(884), s(754)), radius=s(78), fill=(246, 251, 255, 255))
    draw.rounded_rectangle((s(657), s(220), s(853), s(668)), radius=s(42), fill=(8, 55, 142, 255))
    draw.rounded_rectangle((s(684), s(252), s(826), s(630)), radius=s(30), fill=(18, 204, 181, 255))
    draw.ellipse((s(736), s(692), s(774), s(730)), fill=(26, 95, 184, 255))
    draw.rounded_rectangle((s(714), s(184), s(796), s(200)), radius=s(8), fill=(33, 94, 171, 255))

    # Transfer beam
    beam = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    bd = ImageDraw.Draw(beam)
    bd.rounded_rectangle((s(362), s(418), s(726), s(496)), radius=s(39), fill=(255, 255, 255, 190))
    bd.rounded_rectangle((s(406), s(438), s(688), s(476)), radius=s(19), fill=(221, 255, 246, 245))
    canvas.alpha_composite(beam.filter(ImageFilter.GaussianBlur(s(2))))

    draw = ImageDraw.Draw(canvas)
    arrow_fill = (4, 111, 195, 255)
    draw.polygon([(s(688), s(386)), (s(818), s(456)), (s(688), s(526)), (s(714), s(482)), (s(448), s(482)), (s(448), s(430)), (s(714), s(430))], fill=arrow_fill)
    draw.polygon([(s(340), s(532)), (s(210), s(462)), (s(340), s(392)), (s(314), s(436)), (s(580), s(436)), (s(580), s(488)), (s(314), s(488))], fill=(10, 175, 180, 255))

    # Wireless rings
    for radius, alpha in [(150, 120), (205, 88)]:
        draw_wifi_arc(draw, (s(512), s(458)), s(radius), 202, 338, s(18), (255, 255, 255, alpha))

    # Small sparkle pixels for speed/transfer energy.
    for cx, cy, r, alpha in [(230, 210, 10, 175), (788, 812, 12, 148), (170, 778, 7, 130), (884, 126, 8, 120)]:
        draw.ellipse((s(cx - r), s(cy - r), s(cx + r), s(cy + r)), fill=(255, 255, 255, alpha))

    return canvas


def save_android_icons(master: Image.Image) -> None:
    sizes = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }
    round_master = make_icon(1024, round_icon=True)
    for folder, size in sizes.items():
        out_dir = ANDROID_RES / folder
        out_dir.mkdir(parents=True, exist_ok=True)
        master.resize((size, size), Image.Resampling.LANCZOS).save(out_dir / "ic_launcher.png")
        round_master.resize((size, size), Image.Resampling.LANCZOS).save(out_dir / "ic_launcher_round.png")


def save_macos_icon(master: Image.Image) -> None:
    iconset = MACOS_DIR / "AppIcon.iconset"
    iconset.mkdir(parents=True, exist_ok=True)
    sizes = [
        (16, "icon_16x16.png"),
        (32, "icon_16x16@2x.png"),
        (32, "icon_32x32.png"),
        (64, "icon_32x32@2x.png"),
        (128, "icon_128x128.png"),
        (256, "icon_128x128@2x.png"),
        (256, "icon_256x256.png"),
        (512, "icon_256x256@2x.png"),
        (512, "icon_512x512.png"),
        (1024, "icon_512x512@2x.png"),
    ]
    for size, name in sizes:
        master.resize((size, size), Image.Resampling.LANCZOS).save(iconset / name)
    subprocess.run(["iconutil", "-c", "icns", str(iconset), "-o", str(MACOS_DIR / "AppIcon.icns")], check=True)


def save_preview(master: Image.Image) -> None:
    ASSET_DIR.mkdir(parents=True, exist_ok=True)
    master.save(ASSET_DIR / "lan-drop-icon-1024.png")
    master.resize((256, 256), Image.Resampling.LANCZOS).save(ASSET_DIR / "lan-drop-icon-preview.png")


def main() -> None:
    master = make_icon(1024)
    save_preview(master)
    save_android_icons(master)
    save_macos_icon(master)
    print(f"Generated icons under {ROOT}")


if __name__ == "__main__":
    main()
