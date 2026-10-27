package org.eurorig.app;
import android.content.Context;import android.graphics.*;import android.text.TextPaint;import android.text.TextUtils;import android.view.View;import org.eurorig.routing.*;import java.util.*;

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
        String lane=event.recommendedNumber()>0?getContext().getString(R.string.navigation_lane_prepare,event.recommendedNumber()):getContext().getString(event.lanes.isEmpty()?R.string.navigation_lane_unmapped:R.string.navigation_lane_no_permitted);
        setContentDescription(getContext().getString(R.string.navigation_lane_upcoming,kindLabel(event.kind),prefs.distance(Math.max(0,event.metres-progress)))+". "+lane+(event.futureNumber()>0?getContext().getString(R.string.navigation_lane_becomes,event.futureNumber()):"")+". "+event.label+". "+getContext().getString(R.string.navigation_lane_schematic));
        int desired=Math.round((mode==2&&!event.lanes.isEmpty()?186:124)*density);if(getLayoutParams()!=null&&getLayoutParams().height!=desired){getLayoutParams().height=desired;requestLayout();}invalidate();
    }
    protected void onDraw(Canvas canvas){
        super.onDraw(canvas);if(event==null)return;float w=getWidth(),h=getHeight(),s=density;int ink=dark?0xfff6efe5:0xff2c2118,accent=dark?0xffe89045:0xff9c3708;
        paint.setColor(dark?0xff211c17:0xfffff9f0);bounds.set(0,0,w,h);canvas.drawRoundRect(bounds,12*s,12*s,paint);
        paint.setTypeface(Typeface.DEFAULT_BOLD);paint.setTextAlign(Paint.Align.LEFT);paint.setTextSize(13*s);paint.setColor(ink);
        String title=getContext().getString(R.string.navigation_lane_upcoming,kindLabel(event.kind),NavigationSettings.get(getContext()).distance(Math.max(0,event.metres-travelled)));canvas.drawText(title,10*s,19*s,paint);
        paint.setTextSize(11*s);paint.setTypeface(Typeface.DEFAULT);String evidence=TextUtils.ellipsize(event.label,new TextPaint(paint),(int)(w-20*s),TextUtils.TruncateAt.END).toString();canvas.drawText(evidence,10*s,36*s,paint);
        String caption=event.recommendedNumber()>0?getContext().getString(R.string.navigation_lane_prepare,event.recommendedNumber())+(event.futureNumber()>0?getContext().getString(R.string.navigation_lane_future,event.futureNumber()):""):getContext().getString(event.lanes.isEmpty()?R.string.navigation_lane_unmapped:R.string.navigation_lane_no_permitted);
        paint.setTextSize(12*s);paint.setTypeface(Typeface.DEFAULT);canvas.drawText(caption,10*s,53*s,paint);
        if(!event.lanes.isEmpty()){
            int count=event.lanes.size(),chosen=event.recommendedIndex();float top=66*s,bottom=h-34*s,left=10*s,right=w-10*s;
            float narrow=mode==2?.35f:1f;
            for(int i=0;i<count;i++){
                float a=left+(right-left)*i/count,b=left+(right-left)*(i+1)/count,at=w/2+(a-w/2)*narrow,bt=w/2+(b-w/2)*narrow;
                path.reset();path.moveTo(at,top);path.lineTo(bt,top);path.lineTo(b,bottom);path.lineTo(a,bottom);path.close();
                NavigationData.Lane lane=event.lanes.get(i);paint.setColor(i==chosen?(dark?0xff744424:0xfff0cba9):lane.permitted?(dark?0xff35312c:0xffe4ddd3):0xffc78484);canvas.drawPath(path,paint);
                paint.setColor(dark?0xffa79a89:0xff998b7d);paint.setStrokeWidth(s);canvas.drawLine(at,top,a,bottom,paint);
                float x=(a+b)/2,y=mode==2?bottom-24*s:(top+bottom)/2;
                paint.setColor(i==chosen?accent:ink);paint.setStrokeWidth(3*s);paint.setStyle(Paint.Style.STROKE);
                int shownDirections=lane.activeDirections!=0?lane.activeDirections:lane.validDirections!=0?lane.validDirections:lane.directions;drawDirections(canvas,x,y,shownDirections,s);
                paint.setStyle(Paint.Style.FILL);paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(10*s);canvas.drawText(Integer.toString(count-i),x,bottom-3*s,paint);paint.setTextAlign(Paint.Align.LEFT);
            }
            if(mode==2&&chosen>=0){float x=left+(right-left)*(chosen+.5f)/count;float motion=(android.os.SystemClock.uptimeMillis()%1800)/1800f;float y=bottom-(14+motion*12)*s;
                paint.setColor(accent);bounds.set(x-5*s,y-14*s,x+5*s,y+2*s);canvas.drawRoundRect(bounds,2*s,2*s,paint);bounds.set(x-5*s,y+4*s,x+5*s,y+10*s);canvas.drawRoundRect(bounds,2*s,2*s,paint);if(isShown())postInvalidateOnAnimation();}
        }
        paint.setColor(ink);paint.setTextSize(10*s);paint.setTextAlign(Paint.Align.LEFT);
        String footer=next==null?getContext().getString(R.string.navigation_lane_footer_upcoming):getContext().getString(R.string.navigation_lane_footer_then,kindLabel(next.kind),NavigationSettings.get(getContext()).distance(Math.max(0,next.metres-travelled)));canvas.drawText(footer,10*s,h-18*s,paint);
        paint.setTextSize(9*s);canvas.drawText(getContext().getString(R.string.navigation_lane_schematic),10*s,h-4*s,paint);
    }
    private String kindLabel(String kind){int id="roundabout".equals(kind)?R.string.navigation_maneuver_roundabout:"roundabout_exit".equals(kind)?R.string.navigation_maneuver_roundabout_exit:"exit".equals(kind)?R.string.navigation_maneuver_exit:"stay_left".equals(kind)?R.string.navigation_maneuver_stay_left:"stay_right".equals(kind)?R.string.navigation_maneuver_stay_right:"merge".equals(kind)?R.string.navigation_maneuver_merge:"split".equals(kind)?R.string.navigation_maneuver_split:R.string.navigation_maneuver_junction;return getContext().getString(id);}
    private void drawDirections(Canvas c,float x,float y,int bits,float s){if(bits==0)return;boolean straight=(bits&2)!=0,left=(bits&(4|8|16|512))!=0,right=(bits&(32|64|128|1024))!=0;if(straight)arrow(c,x,y,0,s);if(left)arrow(c,x,y,-1,s);if(right)arrow(c,x,y,1,s);}
    private void arrow(Canvas c,float x,float y,int direction,float s){float tipX=x,tipY=y-8*s,baseX=x,baseY=y+9*s;if(direction==-1){tipX=x-8*s;tipY=y-5*s;baseX=x;baseY=y+9*s;}else if(direction==1){tipX=x+8*s;tipY=y-5*s;baseX=x;baseY=y+9*s;}canvasLine(c,baseX,baseY,tipX,tipY);if(direction==0){canvasLine(c,tipX,tipY,x-5*s,y-3*s);canvasLine(c,tipX,tipY,x+5*s,y-3*s);}else{float dx=tipX-baseX,dy=tipY-baseY,len=(float)Math.hypot(dx,dy);float px=-dy/len*3*s,py=dx/len*3*s;canvasLine(c,tipX,tipY,tipX-dx/len*5*s+px,tipY-dy/len*5*s+py);canvasLine(c,tipX,tipY,tipX-dx/len*5*s-px,tipY-dy/len*5*s-py);}}
    private void canvasLine(Canvas c,float x1,float y1,float x2,float y2){c.drawLine(x1,y1,x2,y2,paint);}
}
