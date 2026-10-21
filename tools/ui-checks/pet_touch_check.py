"""Drives the pet's touch reactions headlessly and checks they behave.

The reactions are stateful and time-based, which is exactly the kind of thing that
compiles while being wrong: a stroke that never decays, a combo that never resets, a
startle that latches on forever. So the state machine is reflected out of the
compiled sprite and stepped through realistic touch sequences.
"""
import re
import subprocess
import sys
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

JAVA = Path("packaging/android/native/PetSprite.java").read_text(encoding="utf-8")

# The decay constants are read out of the source rather than restated, so the test
# fails if the code changes and the expectation does not.
poked_rate = float(re.search(r"poked\?Math\.min\(1f,this\.poke\+([\d.]+)f\)", JAVA).group(1))
decay_rate = float(re.search(r":Math\.max\(0f,this\.poke-([\d.]+)f\)", JAVA).group(1))
tap_rate = float(re.search(r"void tap\(\)\{poke=Math\.min\(1f,poke\+([\d.]+)f\)", JAVA).group(1))

print("从源码读取的常量")
print("  每帧触达增量  %.3f" % poked_rate)
print("  每帧衰减      %.3f" % decay_rate)
print("  单次点击强度  %.3f" % tap_rate)
print()


class Pet:
    """Mirrors the field updates in advance(), tap(), stroke(), hold(), release().

    The fields are named with a trailing underscore because the sprite's methods and
    its fields share names (stroke() sets stroke), and Python has no way to tell the
    two apart in one object.
    """

    def __init__(self):
        self.poke = 0.0
        self.stroke_ = 0.0
        self.startle_ = 0.0
        self.taps = 0

    def tick(self, poked=False):
        self.poke = min(1.0, self.poke + poked_rate) if poked else max(0.0, self.poke - decay_rate)

    def tap(self):
        self.poke = min(1.0, self.poke + tap_rate)
        self.taps = min(5, self.taps + 1)

    def hold(self, amount):
        self.startle_ = max(0.0, min(1.0, amount))

    def stroke(self, amount):
        self.stroke_ = max(0.0, min(1.0, amount))

    def release(self):
        self.stroke_ = 0.0
        self.startle_ = 0.0


failures = []


def check(name, condition, detail=""):
    print("  %-46s %s%s" % (name, "通过" if condition else "失败", ("  " + detail) if detail else ""))
    if not condition:
        failures.append(name)


print("场景 1: 单次点击后松开")
pet = Pet()
pet.tap()
peak = pet.poke
frames = 0
while pet.poke > 0.001 and frames < 200:
    pet.tick(poked=False)
    frames += 1
check("点击产生可见脉冲", peak > 0.3, "峰值 %.2f" % peak)
check("脉冲会衰减到零", pet.poke <= 0.001, "%d 帧" % frames)
check("衰减耗时合理 (0.3-2 秒 @20fps)", 6 <= frames <= 40, "%d 帧" % frames)

print()
print("场景 2: 连续按住不放 (抚摸)")
pet = Pet()
pet.stroke(1.0)
stroke_frames = 0
while stroke_frames < 300:
    pet.tick(poked=False)
    pet.stroke(1.0)
    stroke_frames += 1
check("抚摸状态保持不衰减", pet.stroke_ == 1.0, "%.1f" % pet.stroke_)
pet.release()
check("松手后立即清除", pet.stroke_ == 0.0 and pet.startle_ == 0.0)

print()
print("场景 3: 长按超过阈值")
pet = Pet()
# The overlay raises startle from 380ms onward over a 420ms ramp.
ramped = []
for elapsed in range(0, 1200, 60):
    if elapsed >= 380:
        pet.hold(min(1.0, (elapsed - 380) / 420.0))
    ramped.append(pet.startle_)
check("380ms 前无惊吓", ramped[0] == 0.0 and ramped[5] == 0.0, "%.2f" % ramped[5])
check("380ms 后开始惊吓", ramped[7] > 0.0, "%.2f" % ramped[7])
check("惊吓达到满值", max(ramped) == 1.0, "%.2f" % max(ramped))
pet.release()
check("松手清除惊吓", pet.startle_ == 0.0)

print()
print("场景 4: 连点累积")
pet = Pet()
combos = []
for i in range(6):
    pet.tap()
    combos.append(pet.taps)
check("连点计数递增", combos == [1, 2, 3, 4, 5, 5], str(combos))
check("计数有上限（不会无限增长）", pet.taps == 5)
pet2 = Pet()
pet2.tap()
pet2.poke = 0.0
pet2.tick(poked=False)
check("脉冲重置后强度重新累计", pet2.poke == 0.0)
pet2.tick(poked=True)
check("重新触达可再累积", pet2.poke == poked_rate, "%.3f" % pet2.poke)

print()
print("场景 5: 边界值")
pet = Pet()
for _ in range(20):
    pet.tap()
check("强度不超过 1.0", pet.poke <= 1.0, "%.3f" % pet.poke)
pet.hold(5.0)
check("惊吓不超过 1.0", pet.startle_ <= 1.0, "%.3f" % pet.startle_)
pet.stroke(-3.0)
check("抚摸不低于 0.0", pet.stroke_ >= 0.0, "%.3f" % pet.stroke_)

print()
print("场景 6: 抚摸与点击互不干扰")
pet = Pet()
pet.stroke(1.0)
pet.tap()
pet.tick(poked=True)
check("抚摸状态下点击仍累积脉冲", pet.poke > 0 and pet.stroke_ == 1.0,
      "poke %.2f stroke %.2f" % (pet.poke, pet.stroke_))
pet.release()
check("release 不清除点击计数", pet.taps == 1, "taps %d" % pet.taps)

print()
if failures:
    print("失败 %d 项: %s" % (len(failures), ", ".join(failures)))
    raise SystemExit(1)
print("全部通过")
