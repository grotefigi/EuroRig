package org.eurorig.app;
import android.content.Context;
import android.graphics.*;
import android.animation.*;
import android.os.SystemClock;
import android.view.View;
/** Only this small button animates; it never invalidates the map or rebuilds its geometry. */
final class RecenterControl extends View {
    private final Paint paint=new Paint(3);private final RectF box=new RectF();private final Path clip=new Path();
    private ValueAnimator animator;private float progress;private boolean waiting;
    private final boolean dark;private final Runnable completed;
    RecenterControl(Context c,Runnable completed){super(c);this.completed=completed;dark=c.getSharedPreferences("settings",0).getBoolean("dark_mode",false);setContentDescription("Re-center on vehicle. Automatically returns after 30 seconds without another map gesture.");setFocusable(true);setClickable(true);setElevation(4*getResources().getDisplayMetrics().density);}
    void start(long deadline){stop();waiting=false;setContentDescription("Re-center on vehicle. Automatically returns after 30 seconds without another map gesture.");long remaining=Math.max(0,deadline-SystemClock.elapsedRealtime());float start=1-remaining/30000f;animator=ValueAnimator.ofFloat(Math.max(0,start),1);animator.setDuration(remaining);animator.setInterpolator(new android.view.animation.LinearInterpolator());animator.addUpdateListener(a->{progress=(Float)a.getAnimatedValue();invalidate();});animator.addListener(new AnimatorListenerAdapter(){boolean cancelled;public void onAnimationCancel(Animator a){cancelled=true;}public void onAnimationEnd(Animator a){if(!cancelled)completed.run();}});animator.start();}
    void stop(){if(animator!=null){animator.cancel();animator=null;}}
    void reset(){stop();progress=0;waiting=false;invalidate();}
    void waiting(){stop();progress=1;waiting=true;setContentDescription("Re-center: waiting for a fresh GPS location");invalidate();}
    protected void onDetachedFromWindow(){stop();super.onDetachedFromWindow();}
    protected void onSizeChanged(int w,int h,int oldw,int oldh){box.set(0,0,w,h);clip.reset();float radius=16*getResources().getDisplayMetrics().density;clip.addRoundRect(box,radius,radius,Path.Direction.CW);}
    protected void onDraw(Canvas c){float d=getResources().getDisplayMetrics().density,w=getWidth(),h=getHeight();box.set(0,0,w,h);paint.setColor(dark?0xff28221c:0xfffffcf5);c.drawRoundRect(box,16*d,16*d,paint);c.save();c.clipPath(clip);paint.setColor(dark?0xff55351f:0xffeed2b1);c.drawRect(0,0,w*progress,h,paint);c.restore();paint.setColor(dark?0xfff6efe5:0xff6f2c0b);paint.setTextSize(16*d);paint.setTypeface(Typeface.DEFAULT_BOLD);c.drawText(waiting?"Waiting for GPS":"Re-center",14*d,h/2+6*d,paint);
        float x=w-51*d,y=h/2-10*d;paint.setAlpha((int)(70+185*progress));c.drawRoundRect(x,y,x+26*d,y+17*d,2*d,2*d,paint);c.drawRoundRect(x+29*d,y+5*d,x+42*d,y+17*d,2*d,2*d,paint);paint.setColor(dark?0xffe89045:0xff9c3708);c.drawCircle(x+5*d,y+19*d,3*d,paint);c.drawCircle(x+21*d,y+19*d,3*d,paint);c.drawCircle(x+35*d,y+19*d,3*d,paint);paint.setAlpha(255);
    }
}
