package io.github.abyxs.codingremote;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/**
 * The overlay's desktop pet, drawn as vector geometry on a 64x64 grid rather than
 * image assets: the APK is repacked with apktool, where new drawable resources would
 * mean new resource ids, and one sprite has to stay crisp from a 38dp settings
 * swatch up to a 64dp cut-out bubble.
 *
 * Each form owns a full, bright kawaii palette (body, shade, belly, cheek, detail,
 * eye) instead of borrowing the theme's two colours, so the pet reads as a cute
 * sticker rather than a monochrome glyph. A small status badge is drawn on the sprite
 * itself, so state remains visible without turning the floating window into a text card.
 */
final class PetSprite extends Drawable {
    /**
     * The forms, in the order the settings grid shows them.
     *
     * "robot" is the only survivor of the original set. Maltese and golden are the
     * two dogs of the Korean line-art pair - a white Maltese and a golden retriever -
     * drawn outline-first from the breed standards rather than from a recoloured
     * blob:
     *
     *   the Maltese has a skull that is round from the side, wide-set round eyes, a
     *   small round black nose, low-set triangular ears, and a white coat parted
     *   down a centre line with a slight wave along the edges. Those five things are
     *   what separate it from any other small white dog.
     *   the golden is a wider, lower animal with a broad muzzle, high-set floppy
     *   ears, feathering along the tail, and a cream chest and blaze.
     *
     * The three remaining forms are invented mascots built on anime shorthand - the
     * big head, tiny body and oversized eyes of a sticker - each with its own
     * construction rather than a recoloured copy of the same blob:
     *
     *   maltese  the traced reference, drawn from an embedded palette bitmap
     *   robot    flat panels, the one form with no animal reference
     */
    static final String[] FORMS={"maltese","robot"};
    static final String[] LABELS={"线条小狗","机器人"};
    static int index(String form){for(int i=0;i<FORMS.length;i++)if(FORMS[i].equals(form))return i;return 0;}
    static String label(String form){return form.equals("none")?"简洁":LABELS[index(form)];}
    /**
     * Maps any stored preference onto a form that still exists.
     *
     * The roster has changed more than once, so a device can hold a name that was
     * removed - "linepup" from before the reference art, or one of the hand-drawn
     * animals. Those are not harmless: index() would silently fall back to the first
     * entry, which happens to be the right picture here but leaves the settings page
     * showing a label for a pet that is not the one on screen, and leaves the dead
     * name in preferences forever.
     */
    static String migrate(String stored){
        if(stored==null)return FORMS[0];
        for(String known:FORMS)if(known.equals(stored))return stored;
        if(stored.equals("none"))return "none";
        return FORMS[0];
    }
    /** The roster plus the plain-window option, in picker order. */
    static String[] picker(){
        String[] all=new String[FORMS.length+1];
        System.arraycopy(FORMS,0,all,0,FORMS.length);
        all[FORMS.length]="none";
        return all;
    }

    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path=new Path();
    private String form,state="idle";
    private int ink,paper,size;
    // Per-form palette, assigned in form().
    private int body,shade,belly,cheek,detail,inner,eye;
    private float phase,blink,hop,notice,dim=1f;
    // The two ambient reactions. Both are driven by the overlay's own clock rather
    // than by an event: greet fires once when the form changes or the overlay opens,
    // idleLove drifts up on its own every few seconds while the pet is idle. They are
    // what stop the sprite from looking like a still image when nothing is happening.
    private float greet,idleLove;
    // One-shot event cue. The overlay sets this when a meaningful transition
    // arrives; the cue fades with the animation clock instead of competing with
    // the long-lived task state.
    private String event="";
    private float eventPulse;
    // Touch reactions, set by the overlay and decayed here. Each is a separate
    // amount rather than one "reacting" flag so a stroke and a poke can overlap
    // without either canceling the other.
    //   stroke   being petted right now: the pet shuts its eyes and presses up
    //   poke     a short burst, one per tap, that drives the star pop
    //   taps     consecutive taps inside the combo window, for the spin
    //   startle  a long press has gone on too long: the pet snaps awake
    private float stroke,startle,poke;
    private int taps;

    PetSprite(String form,int ink,int paper,int size){this.ink=ink;this.paper=paper;this.size=size;form(form);setBounds(0,0,size,size);}
    String form(){return form;}
    void event(String value){event=value==null?"":value;eventPulse=event.isEmpty()?0f:1f;invalidateSelf();}
    void form(String value){
        form=FORMS[index(value)];
        switch(form){
            // The pair draws from its own embedded palette, so these are only used by
            // the fallback silhouette if that bitmap ever fails to decode. They match
            // the reference: a flat white coat, a dark contour, a faint interior shade.
            case "maltese": body=0xffffffff;shade=0xff3a3a3a;belly=0xfff2f2f2;cheek=0xffffd0d8;detail=0xff3a3a3a;inner=0xffffe9c8;eye=0xff3a3a3a;break;
            default:        body=0xffa9d8e8;shade=0xff5fa6bf;belly=0xffe4f2f7;cheek=0xffff9a9a;detail=0xffffd166;inner=0xff2f4858;eye=0xff8ff6ff;break; // robot
        }
        invalidateSelf();
    }
    String state(){return state;}
    void state(String value){this.state=value;}
    /** One frame: looping phase, eyelid closure, a decaying hop and a new-message notice, each 0..1. */
    void advance(float phase,float blink,float hop){advance(phase,blink,hop,0f,greet,idleLove);}
    void advance(float phase,float blink,float hop,float notice){advance(phase,blink,hop,notice,greet,idleLove);}
    void advance(float phase,float blink,float hop,float notice,float greet,float idleLove){
        this.phase=phase;this.blink=blink;this.hop=hop;this.notice=notice;
        this.greet=Math.max(0f,Math.min(1f,greet));this.idleLove=Math.max(0f,Math.min(1f,idleLove));
        invalidateSelf();
    }

    /**
     * The same tick, plus the touch reactions.
     *
     * Kept as a separate overload rather than folded into the six-argument form so
     * the callers that only animate - the settings swatch, the picker previews -
     * keep working unchanged, and so a stress harness can drive a stroke without
     * having to know about phase and blink.
     */
    void advance(float phase,float blink,float hop,float notice,float greet,float idleLove,
                 float stroke,float startle,boolean poked,int taps){
        advance(phase,blink,hop,notice,greet,idleLove);
        this.stroke=Math.max(0f,Math.min(1f,stroke));
        this.startle=Math.max(0f,Math.min(1f,startle));
        // A tap is a pulse, not a level. The overlay says only that one happened
        // recently; the sprite drives the strength up while that holds and lets it
        // fall away afterwards, so the burst has a shape without the caller timing it.
        // The fall is deliberately slower than the rise: at the frame rate the overlay
        // actually runs, the first version decayed a whole tap in four frames, which is
        // a fifth of a second - fast enough that the burst was over before the eye
        // caught it. Roughly 0.6s of visible decay reads as a reaction.
        this.poke=poked?Math.min(1f,this.poke+.34f):Math.max(0f,this.poke-.032f);
        this.taps=Math.max(0,taps);
        this.eventPulse=Math.max(0f,this.eventPulse-.024f);
        invalidateSelf();
    }

    /** A tap landed. Consecutive taps inside the combo window escalate the reaction. */
    void tap(){poke=Math.min(1f,poke+.34f);taps=Math.min(5,taps+1);invalidateSelf();}
    /** The finger is down and holding; longer than a moment and the pet startles. */
    void hold(float amount){startle=Math.max(0f,Math.min(1f,amount));invalidateSelf();}
    /** The finger came to rest on the pet: a stroke, not a poke. */
    void stroke(float amount){stroke=Math.max(0f,Math.min(1f,amount));invalidateSelf();}
    /** Clears the touch layer, used when the finger lifts. */
    void release(){stroke=0f;startle=0f;invalidateSelf();}
    int combo(){return taps;}

    private void use(int color,float alpha,float stroke){
        paint.setColor(color);paint.setAlpha(Math.round(255*Math.max(0f,Math.min(1f,alpha))*dim));
        paint.setStyle(stroke>0?Paint.Style.STROKE:Paint.Style.FILL);
        paint.setStrokeWidth(stroke);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
    }
    private boolean active(){return !(state.equals("idle")||state.equals("sleep")||state.equals("offline"));}

    @Override public void draw(Canvas canvas){
        boolean asleep=state.equals("sleep"),offline=state.equals("offline");
        canvas.save();
        // Everything below is drawn in a fixed 64x64 space, so the canvas has to be
        // mapped onto the bounds first. Without this the sprite only came out right
        // when the host happened to be exactly 64dp: the settings swatch builds one at
        // 52dp, and at that size the art was drawn at its native scale and clipped by
        // clipRect rather than fitted into the tile.
        Rect bounds=getBounds();
        float fit=Math.min(bounds.width(),bounds.height())/64f;
        if(fit<=0f)fit=1f;
        canvas.translate(bounds.left+(bounds.width()-64f*fit)/2f,
                         bounds.top+(bounds.height()-64f*fit)/2f);
        canvas.scale(fit,fit);
        canvas.clipRect(0,0,64,64);
        dim=offline?.45f:1f;
        // Keep the silhouette stationary; eyes and surrounding cues carry motion.
        boolean shut=asleep||offline||stroke>.35f;
        if(form.equals("maltese"))pair(canvas,shut);
        else robot(canvas,shut);
        // The cue belongs to the fitted 64x64 space like the body does, so it is drawn
        // before the transform is popped. It used to sit after a restore() that already
        // unwound this save, with a second restore() below to unwind it again - and the
        // canvas has no save to match the second one. That underflow threw
        // "more restores than saves" from draw(), on the main thread, on the first frame
        // the pet painted: the overlay crash.
        cue(canvas);
        canvas.restore();
    }

    // --- Shared cute parts -------------------------------------------------
    /** Big round eyes with a highlight; a happy upward curve when closed or mid-blink. */
    private void eyes(Canvas c,boolean closed,float cx,float cy,float gap,float r){
        float lid=closed?1f:blink;
        if(lid>.55f){
            use(eye,1f,2.1f);
            path.reset();path.addArc(cx-gap-3.4f,cy-1,cx-gap+3.4f,cy+5,200,140);c.drawPath(path,paint);
            path.reset();path.addArc(cx+gap-3.4f,cy-1,cx+gap+3.4f,cy+5,200,140);c.drawPath(path,paint);
        }else{
            float rr=r*(1f-.55f*lid),rw=r*.74f;
            use(eye,1f,0);
            c.drawOval(cx-gap-rw,cy-rr,cx-gap+rw,cy+rr,paint);
            c.drawOval(cx+gap-rw,cy-rr,cx+gap+rw,cy+rr,paint);
            use(0xffffffff,.95f,0);
            c.drawCircle(cx-gap+1.5f,cy-rr*.45f,1.5f,paint);
            c.drawCircle(cx+gap+1.5f,cy-rr*.45f,1.5f,paint);
        }
    }

    // --- Dogs --------------------------------------------------------------
    /**
     * The pair from the reference art, drawn from an embedded palette bitmap.
     *
     * Both dogs are one composition - the Maltese stands in front of the golden,
     * touching - so they are one form and not two. Every attempt to redraw them as
     * vector outlines failed for a reason worth recording: because the two are drawn
     * against each other, the shared black contour cuts the white coat into several
     * disconnected regions, and a boundary walk can only follow one of them. The
     * outline it returned swept across the whole frame instead of around a dog.
     *
     * The reference is already flat-shaded at close to the sprite's own size, so the
     * faithful representation is the pixels. Not as 64*64 RGB values - six flat
     * colours cover the drawing exactly, plus one index for transparency, so it
     * stores as a single byte per pixel. Eight bits of index compress to well under
     * a kilobyte, which is smaller than the vector version was, and it needs nothing
     * from the platform beyond Inflater and Bitmap.
     *
     * Motion is applied around the bitmap rather than inside it: breathing, the hop,
     * the head tilt and every reaction are drawn by the caller, so the embedded data
     * stays a still and the animation stays live.
     */
    private static final int[] PAIR_PALETTE={
        0xffffffff,0xffe9cc9c,0xfff5e2b9,0xffccb296,0xffffcbc6,0xff2b2b2b};
    private static final String PAIR_DATA=
        "eNrtVkGSAyEIjEOH/z95BQVFcZLd3LbCIZVkpqGBRnk8vva1f2Zc7SNws+Tvd9AgtQvz68xUCK8cVPBFVzf1YAguavcO" +
        "mB0NqIerxtQnaHi6w1fmHttdkYas7DuBO+4OdwZGoD7EiwQmOBTGZPhWe3GAN+AkVazv+y+27Mu5gz1aC68QMvowtJTv" +
        "5ICnzAVzGflroLuHF8lXczI1XFktc+AA7Zvk3+EouyU1NIBghT317zv4mTGYi0fOfosOKs9nRsDDB/0EOFUBDhHyMfw1" +
        "KTjkzI1AVgErfmhCZA+ti/214mlpW5I8ZhlGvIXFiM8zXKhLT+nQQZ5U5/KlqXSWfo431iIdiHHGnc54ePqCruFpEQxA" +
        "qwT35lMf2NE3JaGCuZVwiz9UC7Z3qBxtUpDm78fN5fzBZ3gkwK1mFHXLd/jC29WCKFzkw3sc4qE9qYSeepxnnl4EvYpS" +
        "hYYFcVpAY5XhTYIiAxJBRBFB4+oxjmyGRxOotRS7ChUvJSbebk4sBwHW80cctEFeJdAtniC7AioQVlgKDkQC8/EhC8Be" +
        "QB1lmwbiVQI+/m0KQwdrzutgrhqwO5t8VeF4dY2ffdCT5aWWcXoQAxLap7yQb0Pb5hQoUx8V/t06hrmA/MeNrp9sn22F" +
        "39X6a//LfgBQLxNF";

    /** Decoded once and shared: the pair is the same pixels for every instance. */
    private static Bitmap pairCache;
    private final Paint bitmapPaint=new Paint(Paint.FILTER_BITMAP_FLAG);
    private final float[] mesh=new float[9*9*2];

    private Bitmap pair(){
        if(pairCache!=null)return pairCache;
        try{
            byte[] packed=android.util.Base64.decode(PAIR_DATA,android.util.Base64.DEFAULT);
            java.util.zip.Inflater inflater=new java.util.zip.Inflater();
            inflater.setInput(packed);
            byte[] indices=new byte[64*64];
            int written=inflater.inflate(indices);
            inflater.end();
            if(written<indices.length)return null;

            // One row at a time: an int buffer for the whole sprite would be a second
            // allocation of the same size for no reason.
            int[] palette=new int[PAIR_PALETTE.length+1];
            for(int i=0;i<PAIR_PALETTE.length;i++)palette[i+1]=PAIR_PALETTE[i];
            Bitmap bitmap=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888);
            int[] row=new int[64];
            for(int y=0;y<64;y++){
                for(int x=0;x<64;x++){
                    row[x]=palette[indices[y*64+x]&0xff];
                }
                bitmap.setPixels(row,0,64,0,y,64,1);
            }
            pairCache=bitmap;
            return bitmap;
        }catch(Exception error){return null;}
    }

    private void pair(Canvas c,boolean closed){
        Bitmap bitmap=pair();
        if(bitmap==null){
            // A decode failure must not leave an invisible pet. There is no vector
            // Maltese to fall back to any more, so this draws the pair's own palette
            // as a plain rounded blob - wrong, but visible and obviously a pet.
            use(body,1f,0);c.drawRoundRect(12,10,52,60,16,16,paint);
            use(shade,1f,2.4f);c.drawRoundRect(12,10,52,60,16,16,paint);
            use(cheek,.8f,0);c.drawCircle(22,36,3.4f,paint);c.drawCircle(42,36,3.4f,paint);
            eyes(c,closed,32,30,9f,2.8f);
            return;
        }
        // State-specific frames deform only ears and paws. The torso and window
        // anchor stay fixed, like the reference pack's constant content box.
        float frame=(float)Math.floor((phase-(float)Math.floor(phase))*8)/8;
        float wave=(float)Math.sin(frame*Math.PI*2);
        boolean typing=state.equals("working")||state.equals("thinking");
        boolean happy=state.equals("completed")||poke>.1f||greet>.1f;
        for(int y=0;y<=8;y++)for(int x=0;x<=8;x++){
            float px=x*8,py=y*8,dx=0,dy=0;
            if(!closed&&!state.equals("offline")){
                float ear=Math.max(0,1-((px-44)*(px-44)+(py-16)*(py-16))/80f);
                dx+=ear*wave*(happy?2.3f:.8f);
                float paw=Math.max(0,1-((px-48)*(px-48)+(py-44)*(py-44))/80f);
                dy+=paw*wave*(typing?1.8f:happy?2.6f:.25f);
                float goldEar=Math.max(0,1-((px-12)*(px-12)+(py-32)*(py-32))/80f);
                dx+=goldEar*wave*.8f;
            }
            int i=(y*9+x)*2;mesh[i]=px+dx;mesh[i+1]=py+dy;
        }
        bitmapPaint.setAlpha(Math.round(255*dim));c.drawBitmapMesh(bitmap,8,8,mesh,0,null,0,bitmapPaint);
        if(closed||blink>.55f){
            use(0xffffffff,1,0);c.drawOval(34,29,38,32,paint);c.drawOval(43,24,47,27,paint);
            use(0xff2b2b2b,1,1);c.drawLine(35,30.5f,37,30,paint);c.drawLine(44,25.5f,46,25,paint);
        }
    }

    /** The robot: the one form with no animal reference, so it is all flat panels. */
    private void robot(Canvas c,boolean closed){
        use(body,1f,0);c.drawRoundRect(17,32,47,62,9,9,paint);
        use(shade,1f,2.6f);c.drawRoundRect(17,32,47,62,9,9,paint);
        use(belly,.85f,0);c.drawRoundRect(24,44,40,56,5,5,paint);
        use(shade,1f,2.2f);c.drawLine(15,36,49,36,paint);
        // Antenna, which bobs out of phase so it reads as springy. Its tip is kept
        // clear of the top edge: the hop lifts the whole sprite by up to 11 units and
        // a stroke adds more, so an antenna reaching y=1.8 was pushed off the tile and
        // the sheet measured it as escaping the frame.
        float tip=3.4f*(float)Math.sin(phase*2*Math.PI*2+0.4f);
        use(shade,1f,2.2f);path.reset();path.moveTo(32,16);path.quadTo(32+tip,11,32+tip*1.6f,8.6f);c.drawPath(path,paint);
        use(detail,1f,0);c.drawCircle(32+tip*1.6f,8f,2.6f,paint);
        use(body,1f,0);c.drawRoundRect(14,13,50,45,10,10,paint);
        use(shade,1f,2.6f);c.drawRoundRect(14,13,50,45,10,10,paint);
        if(closed){use(eye,1f,2.2f);c.drawLine(21,29,27,29,paint);c.drawLine(37,29,43,29,paint);}
        else{
            use(eye,1f,0);
            float lid=blink,rr=5.0f*(1f-.5f*lid),rw=3.6f;
            c.drawRoundRect(21,29-rr,21+rw*2,29+rr,3,3,paint);
            c.drawRoundRect(37,29-rr,37+rw*2,29+rr,3,3,paint);
        }
        use(detail,1f,1.6f);c.drawLine(24,39,40,39,paint);
        float typing=state.equals("working")?1.6f*(float)Math.sin(phase*Math.PI*4):0;
        use(body,1,0);c.drawCircle(18,48+typing,3,paint);c.drawCircle(46,48-typing,3,paint);
        use(shade,1,1.4f);c.drawCircle(18,48+typing,3,paint);c.drawCircle(46,48-typing,3,paint);
    }

    // --- Reactions (actions, never a status label) -------------------------
    private void heart(Canvas c,float cx,float cy,float r,int color,float alpha){
        use(color,alpha,0);path.reset();
        path.moveTo(cx,cy+r*.95f);
        path.cubicTo(cx-r*1.35f,cy-r*.2f,cx-r*.55f,cy-r*1.15f,cx,cy-r*.35f);
        path.cubicTo(cx+r*.55f,cy-r*1.15f,cx+r*1.35f,cy-r*.2f,cx,cy+r*.95f);
        path.close();c.drawPath(path,paint);
    }
    private void star(Canvas c,float cx,float cy,float r,int color,float alpha){
        use(color,alpha,0);path.reset();
        for(int i=0;i<10;i++){
            float rr=(i%2==0)?r:r*.42f;
            double a=-Math.PI/2+i*Math.PI/5;
            float x=cx+rr*(float)Math.cos(a),y=cy+rr*(float)Math.sin(a);
            if(i==0)path.moveTo(x,y);else path.lineTo(x,y);
        }
        path.close();c.drawPath(path,paint);
    }
    private void note(Canvas c,float cx,float cy,float r,int color,float alpha){
        use(color,alpha,0);
        c.drawCircle(cx-r*.5f,cy+r*.55f,r*.42f,paint);
        c.drawRect(cx-r*.14f,cy-r*.9f,cx+r*.1f,cy+r*.6f,paint);
        path.reset();path.moveTo(cx+r*.1f,cy-r*.9f);path.quadTo(cx+r*1.5f,cy-r*1.1f,cx+r*1.35f,cy-r*.2f);
        path.quadTo(cx+r*.95f,cy-r*.75f,cx+r*.1f,cy-r*.55f);path.close();c.drawPath(path,paint);
    }

    /**
     * The reaction layer: one gesture per mood, drawn above the pet.
     *
     * Keyed to `state`, so the overlay shows what is happening with a movement rather
     * than a word - which is the point of having a pet instead of a progress bar.
     * Each gesture is additive and decays with the hop, so it reads as something the
     * animal did rather than a sticker pasted over it.
     */
    private void cue(Canvas c){
        // Working: four marks orbiting the head, like a small engine turning over.
        if(state.equals("working")){
            for(int i=0;i<4;i++){
                double a=phase*Math.PI*2+i*Math.PI/2;
                use(detail,.75f,2f);
                c.drawCircle(32+(float)Math.cos(a)*20f,22+(float)Math.sin(a)*8f,1.9f,paint);
            }
        }
        // Thinking: three stars brightening in sequence, which reads as a held pause.
        else if(state.equals("thinking")){
            for(int i=0;i<3;i++){
                float t=(phase*3f+i*.33f)%1f;
                float a=t<.5f?t*2f:(1f-t)*2f;
                star(c,18+i*11f,10-i*2.4f,3.2f+.9f*sin01(t),detail,a*.9f);
            }
        }
        // Sleeping: a z rising and fading.
        else if(state.equals("sleep")){
            float t=(phase*1.4f)%1f;
            use(detail,(1f-t)*.85f,2.2f);
            float x=46+t*7f,y=20-t*13f,s=3f+t*2.6f;
            path.reset();path.moveTo(x,y-s);path.lineTo(x+s,y-s);path.lineTo(x,y+s);path.lineTo(x+s,y+s);
            c.drawPath(path,paint);
        }
        // A new message: the heart, popping in over the shoulder. More specific
        // events below replace this with a stronger visual cue.
        if(notice>0&&eventPulse<=0){
            float pop=(float)Math.sin(Math.min(1f,notice)*Math.PI),s=.55f+.45f*pop;
            c.save();c.translate(50,12);c.scale(s,s);c.rotate(-10f*(1f-pop));
            use(0xffffffff,.96f,0);c.drawCircle(0,0,11f,paint);
            use(detail,.9f,2f);c.drawCircle(0,0,11f,paint);
            heart(c,0,1.5f,6.4f,0xffff5d7e,1f);
            c.restore();
        }
        // Arriving on a fresh session: a pair of notes, so the pet greets you.
        if(greet>0){
            for(int i=0;i<2;i++){
                float t=Math.min(1f,greet*1.4f-i*.3f);
                if(t<=0)continue;
                note(c,18+i*30f,8f+t*4f,3.6f*(1f-t*.35f),detail,(1f-t)*.9f);
            }
        }
        // Idle affection: every so often a small heart drifts up on its own.
        if(idleLove>0){
            heart(c,14+idleLove*4f,14-idleLove*9f,3.4f+idleLove*1.4f,0xffff7d95,(1f-idleLove)*.75f);
        }
        // Being stroked: hearts rise steadily and spread outward as long as the hand
        // stays down, so the longer you pet it the more it gives back. Driven by the
        // breath phase rather than by a timer, which keeps them in step with the body.
        if(stroke>0){
            for(int i=0;i<3;i++){
                float t=(phase*1.6f+i*.33f)%1f;
                float a=(1f-t)*stroke;
                heart(c,44+i*5f-t*8f,26-t*20f,2.6f+t*1.6f,0xffff7d95,a*.85f);
            }
        }
        // A quick series of taps: a burst of stars that grows with the combo. This is
        // the one reaction with an escalating reward, so tapping repeatedly is worth
        // doing rather than being the same as tapping once.
        if(poke>0){
            int burst=Math.min(3,taps)+2;
            for(int i=0;i<burst;i++){
                double a=-Math.PI/2+(i-(burst-1)/2f)*.62f;
                float reach=(5f+taps*1.6f)*poke;
                star(c,32+(float)Math.cos(a)*reach,30+(float)Math.sin(a)*reach,
                     2.4f+taps*.5f,poke>.5f?0xffffc94d:detail,poke*.95f);
            }
        }
        // Held too long: an exclamation, so the long press reads as "that is enough"
        // rather than as nothing happening.
        if(startle>0){
            float s=1f+.35f*startle;
            use(0xffe2564a,startle,0);
            c.drawRoundRect(30f,4f+4f*(1f-startle),34f,16f,1.8f,1.8f,paint);
            c.drawCircle(32f,19.5f,2.1f*s,paint);
        }
        eventCue(c);
        // The hop itself: sparkles that spread and shrink as it decays.
        if(hop>0)for(int i=0;i<3;i++){
            float ax=32+(i-1)*13f,ay=16-10f*hop;
            use(detail,.85f*hop,2f);c.drawLine(ax-3f,ay,ax+3f,ay,paint);c.drawLine(ax,ay-3f,ax,ay+3f,paint);
        }
    }

    /** Draws a short, icon-like cue for important transitions without text. */
    private void eventCue(Canvas c){
        if(eventPulse<=0||event.isEmpty())return;
        float a=Math.min(1f,eventPulse);
        if(event.equals(PetStateMachine.ERROR)){
            use(0xffe2564a,a,2.5f);c.drawRoundRect(30,4,34,16,2,2,paint);c.drawCircle(32,20,2.1f,paint);
            return;
        }
        if(event.equals(PetStateMachine.COMPLETED)){
            for(int i=0;i<4;i++){
                double angle=-Math.PI/2+i*Math.PI/2;
                float radius=13f+4f*(1f-a);
                star(c,32+(float)Math.cos(angle)*radius,18+(float)Math.sin(angle)*radius,3.1f,0xff2e9b68,a*.9f);
            }
            return;
        }
        if(event.equals(PetStateMachine.APPROVAL)||event.equals(PetStateMachine.QUESTION)){
            int color=event.equals(PetStateMachine.APPROVAL)?0xffffa62b:0xff4d8fe8;
            use(0xffffffff,a*.95f,0);c.drawCircle(49,10,10,paint);
            use(color,a,2f);c.drawCircle(49,10,10,paint);
            use(color,a,2.2f);c.drawCircle(45,10,1.2f,paint);c.drawCircle(53,10,1.2f,paint);c.drawLine(45,14,53,14,paint);
            return;
        }
        if(event.equals("offline")){
            use(0xff7b8794,a,2.5f);c.drawLine(22,12,42,28,paint);c.drawCircle(30,20,9,paint);
            return;
        }
        heart(c,50,12,6.4f,0xffff5d7e,a);
    }
    private float sin01(float t){return (float)Math.sin(Math.PI*t);}


    @Override public int getIntrinsicWidth(){return size;}
    @Override public int getIntrinsicHeight(){return size;}
    @Override public void setAlpha(int alpha){}
    @Override public void setColorFilter(ColorFilter filter){}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
