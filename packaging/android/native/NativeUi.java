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
    static int INK=0xff242b27, MUTED=0xff5b625d, GREEN=0xff27634d;
    static int CANVAS=0xfff6f7f2, WHITE=0xffffffff, LINE=0xffe5e8e1;
    static int EDGE=0xffccd2c8;
    static int TINT=0xffeaf1e9, SOFT=0xfff2f4ef, RED=0xffa4453e;
    /** Secondary accent: every theme is a scheme, not a single colour. */
    static int ACCENT=0xff305d77;
    static int DIFF_ADD=0xffdceee0,DIFF_REMOVE=0xfff8e3df;
    /** Foregrounds for the diff rows, solved against the washes above rather than
     * reusing the primary and the danger colour, which only cleared 4.0-4.5:1. */
    static int DIFF_ADD_TEXT=0xff357149,DIFF_REMOVE_TEXT=0xffa4453e;
    static int CODE=0xff305d77,KEYWORD=0xff8054a2,STRING=0xff357149,EMPHASIS=0xff976c28;
    /** Type scale.
     *
     * The app had drifted to fourteen distinct sizes between 9sp and 34sp, with
     * 12/13/14 each used for the same "secondary list row" role - differences of
     * one sp that read as sloppiness rather than hierarchy. Six steps, one meaning
     * each; anything that needs a size picks the nearest role instead of a number.
     */
    static final int CAPTION=11;   // metadata, counters, tab labels
    static final int LABEL=13;     // secondary row text, section headers
    static final int BODY=15;      // message text, primary list rows
    static final int TITLE=17;     // card and group headings
    static final int HEADING=24;   // page titles
    static final int DISPLAY=34;   // the welcome screen only
    /** The only touch target size; 44dp appears throughout the old layout and is
     * below the 48dp minimum. */
    static final int TOUCH=48;
    /** Row heights.
     *
     * Same story as the corner scale: a row that holds one line of text was being
     * given 48, 50, 52, 54, 56 or 60 depending on which file it lived in, and the
     * numbers had no meaning beyond whoever wrote that file. Three roles cover
     * every case in the app - a plain control, a row with two lines, and a page
     * header bar - and each still clears the 48dp touch minimum.
     */
    static final int ROW=48;        // one-line rows, buttons, list items
    static final int ROW_TALL=56;   // two-line rows (title over a subtitle)
    static final int BAR=68;        // top app bar and the welcome header
    /** Glyph sizes.
     *
     * Eleven values were in use - 12, 16, 18, 19, 20, 22, 23, 24, 38, 40, 54 - for
     * four jobs. Anything sitting inline with text of size LABEL/BODY reads best at
     * one size, a chevron next to it should match, and an icon inside a button is
     * drawn at the button's own weight. The decorative sizes are left alone: the
     * welcome artwork is a one-off illustration, not a role.
     */
    static final int GLYPH_INLINE=18;   // leading icons, chevrons, checkmarks
    static final int GLYPH_ACTION=22;   // icons centred inside a button
    static final int GLYPH_DISPLAY=40;  // empty-state and preview illustrations
    /** Elevation, in dp, as a stack rather than a set of numbers.
     *
     * Five values were in use and they were already ordered correctly by height -
     * the drift risk is that the next overlay picks a sixth. Naming the layers
     * makes the ordering explicit: something that floats above the transcript must
     * also sit above the header, and a modal above both.
     */
    static final int LIFT_FLAT=0;       // inline content, no shadow
    static final int LIFT_HEADER=2;     // page and conversation headers
    static final int LIFT_FLOATING=3;   // the floating navigation handle
    static final int LIFT_POPUP=5;      // menus and drop-downs
    static final int LIFT_DIALOG=10;    // sheets and dialogs
    /** Corner scale.
     *
     * Corner radii had drifted the same way the type scale had: the call sites used
     * 8, 9, 10, 12, 14, 16, 18, 20, 22 and 24, with list rows alone spanning 10 to
     * 18 and cards spanning 8 to 24. A radius that changes by two dp between two
     * rows of the same list reads as a mistake, not as hierarchy. Four steps, each
     * tied to the size of the thing being rounded.
     */
    static final int CORNER_CHIP=10;    // swatches, inline tags, small toggles
    static final int CORNER_ROW=14;     // list rows, buttons, fields
    static final int CORNER_CARD=18;    // grouped cards, panels
    static final int CORNER_SHEET=24;   // sheets and full-width surfaces
    /** Half of the 46dp floating-navigation button. A radius equal to half the side is
     *  what makes it a disc rather than a rounded square, which is why it is its own
     *  step: any of the four above would round the corners and stop there. */
    static final int CORNER_DISC=23;
    /** Spacing scale, for the same reason as the corner scale: gap() had drifted to
     * fourteen values. Only the named roles are pinned - genuinely local spacing
     * (a one-off inset inside one control) is still written as a number. */
    static final int SPACE_TIGHT=4;     // between a label and its value
    static final int SPACE_INLINE=8;    // between siblings inside a row
    static final int SPACE_STACK=12;    // between stacked rows in one card
    static final int SPACE_GROUP=22;    // between two cards
    static final int SPACE_PAGE=16;     // page gutter, left and right
    /**
     * A theme is a colour style, not a pile of overrides.
     *
     * Each entry describes one style by hue, mode and saturation. Surfaces, edges,
     * the code palette, the diff washes and the semantic colours are all derived
     * from those numbers, so a style cannot come out half-applied and "the colours
     * that are not the theme" stay in the same family as the ones that are.
     */
    private static final class Scheme {
        final int hue;        // anchor hue, degrees
        final boolean dark;   // mode, not a style choice
        final float chroma;   // how much of the hue reaches the surfaces, 0..1
        final float warmth;   // hue drift for the warm accents, degrees
        Scheme(int hue,boolean dark,float chroma,float warmth){
            this.hue=hue;this.dark=dark;this.chroma=chroma;this.warmth=warmth;
        }
    }
    static final String[] THEMES={"light","green","ocean","lavender","sand","dark","midnight"};
    /** Style names in the same order as THEMES.
     *
     * The list used to be one entry out of step past the first: 墨绿 named the neutral
     * dark style and 新叶 the light green one, so picking "墨绿" produced a grey-black
     * screen and picking "新叶" a pale one. A name is a promise about the colour. */
    static final String[] THEME_NAMES={"素白","新叶","远洋","紫藤","暖沙","墨绿","午夜"};
    private static Scheme scheme(String name){
        if(name.equals("dark"))     return new Scheme(150,true, .30f, 0f);
        if(name.equals("green"))    return new Scheme(150,false,.30f, 0f);
        if(name.equals("ocean"))    return new Scheme(205,false,.34f, 8f);
        if(name.equals("lavender")) return new Scheme(272,false,.28f,16f);
        if(name.equals("sand"))     return new Scheme( 34,false,.30f,-8f);
        if(name.equals("midnight")) return new Scheme(222,true, .36f,12f);
        return new Scheme(150,false,.22f, 0f);   // light: the neutral default
    }
    /**
     * The light-theme surface ladder: saturation and lightness per theme, ten values
     * each, in the order canvas S/L, card S/L, field S/L, tint S/L, line S/L.
     *
     * These are spelled out rather than derived from a chroma multiplier because that
     * derivation is what collapsed the surfaces together: one small multiplier applied
     * to a hue gives every theme the same near-white, and the schema had no way to say
     * "this theme's canvas is a definite mint and that one's is a definite sand".
     *
     * The canvases carry real chroma and sit lower on the lightness scale than they used
     * to. At .955-.975 with saturation under .4 every one of these rendered as the same
     * off-white, so switching themes changed the accent and nothing else - which is what
     * "the colours are still not obvious" was. The card stays the brightest surface in
     * every row so the ladder (canvas < card) still reads as depth.
     *
     * `light` is deliberately the least saturated entry so it stays the no-opinion
     * choice. The rest carry their hue on the canvas, which is the thing the reader
     * actually sees when they pick a theme.
     */
    private static final float[][] LIGHT_SURFACES={
        //                canvas          card            field           tint            line
        /* light    */ {.030f,.975f, .010f,.998f, .060f,.905f, .055f,.930f, .040f,.870f},
        /* green    */ {.420f,.930f, .100f,.993f, .430f,.868f, .470f,.902f, .380f,.812f},
        /* ocean    */ {.400f,.934f, .090f,.993f, .410f,.872f, .450f,.906f, .360f,.816f},
        /* lavender */ {.340f,.936f, .080f,.994f, .350f,.874f, .390f,.908f, .300f,.818f},
        /* sand     */ {.480f,.926f, .110f,.992f, .500f,.862f, .540f,.898f, .440f,.806f},
        /* dark     */ {.030f,.975f, .010f,.998f, .060f,.905f, .055f,.930f, .040f,.870f},
        /* midnight */ {.160f,.960f, .040f,.996f, .180f,.892f, .210f,.920f, .120f,.850f},
    };
    private static float ladder(String name,int slot){
        int row=java.util.Arrays.asList(THEMES).indexOf(name);
        if(row<0)row=0;
        return LIGHT_SURFACES[row][slot];
    }

    static void theme(String name){
        Scheme s=scheme(name);
        float c=s.chroma;
        int drift=Math.round(s.warmth);

        // Surfaces. A light style is a tinted canvas with a lighter card on it; a dark
        // one inverts that, with the canvas deeper than the card above it.
        //
        // These were previously all within 0.03 lightness of each other - canvas .982,
        // field .950, card .910 - with saturation multiplied down to .03-.04. Three
        // surfaces that close together cannot be told apart whatever hue they carry, so
        // an input field on the page measured 1.07:1 and read as transparent, and five
        // of the seven themes had visibly identical canvases. The ladder below is
        // separated by lightness first and tinted second, which is what makes the field
        // a control and each theme its own colour.
        CANVAS= s.dark?hsl(s.hue,c*.90f,.088f):hsl(s.hue,ladder(name,0),ladder(name,1));
        WHITE = s.dark?hsl(s.hue,c*.80f,.152f):hsl(s.hue,ladder(name,2),ladder(name,3));
        SOFT  = s.dark?hsl(s.hue,c*.95f,.205f):hsl(s.hue,ladder(name,4),ladder(name,5));
        // The card sits above the canvas on light themes and above it on dark ones too,
        // but by less: a card is a grouping, not a control.
        TINT  = s.dark?hsl(s.hue,c*1.10f,.245f):hsl(s.hue,ladder(name,6),ladder(name,7));
        LINE  = s.dark?hsl(s.hue,c*.70f,.260f):hsl(s.hue,ladder(name,8),ladder(name,9));

        // Everything below is placed by the contrast it needs, not by a lightness
        // picked by eye. Hand-chosen values were the reason EDGE measured 1.8-2.2:1
        // against its own field on every theme, and why a cold style kept a warm
        // emphasis amber: the constraint was never written down, so it was never
        // met. solve() walks away from the surface until the floor is reached.

        // A visible edge for input surfaces: 3:1 is what identifies a control.
        EDGE  = solve(s.hue,s.dark?c*.30f:c*.24f,SOFT,3.0f,!s.dark);

        // Text. These are read against the surfaces they actually land on, which is why
        // they are solved rather than picked. A dark label loses contrast on a dark
        // surface, so on light themes the label is solved against whichever of the card
        // and the field is darker; on dark themes the surfaces invert and the lighter
        // one is the harder case.
        int busiest=s.dark?(Color.luminance(SOFT)>=Color.luminance(TINT)?SOFT:TINT)
                          :(Color.luminance(SOFT)<=Color.luminance(TINT)?SOFT:TINT);
        INK  = solve(s.hue,c*.12f,busiest,9.0f,!s.dark);
        MUTED= solve(s.hue,c*.10f,busiest,4.5f,!s.dark);

        // The primary carries onPrimary() labels, so it is solved against its own
        // fill and the label colour is then derived from it.
        GREEN= s.dark?hsl(s.hue,c*.60f,.750f):hsl(s.hue,Math.min(1f,c*1.9f),.300f);

        // Secondary and semantic. ACCENT takes a turn away from the primary: far
        // enough to read as a second colour, close enough to stay in the family.
        ACCENT  = solve(s.hue+40,s.dark?c*.48f:c*.70f,WHITE,4.5f,!s.dark);
        RED     = solve( 8+drift,.46f,WHITE,4.5f,!s.dark);
        EMPHASIS= solve(38+drift,.60f,WHITE,4.5f,!s.dark);

        // Code palette: hue offsets from the anchor, so highlights belong to the
        // style instead of arriving as a fixed purple and green.
        CODE   = solve(s.hue+16,c*.68f,WHITE,4.5f,!s.dark);
        KEYWORD= solve(s.hue+104,c*.60f,WHITE,4.5f,!s.dark);
        STRING = solve(s.hue-72,c*.58f,WHITE,4.5f,!s.dark);

        // Diff washes first - they are the surfaces - then the two foregrounds
        // solved against them, so an added line is legible on every style.
        DIFF_ADD   = s.dark?hsl(s.hue-60,c*.44f,.190f):hsl(s.hue-66,c*.40f,.925f);
        DIFF_REMOVE= s.dark?hsl(10+drift,.32f,.195f):hsl(10+drift,.42f,.935f);
        DIFF_ADD_TEXT   = solve(s.hue-72,c*.58f,DIFF_ADD,4.5f,!s.dark);
        DIFF_REMOVE_TEXT= solve( 8+drift,.46f,DIFF_REMOVE,4.5f,!s.dark);
    }
    /** HSL with the degrees wrapped, so callers can just add offsets. */
    private static int hsl(int hue,float saturation,float lightness){
        float h=(((hue%360)+360)%360)/360f;
        float sat=Math.max(0f,Math.min(1f,saturation)),lum=Math.max(0f,Math.min(1f,lightness));
        float q=lum<.5f?lum*(1+sat):lum+sat-lum*sat;
        float p=2*lum-q;
        return 0xff000000
            |(Math.round(channel(p,q,h+1f/3f)*255f)<<16)
            |(Math.round(channel(p,q,h)*255f)<<8)
            | Math.round(channel(p,q,h-1f/3f)*255f);
    }
    private static float channel(float p,float q,float t){
        if(t<0f)t+=1f;
        if(t>1f)t-=1f;
        if(t<1f/6f)return p+(q-p)*6f*t;
        if(t<1f/2f)return q;
        if(t<2f/3f)return p+(q-p)*(2f/3f-t)*6f;
        return p;
    }
    /**
     * The closest colour to {@code surface} on the requested side that reaches
     * {@code floor} of contrast against it.
     *
     * Solving beats choosing: the requirement is stated once, holds for every
     * style, and survives a change to the saturation or the surface tone. The walk
     * starts at the surface's own lightness, so the result stays as close to the
     * surface as legibility allows instead of drifting to pure black or white.
     */
    private static int solve(int hue,float saturation,int surface,float floor,boolean darker){
        float base=0f,lo=0f,hi=1f;
        // Locate the surface's lightness on this hue/saturation axis by bisection.
        for(int i=0;i<48;i++){
            float mid=(lo+hi)/2f;
            if(Color.luminance(hsl(hue,saturation,mid))<Color.luminance(surface))lo=mid;else hi=mid;
        }
        base=(lo+hi)/2f;
        int best=surface;
        for(int i=0;i<=400;i++){
            float t=i/400f;
            float lightness=darker?base-base*t:base+(1f-base)*t;
            int value=hsl(hue,saturation,lightness);
            if(contrast(value,surface)>=floor)return value;
            best=value;
        }
        return best;
    }
    /** Canvas, primary and secondary colours of a theme, for the settings preview. */
    static int[] preview(String name){
        int[] saved={INK,MUTED,GREEN,CANVAS,WHITE,LINE,TINT,SOFT,RED,DIFF_ADD,DIFF_REMOVE,CODE,KEYWORD,STRING,EMPHASIS,EDGE,ACCENT,DIFF_ADD_TEXT,DIFF_REMOVE_TEXT};
        theme(name);
        int[] result={CANVAS,GREEN,ACCENT};
        INK=saved[0];MUTED=saved[1];GREEN=saved[2];CANVAS=saved[3];WHITE=saved[4];LINE=saved[5];TINT=saved[6];SOFT=saved[7];RED=saved[8];
        DIFF_ADD=saved[9];DIFF_REMOVE=saved[10];CODE=saved[11];KEYWORD=saved[12];STRING=saved[13];EMPHASIS=saved[14];EDGE=saved[15];ACCENT=saved[16];
        DIFF_ADD_TEXT=saved[17];DIFF_REMOVE_TEXT=saved[18];
        return result;
    }
    /** Label colour that stays readable on the accent fill of the current theme.
     *
     * The test has to look at GREEN, the colour actually painted behind the label:
     * judging by CANVAS made the light-background themes (which have a dark GREEN)
     * pick white by accident, while dark and midnight kept white on a bright GREEN
     * and dropped to roughly 1.8:1.
     */
    static int onPrimary(){return Color.luminance(GREEN)>.42?0xff16201a:0xffffffff;}
    /** Touch feedback derived from the theme accent; a fixed green read as foreign
     * against the ocean, lavender and midnight surfaces. */
    private static ColorStateList rippleColor(){return ColorStateList.valueOf(GREEN & 0x00ffffff | 0x16000000);}
    /** Idle scrollbar: a blend towards the ink colour so it clears the 3:1 non-text
     * threshold on both the light canvases and the dark ones, without competing
     * with the content the way a full-strength ink stroke would. */
    static int scrollbar(){
        int ink=INK & 0x00ffffff;
        return Color.luminance(CANVAS)<.2?blend(MUTED,ink,.22f):blend(MUTED,ink,.28f);
    }
    /** Hairline divider for a given surface. LINE alone is nearly invisible against
     * the canvas on every theme, so pull it toward the ink: light surfaces need a
     * slightly stronger stroke than dark ones to read as the same weight. */
    static int rule(int surface){
        int ink=INK & 0x00ffffff;
        return Color.luminance(surface)<.2?blend(LINE,ink,.30f):blend(LINE,ink,.50f);
    }
    /** Context-ring colours, solved against the accent fill the ring sits on.
     *
     * These were fixed black/white/amber values tuned on the light themes. The track was
     * a hardcoded alpha and the arc was simply onPrimary(), so on the light themes the
     * two came out as the same white (both 6.25:1 against the fill) and the ring read as
     * a solid dot; on dark and midnight the amber warning was picked for a light accent
     * and collapsed to 1.37:1. All four are now solved, not chosen. */
    static int ringTrack(){
        // Deliberately quiet - it is the unfilled part - but it must stay visible against
        // the fill rather than merely different from it by a constant alpha. Kept close to
        // the fill on purpose: the gap between track and arc is what makes the arc
        // readable, and at 1.35:1 the two were solved to nearly the same colour on the
        // light themes, so a 30% ring and a 70% ring looked identical.
        int target=onPrimary()==0xffffffff?0x000000:0xffffff;
        return towardExtreme(GREEN,blend(GREEN,target,.34f),1.9f);
    }
    /** The progress arc, solved against the accent fill rather than assumed white.
     *
     * It was simply onPrimary(), which on every light theme is white on a mid-green -
     * about 2.4:1, below the 3:1 an arc needs to be read as an arc. The floor is well
     * above the track's so the filled part is never in doubt. */
    static int ringProgress(){return towardExtreme(GREEN,onPrimary(),7.0f);}
    static int ringWarning(){return towardExtreme(GREEN,0xffffc75c,5.5f);}
    static int ringDanger(){return towardExtreme(GREEN,RED,5.5f);}
    /**
     * Pushes `color` away from `fill` until the pair reaches `ratio`.
     *
     * The direction is chosen by trying both, not by a luminance threshold on the fill.
     * A mid-green accent sits around 0.3 luminance, well under .5, so the previous
     * `> .5` test sent every arc toward white - including on the light themes where
     * white was exactly what it needed to escape. Whichever end actually reaches the
     * ratio is the one used.
     */
    private static int towardExtreme(int fill,int color,float ratio){
        int a=fill & 0x00ffffff,b=color & 0x00ffffff;
        if(contrast(a,b)>=ratio)return b;
        int best=b;float bestRatio=contrast(a,b);
        for(int end=0;end<2;end++){
            int away=end==0?0x000000:0xffffff;
            for(float t=0.1f;t<=1f;t+=0.05f){
                int candidate=blend(b,away,t);
                float reached=contrast(a,candidate);
                if(reached>=ratio)return candidate;
                if(reached>bestRatio){bestRatio=reached;best=candidate;}
            }
        }
        // Nothing reached the ratio - a plainly impossible pair, since black or white
        // always separates from anything. The best found is still the best available.
        return best;
    }
    /** Scrim behind a dialog. Deeper on a dark canvas, where black already dominates
     * and a light scrim would barely register. */
    static int scrim(){return Color.luminance(CANVAS)<.2?0xc4000000:0x8f000000;}
    private static int blend(int a,int b,float t){
        int ar=(a>>16)&255,ag=(a>>8)&255,ab=a&255,br=(b>>16)&255,bg=(b>>8)&255,bb=b&255;
        return 0xff000000|(Math.round(ar+(br-ar)*t)<<16)|(Math.round(ag+(bg-ag)*t)<<8)|Math.round(ab+(bb-ab)*t);
    }
    private final Context context;
    NativeUi(Context c){context=c;}
    /** Resolve a resource name without ever returning 0.
     *
     * getIdentifier() answers 0 for a name the installed package does not carry,
     * and setImageResource/setSmallIcon/setWindowAnimations all throw on 0. The
     * branded and *_official drawables are exactly the assets a rebrand or an
     * upstream change can remove, so every lookup goes through here.
     */
    int res(String name,String type,int fallback){
        int id=context.getResources().getIdentifier(name,type,context.getPackageName());
        return id==0?fallback:id;
    }
    int style(String name,int fallback){
        int id=context.getResources().getIdentifier(name,"style",context.getPackageName());
        return id==0?fallback:id;
    }
    int dp(float x){return Math.round(context.getResources().getDisplayMetrics().density*x);}
    static int dp(Context context,float x){return Math.round(context.getResources().getDisplayMetrics().density*x);}
    GradientDrawable surface(int color,int radius,boolean border){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));
        if(border)d.setStroke(dp(1),LINE);
        return d;
    }
    /** Input surface: a filled field with a visible edge, unlike a card.
     *
     * EDGE is calibrated against WHITE, but the field paints on SOFT, where it
     * measured 1.63-2.06:1 on every theme - below the 3:1 that identifies a control
     * boundary. Blend it toward the ink until it actually clears that, per theme.
     *
     * A 1dp hairline that exactly meets 3:1 is still the faintest thing that passes.
     * On a phone the field is the control the user touches most, so it gets a heavier
     * edge than the strict minimum: the stroke is drawn at 1.5dp and solved against
     * the field to 3.5:1 rather than 3:1, which is what makes the box read as a box
     * rather than as a tint that fades out at the corners. */
    GradientDrawable field(int color,int radius){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));
        d.setStroke(Math.max(dp(1),dp(1.5f)),edgeOn(color,3.5f));
        return d;
    }
    /** EDGE pulled toward the ink until it reaches `ratio` against `surface`. */
    static int edgeOn(int surface){return edgeOn(surface,3f);}
    static int edgeOn(int surface,float ratio){
        int target=surface & 0x00ffffff,base=EDGE & 0x00ffffff;
        if(contrast(base,target)>=ratio)return base;
        int ink=INK & 0x00ffffff;
        for(float t=0.08f;t<=0.9f;t+=0.04f){
            int candidate=blend(base,ink,t);
            if(contrast(candidate,target)>=ratio)return candidate;
        }
        return INK;
    }
    RippleDrawable touchField(int color,int radius){return new RippleDrawable(rippleColor(),field(color,radius),field(WHITE,radius));}
    RippleDrawable touch(int color,int radius,boolean border){return new RippleDrawable(rippleColor(),surface(color,radius,border),surface(WHITE,radius,false));}
    /** Button background that actually changes when the button is disabled.
     *
     * Only the label used to respond to state, so a disabled primary button kept a
     * full-strength accent fill and looked pressable. The fill drops to the neutral
     * surface instead, which is what makes "cannot tap this yet" legible. */
    Drawable touchEnabled(int color,int radius){
        StateListDrawable states=new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled},surface(SOFT,radius,false));
        states.addState(new int[]{},touch(color,radius,false));
        return states;
    }
    TextView text(String label,int size,int color){
        TextView v=new TextView(context);v.setText(label);v.setTextSize(size);v.setTextColor(color);
        v.setFontFeatureSettings("kern");v.setIncludeFontPadding(false);v.setLineSpacing(dp(4),1);return v;
    }
    Button button(String label,boolean primary){
        Button b=new Button(context);b.setText(label);b.setAllCaps(false);b.setTextSize(NativeUi.LABEL);
        emphasize(b);b.setGravity(Gravity.CENTER);
        // A disabled label still has to be legible. MUTED was reused here for both
        // kinds of button, but it is tuned against WHITE/SOFT: on a GREEN fill it
        // measured 1.02-1.25:1 on every theme. Derive it from the fill instead.
        int enabled=primary?onPrimary():INK;
        b.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},
            new int[]{disabledText(primary?GREEN:SOFT,enabled),enabled}));
        b.setBackground(touchEnabled(primary?GREEN:SOFT,14));b.setStateListAnimator(null);
        b.setMinHeight(dp(48));b.setMinimumHeight(dp(48));b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(14),dp(8),dp(14),dp(8));return b;
    }
    /** Label colour for a disabled button: a dimmed form of the enabled label.
     *
     * Dimming toward the fill is what makes it read as disabled, but it has to stay
     * above 4.5:1, so the mix ratio is capped by an actual contrast measurement
     * rather than guessed. */
    private static int disabledText(int fill,int enabled){
        int target=fill & 0x00ffffff;
        int best=enabled;
        for(float t=0f;t<=0.62f;t+=0.02f){
            int candidate=blend(enabled,target,t);
            if(contrast(candidate,fill)>=4.5f)best=candidate;else break;
        }
        return best;
    }
    /** WCAG contrast ratio between two colours, ignoring alpha. */
    private static float contrast(int a,int b){
        float la=Color.luminance(a),lb=Color.luminance(b);
        float hi=Math.max(la,lb),lo=Math.min(la,lb);
        return (hi+.05f)/(lo+.05f);
    }
    Button iconButton(String icon,String description){
        Button b=button("",false);b.setBackground(touch(Color.TRANSPARENT,CORNER_SHEET,false));
        b.setCompoundDrawablesWithIntrinsicBounds(null,null,null,null);
        b.setPadding(dp(12),dp(12),dp(12),dp(12));
        centerIcon(b,icon,INK,24);b.setContentDescription(description);return b;
    }
    void centerIcon(Button button,String name,int color,int size){centerIcon(button,name,color,size,0);}
    void centerIcon(Button button,String name,int color,int size,int offsetY){button.setCompoundDrawables(null,null,null,null);button.setCompoundDrawablePadding(0);button.setPadding(0,0,0,0);button.setForeground(new Drawable(){final Symbol symbol=new Symbol(name,color,dp(size));public void draw(Canvas canvas){canvas.save();canvas.translate((getBounds().width()-dp(size))/2f,(getBounds().height()-dp(size))/2f+dp(offsetY));symbol.draw(canvas);canvas.restore();}public void setAlpha(int a){}public void setColorFilter(ColorFilter f){}public int getOpacity(){return PixelFormat.TRANSLUCENT;}});}
    void leading(TextView v,String name,int color,int size){v.setCompoundDrawables(new Symbol(name,color,dp(size)),null,null,null);v.setCompoundDrawablePadding(dp(8));}
    /**
     * The same glyph on the trailing edge.
     *
     * A leading chevron eats into the label's measured width before the label has had
     * a chance to claim any, so on a narrow control the text is the first thing to go
     * and the row ends up reading as a bare arrow. Trailing it puts the icon after the
     * text, where it contracts against itself instead of against the label.
     */
    void trailing(TextView v,String name,int color,int size){v.setCompoundDrawables(null,null,new Symbol(name,color,dp(size)),null);v.setCompoundDrawablePadding(dp(6));}
    /**
     * Weight, as two roles rather than four spellings.
     *
     * The app was setting weight four different ways: Typeface.create with the
     * medium family, Typeface.DEFAULT with BOLD, the fully-qualified
     * android.graphics.Typeface spelling of the same thing, and MONOSPACE. The
     * third is only a longer way of writing the second, and the first two render
     * differently - so two section headings that should have matched did not.
     * Emphasis here means "the medium family", which stays legible at the caption
     * sizes used for section labels; BOLD is reserved for a value read at a glance.
     */
    static void emphasize(TextView view){view.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));}
    static void strong(TextView view){view.setTypeface(Typeface.DEFAULT,Typeface.BOLD);}
    static void monospace(TextView view){view.setTypeface(Typeface.MONOSPACE);}
    /** Monospace at bold weight, for the single-letter change badges. */
    static void monospaceStrong(TextView view){view.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);}

    ImageView icon(String name,int color,int size){
        ImageView v=new ImageView(context);
        v.setImageDrawable(new Symbol(name,color,dp(size)));
        // Stated rather than inherited: Symbol draws into a square, and a host that
        // gives the view a non-square box (the welcome artwork does) would otherwise
        // rely on the default scale type to keep the glyph from distorting.
        v.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        v.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return v;
    }

    void input(EditText input){input.setTextColor(INK);input.setHintTextColor(MUTED);input.setTextSize(NativeUi.BODY);input.setPadding(dp(14),dp(12),dp(14),dp(12));input.setBackground(touchField(SOFT,CORNER_ROW));input.setBackgroundTintList(null);}
    Button menu(String label,boolean selected){Button b=button(label,false);b.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);b.setTextColor(selected?GREEN:INK);b.setBackground(touch(selected?TINT:Color.TRANSPARENT,CORNER_ROW,false));b.setMaxLines(2);b.setEllipsize(android.text.TextUtils.TruncateAt.END);return b;}
    android.widget.ArrayAdapter<String> choices(java.util.List<String> values){return new android.widget.ArrayAdapter<String>(context,android.R.layout.simple_list_item_1,values){
        public View getView(int p,View old,ViewGroup parent){TextView label=old instanceof TextView?(TextView)old:text("",NativeUi.BODY,INK);label.setText(getItem(p));label.setTextColor(INK);label.setMinHeight(dp(52));label.setGravity(Gravity.CENTER_VERTICAL);label.setPadding(dp(14),dp(10),dp(14),dp(10));label.setMaxLines(2);label.setEllipsize(android.text.TextUtils.TruncateAt.END);label.setBackground(touch(Color.TRANSPARENT,CORNER_ROW,false));label.setCompoundDrawables(null,null,parent instanceof Spinner?new Symbol("down",MUTED,dp(16)):null,null);label.setCompoundDrawablePadding(dp(8));return label;}
        public View getDropDownView(int p,View old,ViewGroup parent){return getView(p,old,parent);}
    };}
    Spinner spinner(String[] values){Spinner v=new Spinner(context,Spinner.MODE_DROPDOWN);v.setAdapter(choices(java.util.Arrays.asList(values)));v.setBackground(touch(SOFT,CORNER_ROW,true));v.setPopupBackgroundDrawable(surface(WHITE,CORNER_CARD,true));v.setPadding(dp(4),0,dp(12),0);return v;}
    void reveal(View view,boolean upward){view.animate().cancel();view.animate().withEndAction(null);view.setVisibility(View.VISIBLE);view.setAlpha(0);view.setTranslationY(dp(upward?10:-6));view.animate().alpha(1).translationY(0).setDuration(180).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();}
    void window(Window window,boolean sheet){window.setBackgroundDrawableResource(android.R.color.transparent);window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        // A dark canvas is already low-luminance, so the same scrim that separates a
        // sheet from a white background swallows the dark one entirely.
        window.setDimAmount(Color.luminance(CANVAS)<.2?.46f:.32f);window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);int width=Math.min(dp(560),context.getResources().getDisplayMetrics().widthPixels-dp(24));window.setLayout(width,-2);if(sheet)window.setGravity(Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);WindowManager.LayoutParams attributes=window.getAttributes();if(sheet)attributes.y=dp(12);attributes.windowAnimations=style(sheet?"SheetMotion":"DialogMotion",0);window.setAttributes(attributes);}

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
            // AT_MOST alone let a tall child expand the dock: a scroll view reports the
            // child's full height when it fits within the bound, so a long option list or
            // an expanded step panel stretched the whole composer area and pushed the
            // input box up the screen. The dock is a fixed strip at the foot of the
            // screen - it may scroll, but it must not grow - so the bound is exact.
            super.onMeasure(width,height);
            if(available>0)setMeasuredDimension(getMeasuredWidth(),Math.min(getMeasuredHeight(),Math.max((int)(getResources().getDisplayMetrics().density*116),available*2/3)));
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
