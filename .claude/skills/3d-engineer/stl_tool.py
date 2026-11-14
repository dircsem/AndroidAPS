"""Small STL tool with numpy only (plus matplotlib for pictures).

Commands:
  info   <in.stl>                         size, bounds, facets, volume, closed or not
  check  <in.stl>                         mesh problems: open edges, bad edges, flat facets, wrong normals
  transform <in.stl> <out.stl> [--translate X Y Z] [--scale S | --scale X Y Z]
                                [--rotate AXIS DEG] [--mirror AXIS] [--center] [--on-bed]
  section <in.stl> <out.png> --z Z         cross-section at height Z (mm), with a 1 mm grid
  render <in.stl> <out.png>                4 views (top, front, side, 3D)
  compare <a.stl> <b.stl>                  size and volume of both, and the change

Units are mm. Output STL is binary. Input can be ASCII or binary.
The tool never writes over its input file.
"""
import argparse
import os
import struct
import sys

import numpy as np


# ---------- read / write ----------

def read_stl(path):
    """Return (triangles Nx3x3 float64, name)."""
    with open(path, "rb") as f:
        data = f.read()
    if len(data) >= 84:
        count = struct.unpack_from("<I", data, 80)[0]
        if 84 + count * 50 == len(data):
            rec = np.dtype([("n", "<f4", 3), ("v", "<f4", (3, 3)), ("a", "<u2")])
            arr = np.frombuffer(data, dtype=rec, count=count, offset=84)
            name = data[:80].split(b"\0")[0].decode("ascii", "replace").strip()
            return arr["v"].astype(np.float64), name
    text = data.decode("ascii", "replace")
    first = text.split("\n", 1)[0].split()
    name = " ".join(first[1:]) if first and first[0] == "solid" else ""
    verts = [list(map(float, line.split()[1:4]))
             for line in text.splitlines() if line.strip().startswith("vertex")]
    if len(verts) % 3:
        sys.exit("error: vertex count is not a multiple of 3")
    return np.array(verts, dtype=np.float64).reshape(-1, 3, 3), name


def write_stl(path, tris, name="edited"):
    n = facet_normals(tris)
    rec = np.dtype([("n", "<f4", 3), ("v", "<f4", (3, 3)), ("a", "<u2")])
    arr = np.zeros(len(tris), dtype=rec)
    arr["n"] = n
    arr["v"] = tris
    header = name.encode("ascii", "replace")[:80].ljust(80, b" ")
    with open(path, "wb") as f:
        f.write(header)
        f.write(struct.pack("<I", len(tris)))
        f.write(arr.tobytes())


# ---------- geometry ----------

def facet_normals(tris):
    n = np.cross(tris[:, 1] - tris[:, 0], tris[:, 2] - tris[:, 0])
    length = np.linalg.norm(n, axis=1, keepdims=True)
    return np.divide(n, length, out=np.zeros_like(n), where=length > 0)


def volume(tris):
    return float(np.einsum("ij,ij->i", tris[:, 0], np.cross(tris[:, 1], tris[:, 2])).sum() / 6.0)


def bounds(tris):
    pts = tris.reshape(-1, 3)
    return pts.min(axis=0), pts.max(axis=0)


def edge_stats(tris, tol=1e-4):
    """Count edges by how many facets use them. Closed mesh: every edge used by exactly 2."""
    pts = np.round(tris.reshape(-1, 3) / tol).astype(np.int64)
    _, idx = np.unique(pts, axis=0, return_inverse=True)
    idx = idx.reshape(-1, 3)
    edges = np.concatenate([idx[:, [0, 1]], idx[:, [1, 2]], idx[:, [2, 0]]])
    directed = edges.copy()
    edges.sort(axis=1)
    _, counts = np.unique(edges, axis=0, return_counts=True)
    _, dcounts = np.unique(directed, axis=0, return_counts=True)
    return {
        "vertices": int(idx.max()) + 1,
        "open_edges": int((counts == 1).sum()),
        "bad_edges": int((counts > 2).sum()),
        "flipped_edges": int((dcounts > 1).sum()),
    }


def fmt(v):
    return " x ".join(f"{x:.2f}" for x in v)


# ---------- commands ----------

def cmd_info(a):
    tris, name = read_stl(a.input)
    mn, mx = bounds(tris)
    e = edge_stats(tris)
    closed = e["open_edges"] == 0 and e["bad_edges"] == 0
    print(f"file:     {os.path.basename(a.input)}  (solid name: {name or '-'})")
    print(f"facets:   {len(tris)}   vertices: {e['vertices']}")
    print(f"size mm:  {fmt(mx - mn)}  (X x Y x Z)")
    print(f"min mm:   {fmt(mn)}")
    print(f"max mm:   {fmt(mx)}")
    print(f"volume:   {volume(tris) / 1000:.3f} cm3" + ("" if closed else "  (not reliable: mesh is open)"))
    print(f"closed:   {'yes' if closed else 'NO'}")


def cmd_check(a):
    tris, _ = read_stl(a.input)
    e = edge_stats(tris)
    area = np.linalg.norm(np.cross(tris[:, 1] - tris[:, 0], tris[:, 2] - tris[:, 0]), axis=1) / 2
    flat = int((area < 1e-8).sum())
    vol = volume(tris)
    problems = []
    if e["open_edges"]:
        problems.append(f"{e['open_edges']} open edges (holes in the surface)")
    if e["bad_edges"]:
        problems.append(f"{e['bad_edges']} edges used by more than 2 facets")
    if e["flipped_edges"]:
        problems.append(f"{e['flipped_edges']} edges with facets that point the wrong way")
    if flat:
        problems.append(f"{flat} facets with zero area")
    if vol < 0:
        problems.append("volume is negative: all normals point inside")
    print("OK: closed, normals out, no flat facets" if not problems else "PROBLEMS:\n- " + "\n- ".join(problems))
    sys.exit(1 if problems else 0)


def rotation(axis, deg):
    r = np.radians(deg)
    c, s = np.cos(r), np.sin(r)
    return {"x": np.array([[1, 0, 0], [0, c, -s], [0, s, c]]),
            "y": np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]]),
            "z": np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])}[axis]


def cmd_transform(a):
    if os.path.abspath(a.input) == os.path.abspath(a.output):
        sys.exit("error: output must be a new file")
    tris, name = read_stl(a.input)
    pts = tris.reshape(-1, 3)
    if a.center:
        mn, mx = pts.min(axis=0), pts.max(axis=0)
        pts = pts - (mn + mx) / 2
    for axis, deg in a.rotate or []:
        pts = pts @ rotation(axis.lower(), float(deg)).T
    if a.scale:
        s = np.array(a.scale * 3 if len(a.scale) == 1 else a.scale, dtype=float)
        pts = pts * s
    flips = 0
    for axis in a.mirror or []:
        pts[:, "xyz".index(axis.lower())] *= -1
        flips += 1
    if a.scale:
        flips += int((np.array(a.scale) < 0).sum()) if len(a.scale) == 3 else (3 if a.scale[0] < 0 else 0)
    if a.translate:
        pts = pts + np.array(a.translate, dtype=float)
    if a.on_bed:
        pts[:, 2] -= pts[:, 2].min()
    out = pts.reshape(-1, 3, 3)
    if flips % 2:
        out = out[:, [0, 2, 1]]  # keep normals pointing out after a mirror
    write_stl(a.output, out, name)
    print(f"wrote {a.output}")
    cmd_info(argparse.Namespace(input=a.output))


def section_segments(tris, z):
    d = tris[:, :, 2] - z
    segs = []
    for t, dz in zip(tris, d):
        pts = []
        for i, j in ((0, 1), (1, 2), (2, 0)):
            if (dz[i] < 0) != (dz[j] < 0):
                k = dz[i] / (dz[i] - dz[j])
                pts.append(t[i] + k * (t[j] - t[i]))
        if len(pts) == 2:
            segs.append(pts)
    return np.array(segs)


def cmd_section(a):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.collections import LineCollection
    tris, _ = read_stl(a.input)
    segs = section_segments(tris, a.z)
    if not len(segs):
        sys.exit(f"nothing at z={a.z}")
    fig, ax = plt.subplots(figsize=(8, 8))
    ax.add_collection(LineCollection(segs[:, :, :2], linewidths=1, colors="k"))
    ax.autoscale()
    ax.set_aspect("equal")
    ax.minorticks_on()
    ax.grid(which="major", lw=0.6)
    ax.grid(which="minor", lw=0.2)
    ax.xaxis.set_minor_locator(matplotlib.ticker.MultipleLocator(1))
    ax.yaxis.set_minor_locator(matplotlib.ticker.MultipleLocator(1))
    ax.set_title(f"{os.path.basename(a.input)}  section z = {a.z} mm")
    ax.set_xlabel("X mm")
    ax.set_ylabel("Y mm")
    fig.savefig(a.output, dpi=120, bbox_inches="tight")
    print(f"wrote {a.output}  ({len(segs)} segments)")


def cmd_render(a):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from mpl_toolkits.mplot3d.art3d import Poly3DCollection
    tris, _ = read_stl(a.input)
    mn, mx = bounds(tris)
    light = np.array([0.3, -0.5, 0.8])
    light /= np.linalg.norm(light)
    shade = 0.35 + 0.65 * np.clip(facet_normals(tris) @ light, 0, 1)
    colors = np.c_[shade * 0.35, shade * 0.55, shade * 0.85, np.ones_like(shade)]
    views = [("top (looking down Z)", 90, -90), ("front (looking +Y)", 0, -90),
             ("side (looking -X)", 0, 0), ("3D", 25, -60)]
    fig = plt.figure(figsize=(12, 11))
    for i, (title, elev, azim) in enumerate(views, 1):
        ax = fig.add_subplot(2, 2, i, projection="3d")
        ax.add_collection3d(Poly3DCollection(tris, facecolors=colors, edgecolor="none"))
        ax.set_xlim(mn[0], mx[0])
        ax.set_ylim(mn[1], mx[1])
        ax.set_zlim(mn[2], mx[2])
        ax.set_box_aspect(np.maximum(mx - mn, 1e-3))
        ax.view_init(elev=elev, azim=azim)
        ax.set_proj_type("ortho")
        ax.set_title(title)
        ax.set_xlabel("X")
        ax.set_ylabel("Y")
        ax.set_zlabel("Z")
    fig.suptitle(f"{os.path.basename(a.input)}   size {fmt(mx - mn)} mm")
    fig.savefig(a.output, dpi=100, bbox_inches="tight")
    print(f"wrote {a.output}")


def cmd_compare(a):
    ta, _ = read_stl(a.a)
    tb, _ = read_stl(a.b)
    sa = np.subtract(*bounds(ta)[::-1])
    sb = np.subtract(*bounds(tb)[::-1])
    va, vb = volume(ta) / 1000, volume(tb) / 1000
    print(f"{'':10}{'A':>24}{'B':>24}")
    print(f"{'size mm':10}{fmt(sa):>24}{fmt(sb):>24}   change {fmt(sb - sa)}")
    print(f"{'volume':10}{va:>21.3f}cm3{vb:>21.3f}cm3   change {vb - va:+.3f} cm3")
    print(f"{'facets':10}{len(ta):>24}{len(tb):>24}")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("info"); s.add_argument("input"); s.set_defaults(f=cmd_info)
    s = sub.add_parser("check"); s.add_argument("input"); s.set_defaults(f=cmd_check)
    s = sub.add_parser("transform"); s.add_argument("input"); s.add_argument("output")
    s.add_argument("--translate", nargs=3, type=float)
    s.add_argument("--scale", nargs="+", type=float)
    s.add_argument("--rotate", nargs=2, action="append", metavar=("AXIS", "DEG"))
    s.add_argument("--mirror", action="append", choices=list("xyzXYZ"))
    s.add_argument("--center", action="store_true")
    s.add_argument("--on-bed", action="store_true")
    s.set_defaults(f=cmd_transform)
    s = sub.add_parser("section"); s.add_argument("input"); s.add_argument("output")
    s.add_argument("--z", type=float, required=True); s.set_defaults(f=cmd_section)
    s = sub.add_parser("render"); s.add_argument("input"); s.add_argument("output"); s.set_defaults(f=cmd_render)
    s = sub.add_parser("compare"); s.add_argument("a"); s.add_argument("b"); s.set_defaults(f=cmd_compare)
    a = p.parse_args()
    if a.cmd == "transform" and a.scale and len(a.scale) not in (1, 3):
        p.error("--scale takes 1 or 3 numbers")
    a.f(a)


if __name__ == "__main__":
    main()
