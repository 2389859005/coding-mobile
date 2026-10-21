"""android.graphics stubs shared by the Java render harnesses."""

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
    public void setColorFilter(ColorFilter f){}
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

