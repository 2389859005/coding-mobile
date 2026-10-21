"""Render every pet form with the real Java sprite code, then assemble a sheet.

Guessing at vector coordinates without looking at the output is how a "cute dog"
turns out to be an unrecognisable blob, so the drawings are rasterised here with
java.awt stubs standing in for the Android Canvas/Drawable surface.
"""
import shutil
import subprocess
import sys
from pathlib import Path

STUBS = {
    "android/graphics/Rect.java": r'''
package android.graphics;
public class Rect {
    public int left,top,right,bottom;
    public Rect(){}
    public void set(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}
    public int width(){return right-left;}
    public int height(){return bottom-top;}
}
''',
    "android/graphics/Paint.java": r'''
package android.graphics;
public class Paint {
    public static final int ANTI_ALIAS_FLAG=1;
    public static final int FILTER_BITMAP_FLAG=2;
    public enum Style { FILL, STROKE }
    public enum Cap { ROUND }
    public enum Join { ROUND }
    private int color=0xff000000;private float stroke=1f;private Style style=Style.FILL;
    public Paint(){}
    public Paint(int flags){}
    public void setColor(int c){color=c;}
    public int getColor(){return color;}
    public void setAlpha(int a){color=(color&0x00ffffff)|(a<<24);}
    public int getAlpha(){return (color>>>24)&0xff;}
    public void setStyle(Style s){style=s;}
    public Style getStyle(){return style;}
    public void setStrokeWidth(float w){stroke=w;}
    public float getStrokeWidth(){return stroke;}
    public void setStrokeCap(Cap c){}
    public void setStrokeJoin(Join j){}
    public boolean isAntiAlias(){return true;}
}
''',
    "android/graphics/Path.java": r'''
package android.graphics;
public class Path {
    public final java.util.List<float[]> ops=new java.util.ArrayList<float[]>();
    public void reset(){ops.clear();}
    public void moveTo(float x,float y){ops.add(new float[]{0,x,y});}
    public void lineTo(float x,float y){ops.add(new float[]{1,x,y});}
    public void quadTo(float x1,float y1,float x2,float y2){ops.add(new float[]{2,x1,y1,x2,y2});}
    public void cubicTo(float a,float b,float c,float d,float e,float f){ops.add(new float[]{3,a,b,c,d,e,f});}
    public void close(){ops.add(new float[]{4});}
    public void addArc(float l,float t,float r,float b,float start,float sweep){ops.add(new float[]{5,l,t,r,b,start,sweep});}
}
''',
    "android/graphics/Canvas.java": r'''
package android.graphics;
import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
public class Canvas {
    public Graphics2D g;
    private final java.util.Deque<AffineTransform> stack=new java.util.ArrayDeque<AffineTransform>();
    public int width,height;
    public Canvas(Graphics2D g,int w,int h){this.g=g;this.width=w;this.height=h;}
    public void save(){stack.push(g.getTransform());}
    public void restore(){if(!stack.isEmpty())g.setTransform(stack.pop());}
    public void translate(float x,float y){g.translate(x,y);}
    public void scale(float sx,float sy){g.scale(sx,sy);}
    public void rotate(float deg){g.rotate(Math.toRadians(deg));}
    public void rotate(float deg,float px,float py){g.rotate(Math.toRadians(deg),px,py);}
    private Shape shape(Path p){
        Path2D.Float s=new Path2D.Float();
        for(float[] o:p.ops){
            switch((int)o[0]){
                case 0: s.moveTo(o[1],o[2]); break;
                case 1: s.lineTo(o[1],o[2]); break;
                case 2: s.quadTo(o[1],o[2],o[3],o[4]); break;
                case 3: s.curveTo(o[1],o[2],o[3],o[4],o[5],o[6]); break;
                case 4: s.closePath(); break;
                case 5: s.append(new Arc2D.Float(o[1],o[2],o[3]-o[1],o[4]-o[2],o[5],o[6],Arc2D.OPEN),false); break;
            }
        }
        return s;
    }
    private void apply(Paint paint){
        g.setColor(new java.awt.Color(paint.getColor(),true));
        g.setStroke(new BasicStroke(Math.max(0.01f,paint.getStrokeWidth()),BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
    }
    public void drawPath(Path p,Paint paint){apply(paint);Shape s=shape(p);if(paint.getStyle()==Paint.Style.STROKE)g.draw(s);else g.fill(s);}
    public void drawOval(float l,float t,float r,float b,Paint paint){apply(paint);Shape s=new Ellipse2D.Float(l,t,r-l,b-t);if(paint.getStyle()==Paint.Style.STROKE)g.draw(s);else g.fill(s);}
    public void drawCircle(float cx,float cy,float rad,Paint paint){apply(paint);Shape s=new Ellipse2D.Float(cx-rad,cy-rad,rad*2,rad*2);if(paint.getStyle()==Paint.Style.STROKE)g.draw(s);else g.fill(s);}
    public void drawRoundRect(float l,float t,float r,float b,float rx,float ry,Paint paint){apply(paint);Shape s=new RoundRectangle2D.Float(l,t,r-l,b-t,rx*2,ry*2);if(paint.getStyle()==Paint.Style.STROKE)g.draw(s);else g.fill(s);}
    public void drawLine(float x1,float y1,float x2,float y2,Paint paint){apply(paint);g.draw(new Line2D.Float(x1,y1,x2,y2));}
    public void drawRect(float l,float t,float r,float b,Paint paint){apply(paint);Shape s=new java.awt.geom.Rectangle2D.Float(l,t,r-l,b-t);if(paint.getStyle()==Paint.Style.STROKE)g.draw(s);else g.fill(s);}
    public void scale(float sx,float sy,float px,float py){g.translate(px,py);g.scale(sx,sy);g.translate(-px,-py);}
    public void clipRect(Rect r){g.clip(new java.awt.Rectangle(r.left,r.top,r.width(),r.height()));}
    public void clipRect(int l,int t,int r,int b){g.clip(new java.awt.Rectangle(l,t,r-l,b-t));}
    /** Draws the palette bitmap through the same transform the caller set up, so a
        render of the sprite shows the actual decoded pixels rather than a placeholder. */
    public void drawBitmap(Bitmap bitmap,Rect src,RectF dst,Paint paint){
        if(bitmap==null||bitmap.image==null)return;
        java.awt.Composite old=g.getComposite();
        if(paint!=null&&paint.getAlpha()<255){
            g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER,paint.getAlpha()/255f));
        }
        g.drawImage(bitmap.image,Math.round(dst.left),Math.round(dst.top),
                    Math.round(dst.right-dst.left),Math.round(dst.bottom-dst.top),null);
        g.setComposite(old);
    }
}
''',
    "android/graphics/RectF.java": r'''
package android.graphics;
public class RectF {
    public float left,top,right,bottom;
    public RectF(){}
    public RectF(float l,float t,float r,float b){left=l;top=t;right=r;bottom=b;}
    public float width(){return right-left;}
    public float height(){return bottom-top;}
}
''',
    "android/graphics/Bitmap.java": r'''
package android.graphics;
/** Backed by a BufferedImage so a headless render really draws the pixels. */
public class Bitmap {
    public enum Config { ARGB_8888 }
    public java.awt.image.BufferedImage image;
    public final int width,height;
    private Bitmap(int w,int h){width=w;height=h;image=new java.awt.image.BufferedImage(w,h,java.awt.image.BufferedImage.TYPE_INT_ARGB);}
    public static Bitmap createBitmap(int w,int h,Config config){return new Bitmap(w,h);}
    public void setPixels(int[] pixels,int offset,int stride,int x,int y,int w,int h){
        image.setRGB(x,y,w,h,pixels,offset,stride);
    }
}
''',
    "android/util/Base64.java": r'''
package android.util;
public class Base64 {
    public static final int DEFAULT=0;
    public static byte[] decode(String text,int flags){
        return java.util.Base64.getDecoder().decode(text);
    }
}
''',
    "android/graphics/Color.java": r'''
package android.graphics;
public class Color {
    public static final int TRANSPARENT=0;
    public static float luminance(int c){
        return (float)(0.2126*lin((c>>16)&0xff)+0.7152*lin((c>>8)&0xff)+0.0722*lin(c&0xff));
    }
    private static double lin(int v){double x=v/255.0;return x<=0.04045?x/12.92:Math.pow((x+0.055)/1.055,2.4);}
}
''',
    "android/graphics/PixelFormat.java": r'''
package android.graphics;
public class PixelFormat { public static final int TRANSLUCENT=-3; }
''',
    "android/graphics/ColorFilter.java": r'''
package android.graphics;
public class ColorFilter {}
''',
    "android/graphics/drawable/Drawable.java": r'''
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
''',
}

HARNESS = r'''
package io.github.abyxs.codingremote;

import android.graphics.Canvas;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

public class PetSheet {
    public static void main(String[] args) throws Exception {
        int tile=200, cols=3;
        int rows=(PetSprite.FORMS.length+cols-1)/cols;
        BufferedImage sheet=new BufferedImage(cols*tile,rows*tile,BufferedImage.TYPE_INT_ARGB);
        Graphics2D sheetG=sheet.createGraphics();
        sheetG.setColor(new Color(0xf6f7f2));
        sheetG.fillRect(0,0,sheet.getWidth(),sheet.getHeight());
        for(int i=0;i<PetSprite.FORMS.length;i++){
            String form=PetSprite.FORMS[i];
            // Two poses side by side: idle and mid-hop, so the motion shows too.
            for(int pose=0;pose<2;pose++){
                BufferedImage img=new BufferedImage(tile/2,tile,BufferedImage.TYPE_INT_ARGB);
                Graphics2D g=img.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                Canvas canvas=new Canvas(g,tile/2,tile);
                // size and bounds must agree: the sprite reports `size` as its
                // intrinsic dimension and the host scales that to the bounds, so
                // handing it 200 while bounding it to 100 made the renderer draw at a
                // different scale from the phone for no reason.
                PetSprite pet=new PetSprite(form,0xff27634d,0xffeaf1e9,tile/2);
                pet.setBounds(0,0,tile/2,tile);
                pet.state(pose==0?"idle":"working");
                pet.advance(0.25f,0f,pose==0?0f:0.8f,pose==0?0f:0.6f);
                pet.draw(canvas);
                g.dispose();
                sheetG.drawImage(img,(i%cols)*tile+pose*(tile/2),(i/cols)*tile,null);
            }
            sheetG.setColor(new Color(0x242b27));
            sheetG.setFont(new Font("SansSerif",Font.BOLD,15));
            sheetG.drawString(PetSprite.LABELS[i]+"  "+form,(i%cols)*tile+8,(i/cols)*tile+tile-10);
        }
        sheetG.dispose();
        ImageIO.write(sheet,"png",new File(args[0]));
        System.out.println("wrote "+args[0]+"  forms="+PetSprite.FORMS.length);
    }
}
'''

# Build output lives beside this script, not in .local/.
BUILD = Path(__file__).resolve().parent / ".build"
root = Path("packaging/android/native")
work = BUILD / "pet"
if work.exists():
    shutil.rmtree(work)
(work / "io/github/abyxs/codingremote").mkdir(parents=True)
for rel, body in STUBS.items():
    target = work / rel
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(body, encoding="utf-8")

shutil.copy(root / "PetSprite.java", work / "io/github/abyxs/codingremote/PetSprite.java")
(work / "io/github/abyxs/codingremote/PetSheet.java").write_text(HARNESS, encoding="utf-8")

sources = [str(p) for p in work.rglob("*.java")]
out = work / "classes"
out.mkdir()
result = subprocess.run(["javac", "-nowarn", "-encoding", "UTF-8", "-source", "8",
                         "-target", "8", "-d", str(out)] + sources,
                        capture_output=True, text=True)
if result.returncode != 0:
    print(result.stdout[-5000:])
    print(result.stderr[-3000:])
    sys.exit("harness compile failed")

png = BUILD / "pet_sheet.png"
run = subprocess.run(["java", "-cp", str(out), "io.github.abyxs.codingremote.PetSheet", str(png)],
                     capture_output=True, text=True)
print(run.stdout)
if run.returncode != 0:
    print(run.stderr[-3000:])
    sys.exit("render failed")
print("sheet: %s" % png.resolve())
