package io.github.abyxs.codingremote;

import android.app.*;
import android.content.Context;
import android.view.*;
import android.widget.*;

/** Native dialogs use the same palette and rounded surface as the conversation. */
final class NativeDialogBuilder extends AlertDialog.Builder {
    private final NativeUi ui;
    private View custom;
    NativeDialogBuilder(Context c,NativeUi u){super(c);ui=u;}
    @Override public AlertDialog.Builder setView(View v){decorate(v);if(v instanceof EditText){FrameLayout inset=new FrameLayout(getContext());inset.setPadding(ui.dp(20),ui.dp(8),ui.dp(20),ui.dp(8));inset.addView(v,new FrameLayout.LayoutParams(-1,-2));custom=inset;}else custom=v;return super.setView(custom);}
    @Override public AlertDialog create(){AlertDialog d=super.create();d.setOnShowListener(ignored->style(d));return d;}
    @Override public AlertDialog show(){AlertDialog d=create();d.show();return d;}
    private void style(AlertDialog d){ui.window(d.getWindow(),false);d.getWindow().setBackgroundDrawable(ui.surface(NativeUi.WHITE,NativeUi.CORNER_CARD,true));tint(d.getWindow().getDecorView());
        ListView list=d.getListView();if(list!=null){list.setDivider(null);list.setPadding(ui.dp(10),ui.dp(6),ui.dp(10),ui.dp(6));list.setSelector(ui.touch(NativeUi.TINT,NativeUi.CORNER_ROW,false));list.setOnScrollListener(new AbsListView.OnScrollListener(){public void onScrollStateChanged(AbsListView v,int state){}public void onScroll(AbsListView v,int first,int count,int total){for(int i=0;i<v.getChildCount();i++)tint(v.getChildAt(i));}});}
        // Positive is filled with the accent, neutral and negative stay text-only but
        // in different colours; all three used GREEN before, so a dialog with both a
        // "取消" and a neutral action showed two identical-looking buttons.
        for(int id:new int[]{-1,-2,-3}){Button b=d.getButton(id);if(b!=null){
            boolean positive=id==-1;
            b.setTextColor(new android.content.res.ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},
                new int[]{NativeUi.MUTED,positive?NativeUi.onPrimary():id==-3?NativeUi.ACCENT:NativeUi.GREEN}));
            b.setBackground(new android.graphics.drawable.InsetDrawable(ui.touchEnabled(positive?NativeUi.GREEN:android.graphics.Color.TRANSPARENT,12),ui.dp(4),ui.dp(4),ui.dp(4),ui.dp(4)));
            b.setAllCaps(false);b.setMinHeight(ui.dp(48));b.setMinimumHeight(ui.dp(48));b.setPadding(ui.dp(18),ui.dp(8),ui.dp(18),ui.dp(8));}}
    }
    private void decorate(View v){if(v instanceof EditText)ui.input((EditText)v);else if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)decorate(((ViewGroup)v).getChildAt(i));}
    private void tint(View v){if(v==custom)return;if(v instanceof TextView){((TextView)v).setTextColor(NativeUi.INK);if(v instanceof CheckedTextView)((CheckedTextView)v).setCheckMarkTintList(android.content.res.ColorStateList.valueOf(NativeUi.GREEN));}if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)tint(g.getChildAt(i));}}
}
