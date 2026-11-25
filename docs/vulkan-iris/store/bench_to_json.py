"""Fills bench.json's "packs" from the two benchmark reports (A: vulkaniris-bench-gl.txt, B: vulkaniris-phase7.txt
from prodBenchVulkan), so the page chart only ever shows measured numbers.
    python docs/vulkan-iris/store/bench_to_json.py [gl report] [vulkan report]
"""
import json
import pathlib
import re
import sys

HERE = pathlib.Path(__file__).resolve().parent
RUN = HERE.parents[2] / "fabric" / "run-prod"
NAMES = {  # report pack file → page name
    "Bliss": "Bliss", "BSL": "BSL", "ComplementaryReimagined": "Complementary Reimagined",
    "ComplementaryUnbound": "Complementary Unbound", "MakeUp": "MakeUp UltraFast", "Mellow": "Mellow",
    "miniature": "miniature", "Noble": "Noble", "Sildur": "Sildur's Vibrant Extreme", "Solas": "Solas",
}


def averages(path):
    out, pack = {}, None
    for line in pathlib.Path(path).read_text(encoding="utf-8", errors="replace").splitlines():
        if line.startswith("== "):
            pack = line[3:].strip()
        m = re.search(r"bench.*?average ([0-9.]+) FPS", line)
        if m:
            key = "none" if line.startswith("bench (no pack") or (pack or "").startswith("(no pack") else pack
            out[key] = float(m.group(1))
    return out


def name(pack):
    for k, v in NAMES.items():
        if pack.startswith(k):
            return v
    return None


def main():
    gl = averages(sys.argv[1] if len(sys.argv) > 1 else RUN / "vulkaniris-bench-gl.txt")
    vk = averages(sys.argv[2] if len(sys.argv) > 2 else RUN / "vulkaniris-phase7.txt")
    rows = []
    for pack, a in gl.items():
        n = name(pack) if pack != "none" else None
        if n and pack in vk:
            rows.append({"name": n, "gl": round(a), "vk": round(vk[pack])})
    rows.sort(key=lambda r: r["name"].lower())
    path = HERE / "bench.json"
    data = json.loads(path.read_text(encoding="utf-8"))
    data["packs"] = rows
    data["control"] = {"gl": round(gl.get("none", 0)), "vk": round(vk.get("none", 0))}
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    for r in rows:
        print(f'{r["name"]:28} GL {r["gl"]:5}  Vulkan {r["vk"]:5}  {100 * (r["vk"] / r["gl"] - 1):+.0f} %')


if __name__ == "__main__":
    main()
