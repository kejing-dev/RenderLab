"""RenderLab icon (design-package appIcon; background moved off teal because abalone-push already uses #1F4E5A): a plum
#704870 disc; a light studio wall back-left; a plaster-white cube in 3/4 view; a warm gold light orb front-right; the cube's
clean dark shadow runs back-left and climbs the wall. Flat, no text.

  python3 tools/build_icon.py [--icon512 out.png] [--preview out.png]

Writes res/drawable/icon_3d_layer_{0,1,2}.png + icon_3d_sdf_{0,1,2}.png (PICO layered icon), mipmap-xxxhdpi/
ic_spatial_launcher.png and the adaptive foreground vector res/drawable/ic_launcher_fg.xml (same polygons).
"""
import os
import sys
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
S, SS = 1024, 4
PLUM = (0x70, 0x48, 0x70)
WALL = (0xE2, 0xDC, 0xD2)
FLOOR = (0xC9, 0xC2, 0xB6)
SHADOW = (0x4A, 0x36, 0x4C)
TOP = (0xF7, 0xF4, 0xEE)
RIGHT = (0xEA, 0xE5, 0xDB)
LEFT = (0xB9, 0xB2, 0xA6)
GOLD = (0xFF, 0xD2, 0x7A)

WALL_P = [(20, 24), (62, 20), (62, 60), (20, 68)]
FLOOR_P = [(20, 68), (62, 60), (90, 72), (48, 86)]
SHADOW_P = [(36, 78), (22, 70), (22, 44), (34, 42), (47, 60)]
CUBE = {
    "top": ([(53, 50), (71, 58), (53, 66), (35, 58)], TOP),
    "left": ([(35, 58), (53, 66), (53, 86), (35, 78)], LEFT),
    "right": ([(53, 66), (71, 58), (71, 78), (53, 86)], RIGHT),
}
ORB = (83, 36, 7.5)


def big(draw_fn):
    im = Image.new("RGBA", (S * SS, S * SS), (0, 0, 0, 0))
    draw_fn(ImageDraw.Draw(im), S * SS / 108.0)
    return im.resize((S, S), Image.LANCZOS)


def sc(pts, k):
    return [(x * k, y * k) for x, y in pts]


def layer_bg():
    return big(lambda dr, k: dr.ellipse((0, 0, 108 * k - 1, 108 * k - 1), fill=PLUM + (255,)))


def layer_set():
    def d(dr, k):
        dr.polygon(sc(WALL_P, k), fill=WALL + (255,))
        dr.polygon(sc(FLOOR_P, k), fill=FLOOR + (255,))
        dr.polygon(sc(SHADOW_P, k), fill=SHADOW + (255,))
        for poly, col in CUBE.values():
            dr.polygon(sc(poly, k), fill=col + (255,))
    return big(d)


def layer_orb():
    x, y, r = ORB
    halo = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    hd = ImageDraw.Draw(halo)
    k = S / 108.0
    for i in range(24, 0, -1):
        rr = r + i * 0.55
        a = int(150 * (1 - i / 24) ** 2)
        hd.ellipse(((x - rr) * k, (y - rr) * k, (x + rr) * k, (y + rr) * k), fill=GOLD + (a,))
    core = big(lambda dr, kk: dr.ellipse(((x - r) * kk, (y - r) * kk, (x + r) * kk, (y + r) * kk), fill=GOLD + (255,)))
    halo.alpha_composite(core)
    return halo


def sdf(layer):
    inside = layer.getchannel("A").point(lambda v: 255 if v >= 128 else 0)
    dist = Image.new("L", inside.size, 0)
    cur = inside
    for i in range(1, 33):
        dist.paste(min(255, i * 8), mask=cur)
        cur = cur.filter(ImageFilter.MinFilter(3) if i % 2 else ImageFilter.Kernel((3, 3), (0, 1, 0, 1, 1, 1, 0, 1, 0), 5))
        cur = cur.point(lambda v: 255 if v >= 255 else 0)
    dist = Image.composite(dist, Image.new("L", inside.size, 0), inside)
    return Image.merge("RGBA", (dist, dist, dist, dist))


def path(pts):
    return "M" + "L".join("%.2f,%.2f" % p for p in pts) + "Z"


def vector():
    hexc = lambda c, a=255: "#%02X%02X%02X%02X" % ((a,) + c)
    x, y, r = ORB
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        '<!-- RenderLab launcher foreground (tools/build_icon.py draws the same artwork for the PICO layered icon). -->',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">',
        '    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(WALL), path(WALL_P)),
        '    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(FLOOR), path(FLOOR_P)),
        '    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(SHADOW), path(SHADOW_P)),
    ]
    for poly, col in CUBE.values():
        lines.append('    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(col), path(poly)))
    rr = r + 6
    lines.append('    <path android:fillColor="%s" android:pathData="M%.1f,%.1fa%.1f,%.1f 0 1,0 %.1f,0a%.1f,%.1f 0 1,0 -%.1f,0Z" />' % (hexc(GOLD, 0x55), x - rr, y, rr, rr, 2 * rr, rr, rr, 2 * rr))
    lines.append('    <path android:fillColor="%s" android:pathData="M%.1f,%.1fa%.1f,%.1f 0 1,0 %.1f,0a%.1f,%.1f 0 1,0 -%.1f,0Z" />' % (hexc(GOLD), x - r, y, r, r, 2 * r, r, r, 2 * r))
    lines.append('</vector>')
    open(os.path.join(RES, "drawable", "ic_launcher_fg.xml"), "w").write("\n".join(lines) + "\n")


def main():
    layers = [layer_bg(), layer_set(), layer_orb()]
    for i, layer in enumerate(layers):
        layer.save(os.path.join(RES, "drawable", f"icon_3d_layer_{i}.png"))
        sdf(layer).save(os.path.join(RES, "drawable", f"icon_3d_sdf_{i}.png"))
    flat = Image.new("RGBA", layers[0].size, (0, 0, 0, 0))
    for l in layers:
        flat.alpha_composite(l)
    flat.save(os.path.join(RES, "mipmap-xxxhdpi", "ic_spatial_launcher.png"))
    vector()
    if "--icon512" in sys.argv:
        flat.resize((512, 512), Image.LANCZOS).save(sys.argv[sys.argv.index("--icon512") + 1])
    if "--preview" in sys.argv:
        w = 256
        sheet = Image.new("RGB", (w * 4, w), (60, 64, 70))
        for i, t in enumerate(layers + [flat]):
            r = t.resize((w, w), Image.LANCZOS)
            sheet.paste(r, (i * w, 0), r)
        sheet.save(sys.argv[sys.argv.index("--preview") + 1])
    print("ICON ok")


main()
