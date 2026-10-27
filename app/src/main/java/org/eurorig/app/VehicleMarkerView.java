package org.eurorig.app;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** One original vehicle marker. Truck and arrow are mutually exclusive. */
final class VehicleMarkerView extends View {
    private final Paint paint=new Paint(3);
    private final Path arrowOutline=polygon(0,-24,18,21,0,13,-18,21),arrowFill=polygon(0,-17,12,15,0,8,-12,15);
    private final Path cabFront=polygon(-11,-27,13,-32,23,-18,2,-10),cabRoof=polygon(-11,-27,1,-39,20,-39,13,-32);
    private final Path window=polygon(1,-36,17,-36,19,-30,1,-26);
    private final Path boxSide=polygon(11,-23,24,-32,24,3,11,14),boxBack=polygon(-17,-20,11,-23,11,14,-17,17);
    private final Path boxRoof=polygon(-17,-20,-4,-32,24,-32,11,-23);
    private final Path leftLight=polygon(-14,12,-8,12,-8,15,-14,15),rightLight=polygon(4,10,9,10,9,13,4,13);
    private boolean truck;
    private float heading;
    VehicleMarkerView(Context c){super(c);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);setLayerType(LAYER_TYPE_SOFTWARE,null);}
    void style(boolean truck,float heading){if(this.truck==truck&&Math.abs(this.heading-heading)<.01f)return;this.truck=truck;this.heading=heading;invalidate();}
    private static Path polygon(float...p){Path path=new Path();path.moveTo(p[0],p[1]);for(int i=2;i<p.length;i+=2)path.lineTo(p[i],p[i+1]);path.close();return path;}
    private void face(Canvas c,int colour,Path path){paint.setColor(colour);paint.setStyle(Paint.Style.FILL);c.drawPath(path,paint);}
    protected void onDraw(Canvas c){
        super.onDraw(c);c.save();c.translate(getWidth()/2f,getHeight()/2f);float s=getResources().getDisplayMetrics().density;c.scale(s,s);c.rotate(heading);
        if(!truck){paint.setShadowLayer(4,0,2,0x77000000);face(c,0xfffff6eb,arrowOutline);paint.clearShadowLayer();face(c,0xffb94d10,arrowFill);}
        else{
            paint.setColor(0x55000000);c.drawOval(-24,-7,28,18,paint);
            paint.setColor(0xff171614);for(int row=0;row<2;row++){float y=4+9*row;c.drawOval(-19,y-4,-12,y+5,paint);c.drawOval(12,y-4,19,y+5,paint);}
            face(c,0xffb94d10,cabFront);face(c,0xffe8a363,cabRoof);
            face(c,0xff33423e,window);
            face(c,0xff96734f,boxSide);face(c,0xffc4a17b,boxBack);
            face(c,0xfff0d6b1,boxRoof);
            paint.setColor(0xff775736);paint.setStrokeWidth(1);c.drawLine(-3,-20,-3,15,paint);
            face(c,0xff9f292e,leftLight);face(c,0xff9f292e,rightLight);
        }c.restore();
    }
}
