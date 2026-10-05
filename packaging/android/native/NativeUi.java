package io.github.abyxs.codingremote;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.Gravity;
import android.view.*;
import android.widget.*;

/** Shared visual language, including density-independent line icons. */
final class NativeUi {
    static int INK=0xff242b27, MUTED=0xff788079, GREEN=0xff27634d;
    static int CANVAS=0xfff6f7f2, WHITE=0xffffffff, LINE=0xffe5e8e1;
    static int TINT=0xffeaf1e9, SOFT=0xfff2f4ef, RED=0xffa4453e;
    static int DIFF_ADD=0xffdceee0,DIFF_REMOVE=0xfff8e3df;
    static int CODE=0xff305d77,KEYWORD=0xff8054a2,STRING=0xff357149,EMPHASIS=0xff976c28;
    static void theme(String name){
        boolean dark=name.equals("dark"),warm=false,green=name.equals("green")||name.equals("warm");
        INK=dark?0xffe8eee9:0xff242b27;MUTED=dark?0xffa6b2a9:0xff727b73;GREEN=dark?0xff92cbb0:warm?0xff806346:0xff27634d;
        CANVAS=dark?0xff161c19:green?0xfff6f7f2:0xffffffff;WHITE=dark?0xff202824:warm?0xfffffaf2:0xffffffff;
        LINE=dark?0xff354039:warm?0xffe6dbcb:0xffe5e8e1;TINT=dark?0xff2d4436:warm?0xffefe3d2:0xffeaf1e9;
        SOFT=dark?0xff28322c:warm?0xfff1e9dc:0xfff2f4ef;RED=dark?0xffefa196:0xffa4453e;
        DIFF_ADD=dark?0xff284c38:warm?0xffe9e4ce:0xffdceee0;DIFF_REMOVE=dark?0xff512f2c:0xfff8e3df;
        CODE=dark?0xffb9d9ef:0xff305d77;KEYWORD=dark?0xffd6b2f1:0xff8054a2;STRING=dark?0xffaad0a2:0xff357149;EMPHASIS=dark?0xffead196:0xff976c28;
        if(name.equals("ocean")){CANVAS=0xfff3f8fc;WHITE=0xffffffff;SOFT=0xffeaf2f8;TINT=0xffdeedf7;GREEN=0xff246d99;CODE=0xff244d6c;KEYWORD=0xff844bb0;STRING=0xff29745e;EMPHASIS=0xffa26725;}
        if(name.equals("lavender")){CANVAS=0xfff8f5fc;WHITE=0xfffffdff;SOFT=0xfff0eaf7;TINT=0xffe8dcf2;GREEN=0xff765198;CODE=0xff554478;KEYWORD=0xffa44379;STRING=0xff347a64;EMPHASIS=0xff91631d;}
        if(name.equals("sand")){CANVAS=0xfffbf6ed;WHITE=0xfffffbf4;SOFT=0xfff2e8d8;TINT=0xffeedfc8;GREEN=0xff896338;CODE=0xff77543b;KEYWORD=0xff924c73;STRING=0xff526e35;EMPHASIS=0xffb05332;}
        if(name.equals("midnight")){INK=0xffe4eafa;MUTED=0xff9eacc7;CANVAS=0xff111827;WHITE=0xff1a2334;SOFT=0xff222f46;LINE=0xff334460;TINT=0xff253d59;GREEN=0xff8cc6f3;RED=0xfff0a9b9;CODE=0xffd2e4fc;KEYWORD=0xffc5adff;STRING=0xffa2dbb4;EMPHASIS=0xfff2cf8b;DIFF_ADD=0xff254936;DIFF_REMOVE=0xff502c39;}
    }
    private final Context context;
    NativeUi(Context c){context=c;}
    int dp(float x){return Math.round(context.getResources().getDisplayMetrics().density*x);}
    GradientDrawable surface(int color,int radius,boolean border){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));
        if(border)d.setStroke(dp(1),LINE);return d;
    }
    RippleDrawable touch(int color,int radius,boolean border){return new RippleDrawable(ColorStateList.valueOf(0x1627634d),surface(color,radius,border),surface(WHITE,radius,false));}
    TextView text(String label,int size,int color){
        TextView v=new TextView(context);v.setText(label);v.setTextSize(size);v.setTextColor(color);
        v.setFontFeatureSettings("kern");v.setIncludeFontPadding(false);v.setLineSpacing(dp(4),1);return v;
    }
    Button button(String label,boolean primary){
        Button b=new Button(context);b.setText(label);b.setAllCaps(false);b.setTextSize(14);
        b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));b.setGravity(Gravity.CENTER);
        b.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{0xffa9b0a8,primary?(Color.luminance(CANVAS)<.2?CANVAS:0xffffffff):INK}));
        b.setBackground(touch(primary?GREEN:SOFT,14,false));b.setStateListAnimator(null);
        b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(14),dp(8),dp(14),dp(8));return b;
    }
    Button iconButton(String icon,String description){
        Button b=button("",false);b.setBackground(touch(Color.TRANSPARENT,24,false));
        b.setCompoundDrawablesWithIntrinsicBounds(null,null,null,null);
        b.setPadding(dp(12),dp(12),dp(12),dp(12));
        centerIcon(b,icon,INK,24);b.setContentDescription(description);return b;
    }
    void centerIcon(Button button,String name,int color,int size){centerIcon(button,name,color,size,0);}
    void centerIcon(Button button,String name,int color,int size,int offsetY){button.setCompoundDrawables(null,null,null,null);button.setCompoundDrawablePadding(0);button.setPadding(0,0,0,0);button.setForeground(new Drawable(){final Symbol symbol=new Symbol(name,color,dp(size));public void draw(Canvas canvas){canvas.save();canvas.translate((getBounds().width()-dp(size))/2f,(getBounds().height()-dp(size))/2f+dp(offsetY));symbol.draw(canvas);canvas.restore();}public void setAlpha(int a){}public void setColorFilter(ColorFilter f){}public int getOpacity(){return PixelFormat.TRANSLUCENT;}});}
    void leading(TextView v,String name,int color,int size){v.setCompoundDrawables(new Symbol(name,color,dp(size)),null,null,null);v.setCompoundDrawablePadding(dp(8));}
    ImageView icon(String name,int color,int size){ImageView v=new ImageView(context);v.setImageDrawable(new Symbol(name,color,dp(size)));v.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);return v;}

    void input(EditText input){input.setTextColor(INK);input.setHintTextColor(MUTED);input.setTextSize(15);input.setPadding(dp(14),dp(12),dp(14),dp(12));input.setBackground(touch(SOFT,14,true));input.setBackgroundTintList(null);}
    Button menu(String label,boolean selected){Button b=button(label,false);b.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);b.setTextColor(selected?GREEN:INK);b.setBackground(touch(selected?TINT:Color.TRANSPARENT,12,false));b.setMaxLines(2);b.setEllipsize(android.text.TextUtils.TruncateAt.END);return b;}
    android.widget.ArrayAdapter<String> choices(java.util.List<String> values){return new android.widget.ArrayAdapter<String>(context,android.R.layout.simple_list_item_1,values){
        public View getView(int p,View old,ViewGroup parent){TextView label=old instanceof TextView?(TextView)old:text("",15,INK);label.setText(getItem(p));label.setTextColor(INK);label.setMinHeight(dp(52));label.setGravity(Gravity.CENTER_VERTICAL);label.setPadding(dp(14),dp(10),dp(14),dp(10));label.setMaxLines(2);label.setEllipsize(android.text.TextUtils.TruncateAt.END);label.setBackground(touch(Color.TRANSPARENT,12,false));label.setCompoundDrawables(null,null,parent instanceof Spinner?new Symbol("down",MUTED,dp(16)):null,null);label.setCompoundDrawablePadding(dp(8));return label;}
        public View getDropDownView(int p,View old,ViewGroup parent){return getView(p,old,parent);}
    };}
    Spinner spinner(String[] values){Spinner v=new Spinner(context,Spinner.MODE_DROPDOWN);v.setAdapter(choices(java.util.Arrays.asList(values)));v.setBackground(touch(SOFT,14,true));v.setPopupBackgroundDrawable(surface(WHITE,18,true));v.setPadding(dp(4),0,dp(12),0);return v;}
    void reveal(View view,boolean upward){view.animate().cancel();view.animate().withEndAction(null);view.setVisibility(View.VISIBLE);view.setAlpha(0);view.setTranslationY(dp(upward?10:-6));view.animate().alpha(1).translationY(0).setDuration(180).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();}
    void window(Window window,boolean sheet){window.setBackgroundDrawableResource(android.R.color.transparent);window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);window.setDimAmount(.32f);window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);int width=Math.min(dp(560),context.getResources().getDisplayMetrics().widthPixels-dp(24));window.setLayout(width,-2);if(sheet)window.setGravity(Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);WindowManager.LayoutParams attributes=window.getAttributes();if(sheet)attributes.y=dp(12);attributes.windowAnimations=context.getResources().getIdentifier(sheet?"SheetMotion":"DialogMotion","style",context.getPackageName());window.setAttributes(attributes);}

    /** Keep transcript space available when approvals, a draft and the IME coexist. */
    static final class MenuScroll extends ScrollView {
        private final int cap;
        MenuScroll(Context c,int height){super(c);cap=Math.round(height*c.getResources().getDisplayMetrics().density);setFillViewport(false);setVerticalScrollBarEnabled(true);}
        @Override protected void onMeasure(int width,int height){int limit=Math.min(cap,getResources().getDisplayMetrics().heightPixels/3);if(View.MeasureSpec.getMode(height)!=View.MeasureSpec.UNSPECIFIED)limit=Math.min(limit,View.MeasureSpec.getSize(height));super.onMeasure(width,View.MeasureSpec.makeMeasureSpec(limit,View.MeasureSpec.AT_MOST));}
    }

    /** Keep transcript space available when approvals, a draft and the IME coexist. */
    static final class DockScroll extends ScrollView {
        DockScroll(Context c){super(c);setFillViewport(false);setClipToPadding(false);setVerticalScrollBarEnabled(false);}
        @Override protected void onMeasure(int width,int height){
            int available=android.view.View.MeasureSpec.getSize(height);
            if(available>0)height=android.view.View.MeasureSpec.makeMeasureSpec(Math.min(available,Math.max((int)(getResources().getDisplayMetrics().density*116),available*2/3)),android.view.View.MeasureSpec.AT_MOST);
            super.onMeasure(width,height);
        }
    }

    /** Let a bounded execution block consume vertical drags before the transcript. */
    static final class InnerScroll extends ScrollView {
        private float lastY;
        InnerScroll(Context c){super(c);setFillViewport(false);setNestedScrollingEnabled(true);}
        @Override public boolean dispatchTouchEvent(android.view.MotionEvent event){
            int action=event.getActionMasked();float y=event.getY();
            if(action==android.view.MotionEvent.ACTION_DOWN){lastY=y;getParent().requestDisallowInterceptTouchEvent(true);}
            else if(action==android.view.MotionEvent.ACTION_MOVE){int direction=y<lastY?1:-1;getParent().requestDisallowInterceptTouchEvent(canScrollVertically(direction));lastY=y;}
            else if(action==android.view.MotionEvent.ACTION_UP||action==android.view.MotionEvent.ACTION_CANCEL)getParent().requestDisallowInterceptTouchEvent(false);
            return super.dispatchTouchEvent(event);
        }
    }

    /** Short questions wrap their content; long forms remain scrollable above the keyboard. */
    static final class FormScroll extends ScrollView {
        FormScroll(Context c){super(c);setFillViewport(false);}
        @Override protected void onMeasure(int width,int height){
            int cap=Math.min((int)(380*getResources().getDisplayMetrics().density),getResources().getDisplayMetrics().heightPixels/2);
            if(android.view.View.MeasureSpec.getMode(height)!=android.view.View.MeasureSpec.UNSPECIFIED)cap=Math.min(cap,android.view.View.MeasureSpec.getSize(height));
            super.onMeasure(width,android.view.View.MeasureSpec.makeMeasureSpec(cap,android.view.View.MeasureSpec.AT_MOST));
        }
    }

    static final class Symbol extends Drawable {
        final String name;final int color,size;final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        Symbol(String name,int color,int size){this.name=name;this.color=color;this.size=size;setBounds(0,0,size,size);}
        void line(Canvas c,float... points){Path path=new Path();path.moveTo(points[0],points[1]);for(int i=2;i<points.length;i+=2)path.lineTo(points[i],points[i+1]);c.drawPath(path,p);}
        void box(Canvas c,float l,float t,float r,float b,float radius){c.drawRoundRect(l,t,r,b,radius,radius,p);}
        @Override public void draw(Canvas c){
            c.save();c.translate(getBounds().left,getBounds().top);c.scale(getBounds().width()/24f,getBounds().height()/24f);
            p.setColor(color);p.setStrokeWidth(1.65f);p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
            switch(name){
                case "back":line(c,14,5,7,12,14,19);break;
                case "down":line(c,7,10,12,15,17,10);break;
                case "right":line(c,9,6,15,12,9,18);break;
                case "arrow":line(c,12,19,12,5);line(c,6,11,12,5,18,11);break;
                case "latest":line(c,12,4,12,19);line(c,6,13,12,19,18,13);break;
                case "close":line(c,6,6,18,18);line(c,18,6,6,18);break;
                case "plus":line(c,12,4,12,20);line(c,4,12,20,12);break;
                case "search":c.drawCircle(10.5f,10.5f,6.5f,p);line(c,15.5f,15.5f,21,21);break;
                case "folder":line(c,3,19,3,6,9,6,11,9,21,9,21,19,3,19);break;
                case "file":line(c,5,2,14,2,19,7,19,22,5,22,5,2);line(c,14,2,14,7,19,7);line(c,8,12,16,12);line(c,8,16,16,16);break;
                case "monitor":box(c,3,4,21,17,2);line(c,12,17,12,21);line(c,8,21,16,21);break;
                case "phone":box(c,6,2,18,22,3);line(c,10,18,14,18);break;
                case "check":line(c,5,12,10,17,19,7);break;
                case "refresh":c.drawArc(4,4,20,20,40,295,false,p);line(c,20,3,20,9,14,9);break;
                case "chat":box(c,3,4,21,18,4);line(c,7,18,6,22,12,18);line(c,8,9,16,9);line(c,8,13,13,13);break;
                case "code":line(c,8,7,3,12,8,17);line(c,16,7,21,12,16,17);line(c,14,4,10,20);break;
                case "settings":c.drawCircle(12,12,4,p);for(int i=0;i<8;i++){c.save();c.rotate(i*45,12,12);line(c,12,2,12,5);c.restore();}c.drawCircle(12,12,8,p);break;
                case "scan":line(c,3,8,3,3,8,3);line(c,16,3,21,3,21,8);line(c,21,16,21,21,16,21);line(c,8,21,3,21,3,16);box(c,8,8,16,16,1);break;
                case "copy":box(c,8,8,20,21,2);line(c,15,5,15,3,3,3,3,16,5,16);break;
                case "shield":line(c,12,2,20,5,19,15,12,22,5,15,4,5,12,2);line(c,8,12,11,15,16,9);break;
                case "stop":p.setStyle(Paint.Style.FILL);box(c,6,6,18,18,2);break;
                case "spark":for(int i=0;i<8;i++){c.save();c.rotate(i*45,12,12);line(c,12,3,12,8);c.restore();}break;
                case "info":c.drawCircle(12,12,9,p);line(c,12,11,12,17);c.drawPoint(12,7,p);break;
                default:line(c,6,12,18,12);line(c,12,6,12,18);
            }c.restore();
        }
        @Override public int getIntrinsicWidth(){return size;}
        @Override public int getIntrinsicHeight(){return size;}
        @Override public void setAlpha(int alpha){}
        @Override public void setColorFilter(ColorFilter filter){}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}
