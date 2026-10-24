"""Real debug-player pixel check; never prints or saves book text/screenshots."""
import argparse
import json
import pathlib
import re
import xml.etree.ElementTree as ET

import uiautomator2


def luminance(rgb):
    channels = [x / 255 for x in rgb]
    linear = [x / 12.92 if x <= .04045 else ((x + .055) / 1.055) ** 2.4 for x in channels]
    return sum(x * weight for x, weight in zip(linear, [.2126, .7152, .0722]))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    device = uiautomator2.connect(args.device)
    # These labels are real ReadAloudPlayerPanel controls, never chapter text.
    labels = ("引擎", "角色")
    nodes = list(ET.fromstring(device.dump_hierarchy()).iter("node"))
    image = device.screenshot().convert("RGB")
    results = []
    for label in labels:
        node = next((n for n in nodes if n.get("package") == "io.legado.miss.app.debug"
                     and n.get("text") == label), None)
        assert node is not None, "PLAYER_CONTROL_NOT_VISIBLE"
        bounds = tuple(map(int, re.findall(r"\d+", node.get("bounds"))))
        raw = image.crop(bounds).tobytes()
        values = sorted(luminance(rgb) for rgb in zip(raw[0::3], raw[1::3], raw[2::3]))
        # Wide quantiles include solid glyph strokes and reject isolated image noise.
        low, high = values[len(values) // 100], values[99 * len(values) // 100]
        contrast = (high + .05) / (low + .05)
        results.append(dict(label=label,contrast=round(contrast, 2),pass_=contrast >= 4.5))
    result = dict(pass_=all(v["pass_"] for v in results),controls=results)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result))
    return 0 if result["pass_"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
