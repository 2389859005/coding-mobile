"""Renders the pet at the phone's own geometry, at every density Android uses.

pet_render.py draws into a 100x200 tile because that is a readable contact sheet, but
the phone never does that: there the host ImageView is 64dp square and the sprite
reports 64dp as its intrinsic size, so bounds and intrinsic agree and the density
cancels out. That difference matters when the sprite maps itself onto its bounds,
because a non-square tile exercises a code path the phone never takes.

This harness builds the square case at 1.0x through 3.5x and reports how much of the
host the sprite actually inks. A sprite that "does not show" reads as a near-zero
percentage here rather than as an opinion.
"""
import pathlib
import shutil
import subprocess
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

HERE = pathlib.Path(__file__).resolve().parent
BUILD = HERE / ".build"
ROOT = pathlib.Path("packaging/android/native")

# Reuse the stub layer from the sheet harness rather than maintaining a second copy.
namespace = {}
exec(compile((HERE / "pet_render.py").read_text(encoding="utf-8").split("HARNESS =")[0],
             "pet_render_stubs", "exec"), namespace)
STUBS = namespace["STUBS"]

HARNESS = r'''
package io.github.abyxs.codingremote;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import android.graphics.Canvas;

public class PhoneGeom {
    public static void main(String[] args) throws Exception {
        float[] densities = {1.0f, 1.5f, 2.0f, 2.625f, 2.75f, 3.0f, 3.5f};
        System.out.println("form      density   host   inked   inked%   bbox        spills");
        boolean bad = false;
        for (String form : PetSprite.FORMS) {
            for (float density : densities) {
                // What ImageView does: bounds become the measured view size, and the
                // sprite's own `size` is dp(64) exactly as the overlay passes it.
                int host = Math.round(64 * density);
                int size = Math.round(64 * density);
                BufferedImage img = new BufferedImage(host, host, BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D g = img.createGraphics();
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                                   java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                Canvas canvas = new Canvas(g, host, host);
                PetSprite pet = new PetSprite(form, 0xff27634d, 0xffeaf1e9, size);
                pet.setBounds(0, 0, host, host);
                pet.advance(0.25f, 0f, 0f, 0f);
                pet.draw(canvas);
                g.dispose();

                int inked = 0, minx = host, miny = host, maxx = -1, maxy = -1;
                // Count only the sprite's own palette, so canvas-level decoration the
                // stub might leave behind is not mistaken for the animal. Both rosters
                // must be listed: an earlier version carried only the pair's colours, so
                // the robot scored 3.4% and looked like a sprite that failed to draw
                // when it was the whitelist that failed to match.
                int[] palette = {
                    // maltese / the pair, from PAIR_PALETTE
                    0xffffff, 0xe9cc9c, 0xf5e2b9, 0xccb296, 0xffcbc6, 0x2b2b2b,
                    0x3a3a3a, 0xf2f2f2, 0xffd0d8, 0xffe9c8,
                    // robot, from the default branch of form()
                    0xa9d8e8, 0x5fa6bf, 0xe4f2f7, 0xff9a9a, 0xffd166, 0x2f4858, 0x8ff6ff,
                    // overlay chrome the sprite may be drawn against
                    0x27634d, 0xeaf1e9, 0xf2f4ef,
                };
                for (int y = 0; y < host; y++) {
                    for (int x = 0; x < host; x++) {
                        int argb = img.getRGB(x, y);
                        if ((argb >>> 24) <= 40) continue;
                        int rgb = argb & 0xffffff;
                        boolean own = false;
                        for (int p : palette) {
                            int dr = Math.abs(((rgb >> 16) & 255) - ((p >> 16) & 255));
                            int dg = Math.abs(((rgb >> 8) & 255) - ((p >> 8) & 255));
                            int db = Math.abs((rgb & 255) - (p & 255));
                            if (dr < 26 && dg < 26 && db < 26) { own = true; break; }
                        }
                        if (!own) continue;
                        inked++;
                        if (x < minx) minx = x;
                        if (y < miny) miny = y;
                        if (x > maxx) maxx = x;
                        if (y > maxy) maxy = y;
                    }
                }
                String bbox = maxx < 0 ? "-" : (maxx - minx + 1) + "x" + (maxy - miny + 1);
                String spills = maxx < 0 ? "-"
                        : (minx <= 0 || miny <= 0 || maxx >= host - 1 || maxy >= host - 1
                           ? "edge" : "no");
                double percent = 100.0 * inked / (host * (double) host);
                if (percent < 5.0 || maxx < 0) bad = true;
                System.out.printf("%-9s %7.3f %6d %7d %7.1f%%   %-10s  %s%n",
                        form, density, host, inked, percent, bbox, spills);
            }
        }
        System.out.println();
        System.out.println(bad ? "结论: 有密度下几乎不着墨 —— 悬浮窗会看起来完全不显示"
                               : "结论: 所有密度下都正常着墨");
    }
}
'''

work = BUILD / "phone"
if work.exists():
    shutil.rmtree(work)
(work / "io/github/abyxs/codingremote").mkdir(parents=True)
for rel, body in STUBS.items():
    target = work / rel
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(body, encoding="utf-8")

shutil.copy(ROOT / "PetSprite.java", work / "io/github/abyxs/codingremote/PetSprite.java")
(work / "io/github/abyxs/codingremote/PhoneGeom.java").write_text(HARNESS, encoding="utf-8")

sources = [str(p) for p in work.rglob("*.java")]
out = work / "classes"
out.mkdir()
result = subprocess.run(["javac", "-nowarn", "-encoding", "UTF-8", "-source", "8",
                         "-target", "8", "-d", str(out)] + sources,
                        capture_output=True, text=True)
if result.returncode != 0:
    print(result.stdout[-4000:])
    print(result.stderr[-3000:])
    sys.exit("探针编译失败")

run = subprocess.run(["java", "-cp", str(out), "io.github.abyxs.codingremote.PhoneGeom"],
                     capture_output=True, text=True, encoding="utf-8", errors="replace")
print(run.stdout)
if run.stderr.strip():
    print(run.stderr[-2000:])
