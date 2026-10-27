package org.eurorig.app;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Original line icons for the native journey editor and country preferences. */
@android.annotation.SuppressLint("ViewConstructor") // Constructed in code with its icon and palette.
final class RouteGlyph extends View {
    private final String kind;private final int color;private final Paint paint=new Paint(3);private final Path path=new Path();
    RouteGlyph(Context context,String kind,int color){super(context);this.kind=kind;this.color=color;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    @Override public boolean performClick(){return super.performClick();}
    protected void onDraw(Canvas canvas){
        canvas.save();canvas.translate((getWidth()-Math.min(getWidth(),getHeight()))/2f,(getHeight()-Math.min(getWidth(),getHeight()))/2f);canvas.scale(Math.min(getWidth(),getHeight())/32f,Math.min(getWidth(),getHeight())/32f);
        paint.setColor(color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2.5f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
        if(kind.equals("clock")){canvas.drawCircle(16,16,11,paint);canvas.drawLine(16,9,16,16,paint);canvas.drawLine(16,16,21,19,paint);}
        else if(kind.equals("start")||kind.equals("add")){canvas.drawCircle(16,16,12,paint);if(kind.equals("start"))canvas.drawCircle(16,16,7,paint);else{canvas.drawLine(10,16,22,16,paint);canvas.drawLine(16,10,16,22,paint);}}
        else if(kind.equals("flag")){canvas.drawLine(8,28,8,5,paint);canvas.drawRect(9,5,26,17,paint);paint.setStyle(Paint.Style.FILL);canvas.drawRect(10,6,17,11,paint);canvas.drawRect(18,12,25,16,paint);}
        else if(kind.equals("distance")){canvas.drawCircle(8,24,3,paint);canvas.drawCircle(24,7,3,paint);path.reset();path.moveTo(12,24);path.lineTo(23,24);path.cubicTo(32,24,29,15,21,15);path.lineTo(12,15);path.cubicTo(3,15,4,7,17,7);canvas.drawPath(path,paint);}
        else if(kind.equals("highways")){canvas.drawLine(10,4,6,29,paint);canvas.drawLine(22,4,26,29,paint);canvas.drawLine(4,12,28,12,paint);canvas.drawLine(3,21,29,21,paint);canvas.drawLine(16,6,16,10,paint);canvas.drawLine(16,16,16,20,paint);canvas.drawLine(16,26,16,29,paint);}
        else if(kind.equals("ferries")){canvas.drawLine(10,10,24,10,paint);canvas.drawLine(12,10,12,17,paint);canvas.drawLine(22,10,22,17,paint);canvas.drawLine(17,4,17,10,paint);path.reset();path.moveTo(4,18);path.lineTo(16,15);path.lineTo(28,18);path.lineTo(23,25);path.lineTo(9,25);path.close();canvas.drawPath(path,paint);canvas.drawLine(5,29,27,29,paint);}
        else if(kind.equals("unpaved")){canvas.drawRoundRect(9,9,27,22,3,3,paint);canvas.drawLine(11,22,11,26,paint);canvas.drawLine(25,22,25,26,paint);canvas.drawLine(10,15,26,15,paint);for(int i=0;i<6;i++)canvas.drawPoint(3+i%2*3,6+i*4,paint);}
        else if(kind.equals("tolls")||kind.equals("congestion")){canvas.drawCircle(16,16,12,paint);paint.setStyle(Paint.Style.FILL);paint.setTypeface(Typeface.DEFAULT_BOLD);paint.setTextSize(21);paint.setTextAlign(Paint.Align.CENTER);canvas.drawText(kind.equals("tolls")?"$":"C",16,23,paint);}
        else if(kind.equals("drag")){canvas.drawLine(8,13,24,13,paint);canvas.drawLine(8,20,24,20,paint);}
        else if(kind.equals("country")){canvas.drawCircle(16,16,12,paint);canvas.drawOval(10,4,22,28,paint);canvas.drawLine(4,16,28,16,paint);}
        else {canvas.drawLine(21,6,11,16,paint);canvas.drawLine(11,16,21,26,paint);}
        canvas.restore();
    }
}
