package org.eurorig.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;

/** One map control that expands on demand and folds after five idle seconds. */
final class MapControlMenu extends LinearLayout {
    final ImageButton toggle;
    final Button zoomIn,camera,zoomOut;
    private final LinearLayout expanded;
    private final Runnable collapse=()->setExpanded(false);
    MapControlMenu(Context context,int panel,int accent,Runnable plus,Runnable changeCamera,Runnable minus){
        super(context);setOrientation(VERTICAL);setElevation(dp(4));
        GradientDrawable shape=new GradientDrawable();shape.setColor(panel);shape.setCornerRadius(dp(18));setBackground(shape);setClipToOutline(true);
        toggle=new ImageButton(context);toggle.setImageResource(R.drawable.ic_map_controls);toggle.setImageTintList(ColorStateList.valueOf(accent));toggle.setContentDescription("Expand zoom and camera controls");
        toggle.setBackground(ripple(accent));addView(toggle,new LayoutParams(dp(56),dp(56)));toggle.setOnClickListener(v->setExpanded(true));
        expanded=new LinearLayout(context);expanded.setOrientation(VERTICAL);addView(expanded);
        zoomIn=cell("+","Zoom in",accent,plus);divider(accent);
        camera=cell("3D","Switch to 3D perspective map",accent,changeCamera);divider(accent);
        zoomOut=cell("−","Zoom out",accent,minus);
        setExpanded(false);
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private android.graphics.drawable.Drawable ripple(int accent){return new RippleDrawable(ColorStateList.valueOf((accent&0xffffff)|0x22000000),new ColorDrawable(Color.TRANSPARENT),null);}
    private Button cell(String text,String description,int accent,Runnable action){
        Button button=new Button(getContext());button.setAllCaps(false);button.setText(text);button.setTextSize(21);button.setTypeface(null,Typeface.BOLD);button.setTextColor(accent);button.setContentDescription(description);
        button.setMinWidth(0);button.setMinimumWidth(0);button.setPadding(0,0,0,0);button.setBackground(ripple(accent));
        expanded.addView(button,new LayoutParams(dp(56),dp(56)));
        button.setOnClickListener(v->{action.run();arm();});
        button.setOnTouchListener((v,event)->{if(event.getActionMasked()==MotionEvent.ACTION_DOWN)removeCallbacks(collapse);else if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL)arm();return false;});
        return button;
    }
    private void divider(int accent){View divider=new View(getContext());divider.setBackgroundColor((accent&0xffffff)|0x16000000);expanded.addView(divider,new LayoutParams(-1,dp(1)));}
    void cameraChanged(boolean perspective){camera.setText(perspective?"2D":"3D");camera.setContentDescription(perspective?"Switch to 2D north-up map":"Switch to 3D perspective map");}
    boolean isExpanded(){return expanded.getVisibility()==VISIBLE;}
    void setExpanded(boolean open){removeCallbacks(collapse);toggle.setVisibility(open?GONE:VISIBLE);expanded.setVisibility(open?VISIBLE:GONE);if(open)arm();}
    private void arm(){removeCallbacks(collapse);if(isExpanded())postDelayed(collapse,5000);}
    @Override protected void onDetachedFromWindow(){removeCallbacks(collapse);super.onDetachedFromWindow();}
    @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(visibility!=VISIBLE&&expanded!=null)setExpanded(false);}
}
