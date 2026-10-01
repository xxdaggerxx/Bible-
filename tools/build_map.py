"""Builds app/src/main/assets/map/lands.bin: a simple offline map of the Bible lands (STD-10).

Source: Natural Earth 1:10m physical vectors (public domain), from
github.com/nvkelso/natural-earth-vector/geojson:
  ne_10m_land.geojson, ne_10m_lakes.geojson, ne_10m_rivers_lake_centerlines.geojson

Usage: python3 build_map.py <folder with the geojson files> <output lands.bin>

Format (little-endian): three sections (land, lakes, rivers), each an int32 shape count, then
for each shape an int32 point count followed by (float32 longitude, float32 latitude) pairs.
Land and lakes are closed rings; rivers are open lines.
"""
import json
import os
import struct
import sys

src, out = sys.argv[1:3]
# From Spain and Italy to Persia, from the Black Sea to Arabia.
W, E, S, N = -10.0, 62.0, 8.0, 50.0
TOL = 0.012  # simplification, in degrees (about a kilometre)


def simplify(pts, tol):
    """Douglas-Peucker."""
    if len(pts) < 3:
        return pts
    keep = [False] * len(pts)
    keep[0] = keep[-1] = True
    stack = [(0, len(pts) - 1)]
    while stack:
        a, b = stack.pop()
        ax, ay = pts[a]; bx, by = pts[b]
        dx, dy = bx - ax, by - ay
        norm = (dx * dx + dy * dy) ** 0.5 or 1e-12
        far, idx = 0.0, -1
        for i in range(a + 1, b):
            px, py = pts[i]
            d = abs(dy * px - dx * py + bx * ay - by * ax) / norm
            if d > far:
                far, idx = d, i
        if far > tol and idx > 0:
            keep[idx] = True
            stack += [(a, idx), (idx, b)]
    return [p for p, k in zip(pts, keep) if k]


def clip_ring(ring):
    """Sutherland-Hodgman against the map box."""
    def clip(pts, inside, cross):
        outp = []
        for i, cur in enumerate(pts):
            prev = pts[i - 1]
            if inside(cur):
                if not inside(prev):
                    outp.append(cross(prev, cur))
                outp.append(cur)
            elif inside(prev):
                outp.append(cross(prev, cur))
        return outp

    def at_x(x):
        return lambda p, q: (x, p[1] + (q[1] - p[1]) * (x - p[0]) / (q[0] - p[0]))

    def at_y(y):
        return lambda p, q: (p[0] + (q[0] - p[0]) * (y - p[1]) / (q[1] - p[1]), y)

    pts = ring
    for inside, cross in (
        (lambda p: p[0] >= W, at_x(W)), (lambda p: p[0] <= E, at_x(E)),
        (lambda p: p[1] >= S, at_y(S)), (lambda p: p[1] <= N, at_y(N)),
    ):
        if not pts:
            break
        pts = clip(pts, inside, cross)
    return pts


def polygons(path):
    for f in json.load(open(os.path.join(src, path)))["features"]:
        g = f["geometry"]
        polys = g["coordinates"] if g["type"] == "MultiPolygon" else [g["coordinates"]]
        for poly in polys:
            for ring in poly[:1]:  # outer rings only; islands and lakes are separate shapes
                r = clip_ring([tuple(p) for p in ring])
                # A closed ring starts and ends on the same point, so simplify it in two halves.
                h = len(r) // 2
                r = simplify(r[:h + 1], TOL)[:-1] + simplify(r[h:], TOL) if len(r) > 3 else r
                if len(r) >= 4:
                    yield r


def lines(path):
    for f in json.load(open(os.path.join(src, path)))["features"]:
        g = f["geometry"]
        if g is None:
            continue
        ls = g["coordinates"] if g["type"] == "MultiLineString" else [g["coordinates"]]
        for line in ls:
            pts = [tuple(p) for p in line if W <= p[0] <= E and S <= p[1] <= N]
            pts = simplify(pts, TOL)
            if len(pts) >= 2:
                yield pts


sections = [list(polygons("ne_10m_land.geojson")), list(polygons("ne_10m_lakes.geojson")), list(lines("ne_10m_rivers.geojson"))]
os.makedirs(os.path.dirname(out), exist_ok=True)
with open(out, "wb") as fh:
    for shapes in sections:
        fh.write(struct.pack("<i", len(shapes)))
        for s in shapes:
            fh.write(struct.pack("<i", len(s)))
            fh.write(b"".join(struct.pack("<ff", x, y) for x, y in s))
print("shapes", [len(s) for s in sections], "points", [sum(len(x) for x in s) for s in sections], os.path.getsize(out) // 1024, "KB")
