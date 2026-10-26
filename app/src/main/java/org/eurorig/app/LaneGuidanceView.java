package org.eurorig.app;
import android.content.Context;import android.graphics.*;import android.view.View;import org.eurorig.routing.*;import java.util.*;

/** Original upcoming-layout preview. No lane geometry is invented when evidence is absent. */
final class LaneGuidanceView extends View {
    private final Paint paint=new Paint(3);private final Path path=new Path();
    private NavigationData.Event event,next;private double travelled;private int mode;private boolean dark;
    private final float density;private final RectF bounds=new RectF();
    LaneGuidanceView(Context context){super(context);density=getResources().getDisplayMetrics().density;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);setVisibility(GONE);}
    void update(Router.Route route,double progress,boolean active,boolean night){
        NavigationSettings prefs=NavigationSettings.get(getContext());mode=prefs.lanes;dark=night;travelled=progress;
        java.util.List<NavigationData.Event> events=active&&route!=null&&mode!=0?route.navigation.lanePreview(progress,5000):Collections.emptyList();
        event=events.isEmpty()?null:events.get(0);next=events.size()>1?events.get(1):null;
        setVisibility(event==null?GONE:VISIBLE);if(event==null)return;
        String lane=event.recommendedNumber()>0?". Prepare lane "+event.recommendedNumber()+" counting from the right":". Lane layout is not mapped";
        setContentDescription("Upcoming "+event.kind+" in "+prefs.distance(Math.max(0,event.metres-progress))+lane+(event.futureNumber()>0?", becomes lane "+event.futureNumber():"")+". "+event.label);
        int desired=Math.round((mode==2&&!event.lanes.isEmpty()?152:90)*density);if(getLayoutParams()!=null&&getLayoutParams().height!=desired){getLayoutParams().height=desired;requestLayout();}invalidate();
    }
    protected void onDraw(Canvas canvas){
        super.onDraw(canvas);if(event==null)return;float w=getWidth(),h=getHeight(),s=density;int ink=dark?0xfff6efe5:0xff2c2118,accent=dark?0xffe89045:0xff9c3708;
        paint.setColor(dark?0xff211c17:0xfffff9f0);bounds.set(0,0,w,h);canvas.drawRoundRect(bounds,12*s,12*s,paint);
        paint.setTypeface(Typeface.DEFAULT_BOLD);paint.setTextAlign(Paint.Align.LEFT);paint.setTextSize(13*s);paint.setColor(ink);
        String title="Upcoming "+event.kind.replace('_',' ')+" · "+NavigationSettings.get(getContext()).distance(Math.max(0,event.metres-travelled));canvas.drawText(title,10*s,19*s,paint);
        String caption=event.recommendedNumber()>0?"Prepare lane "+event.recommendedNumber()+" from right"+(event.futureNumber()>0?" → lane "+event.futureNumber():""):"Lane layout not mapped · follow maneuver";
        paint.setTextSize(12*s);paint.setTypeface(Typeface.DEFAULT);canvas.drawText(caption,10*s,37*s,paint);
        if(!event.lanes.isEmpty()){
            int count=event.lanes.size(),chosen=event.recommendedIndex();float top=48*s,bottom=h-22*s,left=10*s,right=w-10*s;
            float narrow=mode==2?.35f:1f;
            for(int i=0;i<count;i++){
                float a=left+(right-left)*i/count,b=left+(right-left)*(i+1)/count,at=w/2+(a-w/2)*narrow,bt=w/2+(b-w/2)*narrow;
                path.reset();path.moveTo(at,top);path.lineTo(bt,top);path.lineTo(b,bottom);path.lineTo(a,bottom);path.close();
                NavigationData.Lane lane=event.lanes.get(i);paint.setColor(i==chosen?(dark?0xff744424:0xfff0cba9):lane.permitted?(dark?0xff35312c:0xffe4ddd3):0xffc78484);canvas.drawPath(path,paint);
                paint.setColor(dark?0xffa79a89:0xff998b7d);paint.setStrokeWidth(s);canvas.drawLine(at,top,a,bottom,paint);
                float x=(a+b)/2,y=mode==2?bottom-24*s:(top+bottom)/2;
                paint.setColor(i==chosen?accent:ink);paint.setStrokeWidth(3*s);paint.setStyle(Paint.Style.STROKE);
                if(lane.directions!=0){int bits=lane.directions;boolean turnLeft=(bits&(4|8|16|512))!=0,turnRight=(bits&(32|64|128|1024))!=0,straight=(bits&2)!=0;
                    if(straight||(!turnLeft&&!turnRight)){canvas.drawLine(x,y+9*s,x,y-8*s,paint);canvas.drawLine(x,y-8*s,x-5*s,y-3*s,paint);canvas.drawLine(x,y-8*s,x+5*s,y-3*s,paint);}
                    if(turnLeft||turnRight){float dir=turnLeft?-1:1;canvas.drawLine(x,y+9*s,x,y,paint);canvas.drawLine(x,y,x+dir*9*s,y-5*s,paint);canvas.drawLine(x+dir*9*s,y-5*s,x+dir*3*s,y-6*s,paint);}
                }
                paint.setStyle(Paint.Style.FILL);paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(10*s);canvas.drawText(Integer.toString(count-i),x,bottom-3*s,paint);paint.setTextAlign(Paint.Align.LEFT);
            }
            if(mode==2&&chosen>=0){float x=left+(right-left)*(chosen+.5f)/count;float motion=(android.os.SystemClock.uptimeMillis()%1800)/1800f;float y=bottom-(14+motion*12)*s;
                paint.setColor(accent);bounds.set(x-5*s,y-14*s,x+5*s,y+2*s);canvas.drawRoundRect(bounds,2*s,2*s,paint);bounds.set(x-5*s,y+4*s,x+5*s,y+10*s);canvas.drawRoundRect(bounds,2*s,2*s,paint);if(isShown())postInvalidateOnAnimation();}
        }
        paint.setColor(ink);paint.setTextSize(10*s);paint.setTextAlign(Paint.Align.LEFT);
        String footer=next==null?"Upcoming layout · remain until past junction":"Then: "+next.kind.replace('_',' ')+" · "+NavigationSettings.get(getContext()).distance(Math.max(0,next.metres-travelled));canvas.drawText(footer,10*s,h-5*s,paint);
    }
}
