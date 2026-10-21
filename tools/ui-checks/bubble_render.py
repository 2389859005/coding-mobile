"""Render SpeechBubble with the real Java code and check the tail geometry.

The one thing that can silently be wrong here is the tail: pointing the wrong way,
or sitting at the wrong end, makes the bubble appear to come out of the desktop
rather than the pet's mouth. The checks below locate the tail by finding the ink
below the bubble body and asserting it is on the expected side.
"""
import struct
import shutil
import subprocess
import sys
import zlib
from pathlib import Path

sys.path.insert(0, ".local")

# The same android.graphics stubs the pet renderer uses; imported rather than
# duplicated, since the two harnesses have to agree on the rasteriser.
import importlib.util


def load_stubs():
    spec = importlib.util.spec_from_file_location("_pet_stubs", str(Path(__file__).with_name("stubs.py")))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.STUBS


STUBS = dict(load_stubs())
STUBS["android/graphics/drawable/Drawable.java"] = r'''
package android.graphics.drawable;
import android.graphics.*;
public abstract class Drawable {
    private final Rect bounds=new Rect();
    public void setBounds(int l,int t,int r,int b){bounds.set(l,t,r,b);}
    public Rect getBounds(){return bounds;}
    public void invalidateSelf(){}
    public abstract void draw(Canvas canvas);
    public int getIntrinsicWidth(){return -1;}
    public int getIntrinsicHeight(){return -1;}
    public abstract void setAlpha(int alpha);
    public abstract void setColorFilter(ColorFilter filter);
    public abstract int getOpacity();
}
'''

HARNESS = r'''
package io.github.abyxs.codingremote;

import android.graphics.Canvas;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

public class BubbleRender {
    public static void main(String[] args) throws Exception {
        int w=420,h=280;
        BufferedImage sheet=new BufferedImage(w*2,h,BufferedImage.TYPE_INT_ARGB);
        Graphics2D sheetG=sheet.createGraphics();
        for(int side=0;side<2;side++){
            sheetG.setColor(new java.awt.Color(0xf6f7f2));
            sheetG.fillRect(side*w,0,w,h);
            BufferedImage img=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);
            Graphics2D g=img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            Canvas canvas=new Canvas(g,w,h);
            SpeechBubble bubble=new SpeechBubble(2.0f,14f,13f,11f);
            bubble.tail(side==0?SpeechBubble.TAIL_LEFT:SpeechBubble.TAIL_RIGHT);
            bubble.colors(0xffffffff,0x33000000,0x14000000);
            bubble.setBounds(0,0,w,h);
            bubble.draw(canvas);
            g.dispose();
            sheetG.drawImage(img,side*w,0,null);
            // Report the silhouette directly. Decoding the PNG in the test harness
            // would only add a second place for the same measurement to go wrong.
            String name = side==0?"TAIL_LEFT":"TAIL_RIGHT";
            for(int y=0;y<h;y++){
                int first=-1,last=-1,count=0;
                for(int x=0;x<w;x++){
                    int argb=img.getRGB(x,y);
                    // Ignore the flat background; anything else is the bubble.
                    if(((argb>>>24)&0xff)>30 && (argb & 0xffffff)!=0xf6f7f2){
                        if(first<0)first=x;
                        last=x; count++;
                    }
                }
                if(count>0) System.out.println("ROW "+name+" "+y+" "+first+" "+last+" "+count);
            }
        }
        sheetG.dispose();
        ImageIO.write(sheet,"png",new File(args[0]));
        System.out.println("wrote "+args[0]);
    }
}
'''

# Build output lives beside this script, not in .local/.
BUILD = Path(__file__).resolve().parent / ".build"
root = Path("packaging/android/native")
work = BUILD / "bubble"
if work.exists():
    shutil.rmtree(work)
(work / "io/github/abyxs/codingremote").mkdir(parents=True)
for rel, body in STUBS.items():
    target = work / rel
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(body, encoding="utf-8")
shutil.copy(root / "SpeechBubble.java", work / "io/github/abyxs/codingremote/SpeechBubble.java")
(work / "io/github/abyxs/codingremote/BubbleRender.java").write_text(HARNESS, encoding="utf-8")

out = work / "classes"
out.mkdir()
sources = [str(p) for p in work.rglob("*.java")]
result = subprocess.run(["javac", "-nowarn", "-encoding", "UTF-8", "-source", "8",
                         "-target", "8", "-d", str(out)] + sources,
                        capture_output=True, text=True)
if result.returncode != 0:
    print(result.stdout[-4000:])
    print(result.stderr[-2000:])
    sys.exit("bubble harness compile failed")

png = BUILD / "bubble.png"
run = subprocess.run(["java", "-cp", str(out), "io.github.abyxs.codingremote.BubbleRender", str(png)],
                     capture_output=True, text=True)
if run.returncode != 0:
    print(run.stdout[-2000:])
    print(run.stderr[-3000:])
    sys.exit("bubble render failed")

# Rows of the silhouette, straight from the renderer.
spans = {"TAIL_LEFT": [], "TAIL_RIGHT": []}
for line in run.stdout.splitlines():
    if not line.startswith("ROW "):
        continue
    _, name, y, first, last, count = line.split()
    spans[name].append((int(y), int(first), int(last), int(count)))

WIDTH = 420
TAIL_HEIGHT = 11 * 2  # the tail is 11dp at density 2
print("bubble body is %dpx wide; tail is %dpx tall" % (WIDTH, TAIL_HEIGHT))
print()
failures = 0
for name in ("TAIL_LEFT", "TAIL_RIGHT"):
    rows = spans[name]
    if not rows:
        print("%-11s NO INK AT ALL" % name)
        failures += 1
        continue
    body = [r for r in rows if r[3] > WIDTH * 0.5]
    lower = [r for r in rows if r[3] <= WIDTH * 0.5]
    if not body:
        print("%-11s no bubble body found" % name)
        failures += 1
        continue
    body_bottom = max(r[0] for r in body)
    tail = [r for r in lower if r[0] > body_bottom]
    if not tail:
        print("%-11s NO TAIL (body ends at y=%d)" % (name, body_bottom))
        failures += 1
        continue
    centre = sum((r[1] + r[2]) / 2 for r in tail) / len(tail)
    depth = tail[-1][0] - body_bottom
    expected_left = name == "TAIL_LEFT"
    side_ok = (centre < WIDTH / 2) == expected_left
    depth_ok = 0 < depth <= TAIL_HEIGHT + 2
    gap_ok = tail[0][0] - body_bottom <= 1
    if not (side_ok and depth_ok and gap_ok):
        failures += 1
    print("%-11s tail x=%6.1f  depth=%2dpx  attached=%s  side=%s"
          % (name, centre, depth, "yes" if gap_ok else "NO",
             "OK" if side_ok else "WRONG SIDE"))
    if not depth_ok:
        print("              depth %d outside the expected 1..%d" % (depth, TAIL_HEIGHT + 2))

print()
print("failures: %d" % failures)
print("bubble image: %s" % png.resolve())
