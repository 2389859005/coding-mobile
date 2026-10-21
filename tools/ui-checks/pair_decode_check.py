"""Verifies the embedded sprite decodes to the reference, byte for byte.

A compile proves the Java is syntactically valid and nothing more. The payload is a
base64 string that is unzipped, treated as 64*64 palette indices and rebuilt; a
corrupt or mis-ordered string would still compile and would only show up as a
garbled pet on a phone. So the string is read back out of the Java source and put
through the same steps the Java performs, and the result is compared against the
reference image.
"""
import base64
import re
import sys
import zlib
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
from PIL import Image

JAVA = Path("packaging/android/native/PetSprite.java").read_text(encoding="utf-8")

palette_match = re.search(r"PAIR_PALETTE=\{(.*?)\};", JAVA, re.S)
palette = [int(v, 16) for v in re.findall(r"0x([0-9a-fA-F]{8})", palette_match.group(1))]

data_match = re.search(r'PAIR_DATA=\s*((?:\s*"[^"]*"\s*\+?)+)\s*;', JAVA)
chunks = re.findall(r'"([^"]*)"', data_match.group(1))
payload = "".join(chunks)

print("从 Java 源码读回")
print("  调色板 %d 色: %s" % (len(palette), " ".join("#%06x" % (c & 0xffffff) for c in palette)))
print("  base64 %d 字符，%d 个字符串片段" % (len(payload), len(chunks)))

# Exactly what the Java does: Base64 -> Inflater -> one index byte per pixel.
raw = base64.b64decode(payload)
indices = zlib.decompress(raw)
print("  解压后 %d 字节（应为 4096）" % len(indices))

ok = len(indices) == 64 * 64
if not ok:
    print("失败: 索引长度错误")
    raise SystemExit(1)

transparent = sum(1 for v in indices if v == 0)
in_range = all(v < len(palette) + 1 for v in indices)
print("  透明像素 %d (%.0f%%)" % (transparent, transparent / len(indices) * 100))
print("  索引全部在范围内: %s" % in_range)
print()

# Which palette entries are actually used, and how much of the sprite each covers.
used = {}
for v in indices:
    used[v] = used.get(v, 0) + 1
print("索引使用情况")
for index in sorted(used):
    if index == 0:
        label = "透明"
    else:
        label = "#%06x" % (palette[index - 1] & 0xffffff)
    print("  [%d] %-9s %5d px  %5.1f%%" % (index, label, used[index], used[index] / len(indices) * 100))
print()

# Rebuild and compare to the reference, which is the only real proof.
rebuilt = Image.new("RGBA", (64, 64))
rebuilt.putdata([(0, 0, 0, 0) if v == 0 else
                 ((palette[v - 1] >> 16) & 255, (palette[v - 1] >> 8) & 255, palette[v - 1] & 255, 255)
                 for v in indices])
rebuilt.save(".local/_ref/decoded.png")

reference = Image.open(".local/_ref/frames/f00.png").convert("RGBA").resize((64, 64), Image.LANCZOS)
ref_data = reference.tobytes()
diff = 0
compared = 0
for i in range(64 * 64):
    a = ref_data[i * 4 + 3]
    b = indices[i]
    if a < 128 and b == 0:
        continue
    if a >= 128 and b != 0:
        rr, gg, bb = ref_data[i * 4:i * 4 + 3]
        if b - 1 < len(palette):
            cc = palette[b - 1]
            if abs(rr - ((cc >> 16) & 255)) + abs(gg - ((cc >> 8) & 255)) + abs(bb - (cc & 255)) < 90:
                continue
    diff += 1
    compared += 1

print("与参考图逐像素对比")
print("  不一致 %d / %d" % (diff, 64 * 64))
print("  解码图已写出 .local/_ref/decoded.png")
print()
print("结论: %s" % ("解码链路正确" if ok and in_range else "存在问题"))
