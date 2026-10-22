package org.eurorig.app;
import android.content.Context;import android.graphics.*;import android.view.View;
/** Original map pin; its pointed tip is the exact selected coordinate. */
final class PlacePinView extends View {
    private final Paint paint=new Paint(3);private final Path path=new Path();private final boolean dark;
    PlacePinView(Context c){super(c);dark=c.getSharedPreferences("settings",0).getBoolean("dark_mode",false);setContentDescription("Selected place at this map coordinate");setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);}
    protected void onDraw(Canvas c){float d=getResources().getDisplayMetrics().density;c.save();c.scale(d,d);path.reset();path.moveTo(28,68);path.cubicTo(22,51,7,43,7,27);path.cubicTo(7,0,49,0,49,27);path.cubicTo(49,43,34,51,28,68);path.close();paint.setStyle(Paint.Style.FILL);paint.setColor(dark?0xffef9549:0xffb94d10);c.drawPath(path,paint);paint.setStyle(Paint.Style.STROKE);paint.setColor(dark?0xff33251b:0xfffffcf5);paint.setStrokeWidth(3);c.drawPath(path,paint);paint.setStyle(Paint.Style.FILL);paint.setColor(dark?0xff211c17:0xfffffcf5);c.drawCircle(28,25,8,paint);c.restore();}
}
