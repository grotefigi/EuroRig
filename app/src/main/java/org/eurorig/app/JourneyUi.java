package org.eurorig.app;

import android.app.*;
import android.content.*;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import org.json.*;
import org.eurorig.routing.*;

/** Search and route editing share the installed map and the existing audited router. */
final class JourneyUi {
    final MainActivity host;private Dialog dialog;AlertDialog waypointActionsDialog;EditText query;ListView results;
    /** Icon per shortcut, in the same order as the shortcut names. */
    private static final int[] SHORTCUT_ICONS={R.drawable.ic_shortcut_favourites,R.drawable.ic_shortcut_fuel,R.drawable.ic_shortcut_parking,R.drawable.ic_shortcut_services};
    private final Handler handler=new Handler(Looper.getMainLooper());private int generation;
    private String routeTitle="";
    private int bg,panel,text,muted,accent;
    JourneyUi(MainActivity host){this.host=host;}
    private int dp(float v){return Math.round(v*host.getResources().getDisplayMetrics().density);}
    private void colors(){boolean dark=host.getSharedPreferences("settings",0).getBoolean("dark_mode",false);bg=dark?0xff000000:0xfffff9f0;panel=dark?0xff211c17:0xffeee4d7;text=dark?0xfff6efe5:0xff2c2118;muted=dark?0xffc0af9b:0xff6b5948;accent=dark?0xffe89045:0xff9c3708;}
    private GradientDrawable surface(){GradientDrawable d=new GradientDrawable();d.setColor(panel);d.setCornerRadius(dp(18));return d;}
    private TextView label(String s,int size,int color){TextView t=new TextView(host);t.setText(s);t.setTextSize(size);t.setTextColor(color);return t;}
    private Button button(String s,Runnable click){Button v=new Button(host);v.setText(s);v.setAllCaps(false);v.setTextSize(16);v.setTextColor(accent);v.setBackground(surface());v.setMinHeight(dp(52));v.setOnClickListener(x->click.run());return v;}
    /** Borderless glyph, so back and clear can sit inside the search bar rather than above it. */
    private TextView glyph(String symbol,String description,Runnable click){TextView v=new TextView(host);v.setText(symbol);v.setTextSize(27);v.setTextColor(accent);v.setGravity(Gravity.CENTER);v.setContentDescription(description);v.setOnClickListener(x->click.run());return v;}
    private LinearLayout column(){LinearLayout v=new LinearLayout(host);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout page(String title){return page(title,true);}
    /** Pages whose back control sits inside their own bar do not need a separate header row. */
    private LinearLayout page(String title,boolean header){close();host.overlayOpened();colors();dialog=new Dialog(host);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);LinearLayout root=column();root.setBackgroundColor(bg);root.setPadding(dp(16),dp(12),dp(16),dp(12));if(header){LinearLayout head=new LinearLayout(host);head.setGravity(Gravity.CENTER_VERTICAL);head.addView(button("‹",this::close),new LinearLayout.LayoutParams(dp(48),dp(52)));TextView name=label(title,25,text);name.setTypeface(null,Typeface.BOLD);name.setPadding(dp(12),0,0,0);head.addView(name,new LinearLayout.LayoutParams(0,-2,1));root.addView(head);}dialog.setContentView(root);dialog.show();Window w=dialog.getWindow();w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);w.setBackgroundDrawableResource(android.R.color.transparent);w.setStatusBarColor(bg);w.setNavigationBarColor(bg);w.getDecorView().setSystemUiVisibility(bg==0xff000000?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);w.setLayout(-1,-1);w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);return root;}
    void close(){generation++;handler.removeCallbacksAndMessages(null);if(dialog!=null)dialog.dismiss();dialog=null;routeTitle="";}
    void configurationChanged(){if(!showing())return;String title=routeTitle;if(title.equals(host.getString(R.string.screen_title_edit_route)))edit();else if(title.equals(host.getString(R.string.action_route_options)))routeOptions();}
    boolean showing(){return dialog!=null&&dialog.isShowing();}
    private void row(LinearLayout parent,String title,String detail,Runnable action){LinearLayout box=column();box.setPadding(dp(16),dp(14),dp(16),dp(14));box.setBackground(surface());TextView name=label(title,18,text);name.setTypeface(null,Typeface.BOLD);box.addView(name);if(!detail.isEmpty()){TextView sub=label(detail,14,muted);sub.setPadding(0,dp(5),0,0);box.addView(sub);}box.setMinimumHeight(dp(72));box.setFocusable(true);box.setOnClickListener(v->action.run());LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(8);parent.addView(box,p);}
    private void routeColors(){
        boolean dark=host.getSharedPreferences("settings",0).getBoolean("dark_mode",false);
        bg=dark?0xff101010:0xfffff9f0;panel=dark?0xff242424:0xffeee9e1;
        text=dark?0xfff5f5f5:0xff222222;muted=dark?0xffaaaaaa:0xff666666;accent=0xff61a7f4;
    }
    private LinearLayout routePage(String title,boolean side){
        LinearLayout root=page(title,false);routeTitle=title;routeColors();root.setBackground(surfaceColor(bg,16));root.setPadding(0,dp(10),0,dp(12));
        LinearLayout head=new LinearLayout(host);head.setGravity(Gravity.CENTER_VERTICAL);head.setPadding(dp(12),0,dp(12),dp(8));
        RouteGlyph back=new RouteGlyph(host,"back",accent);back.setContentDescription(host.getString(R.string.action_back));back.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);back.setFocusable(true);back.setOnClickListener(v->{if(title.equals(host.getString(R.string.action_route_options)))edit();else close();});
        head.addView(back,new LinearLayout.LayoutParams(dp(40),dp(48)));TextView caption=label(title,25,text);caption.setTypeface(null,Typeface.BOLD);head.addView(caption,new LinearLayout.LayoutParams(0,dp(56),1));caption.setGravity(Gravity.CENTER_VERTICAL);root.addView(head);
        Window window=dialog.getWindow();boolean wide=host.getResources().getConfiguration().screenWidthDp>host.getResources().getConfiguration().screenHeightDp;
        if(side&&wide){window.setGravity(Gravity.START|Gravity.TOP);WindowManager.LayoutParams attrs=window.getAttributes();attrs.x=dp(10);attrs.y=dp(8);window.setAttributes(attrs);window.setLayout(dp(Math.min(480,host.getResources().getConfiguration().screenWidthDp*.4f)),-1);}
        return root;
    }
    private GradientDrawable surfaceColor(int color,int radius){GradientDrawable drawable=new GradientDrawable();drawable.setColor(color);drawable.setCornerRadius(dp(radius));return drawable;}
    private void divider(LinearLayout parent){View line=new View(host);line.setBackgroundColor(bg==0xff101010?0xff292929:0xffd8d2c8);parent.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));}
    private String stopName(Graph.Node point){return point.label.isEmpty()?String.format(Locale.ROOT,host.getString(R.string.coordinate_stop_format),point.lat,point.lon):point.label;}
    private String pointName(boolean start){return start?(Store.originChosen?Store.originFromGps?host.getString(R.string.start_point_current_position):host.pointLabel(Store.start):host.getString(R.string.start_point_choose)):(Store.destinationChosen?host.pointLabel(Store.end):host.getString(R.string.destination_choose));}
    void edit(){
        LinearLayout root=routePage(host.getString(R.string.screen_title_edit_route),true);Router.Route route=Store.route;LinearLayout metrics=new LinearLayout(host);metrics.setPadding(dp(14),dp(4),dp(14),dp(14));
        String[] icons={"flag","clock","distance"};String[] values=route==null?new String[]{"—","—","—"}:new String[]{NavigationSettings.get(host).time(System.currentTimeMillis()+(long)(route.seconds*1000),host),duration(route.seconds),NavigationDistance.format(route.metres)};
        for(int i=0;i<3;i++){LinearLayout metric=column();metric.setGravity(Gravity.CENTER);metric.addView(new RouteGlyph(host,icons[i],accent),new LinearLayout.LayoutParams(dp(28),dp(28)));TextView value=label(values[i],16,text);value.setGravity(Gravity.CENTER);LinearLayout.LayoutParams margin=new LinearLayout.LayoutParams(-1,dp(38));metric.addView(value,margin);metrics.addView(metric,new LinearLayout.LayoutParams(0,-2,1));}root.addView(metrics);divider(root);
        List<View> stopRows=new ArrayList<>();ScrollView scroll=new ScrollView(host);LinearLayout fields=new LinearLayout(host){final android.graphics.Paint paint=new android.graphics.Paint(3);protected void dispatchDraw(android.graphics.Canvas canvas){paint.setColor(accent);paint.setStrokeWidth(dp(1));float x=getPaddingLeft()+dp(17);for(int i=1;i<stopRows.size();i++){View previous=stopRows.get(i-1),next=stopRows.get(i);canvas.drawLine(x,previous.getTop()+previous.getHeight()/2f+dp(15),x,next.getTop()+next.getHeight()/2f-dp(15),paint);}super.dispatchDraw(canvas);}};fields.setOrientation(LinearLayout.VERTICAL);fields.setPadding(dp(12),dp(14),dp(14),dp(16));scroll.addView(fields);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        int count=Store.viaPoints.size()+2;
        for(int index=0;index<count;index++){
            final int at=index;boolean first=index==0,last=index==count-1;
            String title=first?pointName(true):last?pointName(false):stopName(Store.viaPoints.get(index-1));
            LinearLayout row=new LinearLayout(host);row.setGravity(Gravity.CENTER_VERTICAL);RouteGlyph symbol=new RouteGlyph(host,first?"start":last?"flag":"add",accent);
            row.addView(symbol,new LinearLayout.LayoutParams(dp(34),dp(48)));LinearLayout card=new LinearLayout(host);card.setGravity(Gravity.CENTER_VERTICAL);card.setBackground(surfaceColor(panel,9));card.setPadding(dp(12),dp(4),0,dp(4));
            TextView name=label(title,16,text);name.setMaxLines(2);name.setGravity(Gravity.CENTER_VERTICAL);card.addView(name,new LinearLayout.LayoutParams(0,dp(54),1));
            RouteGlyph drag=new RouteGlyph(host,"drag",accent);drag.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);drag.setContentDescription(host.getString(R.string.waypoint_reorder_accessibility,index+1));drag.setFocusable(true);card.addView(drag,new LinearLayout.LayoutParams(dp(44),dp(48)));
            row.addView(card,new LinearLayout.LayoutParams(0,-2,1));LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(-1,-2);size.topMargin=dp(7);size.bottomMargin=dp(7);fields.addView(row,size);stopRows.add(row);
            card.setContentDescription(host.getString(R.string.waypoint_card_accessibility,first?host.getString(R.string.search_title_starting_point):last?host.getString(R.string.search_title_destination):host.getString(R.string.waypoint_number_label,index),title));
            card.setFocusable(true);card.setOnClickListener(v->{if(first)search(1);else if(last)search(3);else waypointActions(at-1);});
            drag.setOnClickListener(v->reorderActions(at));drag.setOnLongClickListener(v->v.startDragAndDrop(android.content.ClipData.newPlainText("Journey stop",Integer.toString(at)),new View.DragShadowBuilder(row),Integer.valueOf(at),0));
            drag.setOnTouchListener(new View.OnTouchListener(){float x,y;boolean dragGesture;public boolean onTouch(View v,android.view.MotionEvent event){if(event.getAction()==android.view.MotionEvent.ACTION_DOWN){x=event.getX();y=event.getY();dragGesture=false;v.getParent().requestDisallowInterceptTouchEvent(true);}else if(event.getAction()==android.view.MotionEvent.ACTION_MOVE&&!dragGesture&&Math.hypot(event.getX()-x,event.getY()-y)>dp(12)){dragGesture=true;v.performLongClick();return true;}else if(event.getAction()==android.view.MotionEvent.ACTION_UP){v.getParent().requestDisallowInterceptTouchEvent(false);if(dragGesture){dragGesture=false;return true;}v.performClick();return true;}else if(event.getAction()==android.view.MotionEvent.ACTION_CANCEL){v.getParent().requestDisallowInterceptTouchEvent(false);dragGesture=false;return true;}return dragGesture;}});
            if(first&&route!=null){TextView leg=label(duration(route.seconds)+" · "+NavigationDistance.format(route.metres),14,muted);leg.setPadding(dp(46),dp(6),0,dp(8));fields.addView(leg);}
        }
        fields.setOnDragListener((v,event)->{
            if(!(event.getLocalState() instanceof Integer))return false;
            if(event.getAction()==android.view.DragEvent.ACTION_DRAG_LOCATION){float y=event.getY()-scroll.getScrollY();if(y<dp(48))scroll.smoothScrollBy(0,-dp(24));else if(y>scroll.getHeight()-dp(48))scroll.smoothScrollBy(0,dp(24));}
            if(event.getAction()==android.view.DragEvent.ACTION_DROP){int to=stopRows.size()-1;for(int i=0;i<stopRows.size();i++)if(event.getY()<stopRows.get(i).getTop()+stopRows.get(i).getHeight()/2f){to=i;break;}moveStop((Integer)event.getLocalState(),to);}
            return true;
        });
        LinearLayout add=new LinearLayout(host);add.setGravity(Gravity.CENTER_VERTICAL);add.addView(new RouteGlyph(host,"add",accent),new LinearLayout.LayoutParams(dp(34),dp(48)));Button waypoint=button(host.getString(R.string.action_add_waypoint),()->{if(Store.viaPoints.size()>=JourneyPlan.MAX_WAYPOINTS){host.error(host.getString(R.string.error_max_waypoints));return;}search(2);});add.addView(waypoint,new LinearLayout.LayoutParams(0,dp(48),1));fields.addView(add);
        divider(root);LinearLayout footer=column();footer.setPadding(dp(14),dp(10),dp(14),0);
        Button vehicle=button(host.getString(R.string.action_vehicle_profile),()->{close();host.openTrucks();});footerIcon(vehicle,R.drawable.ic_profile_truck);footer.addView(vehicle,new LinearLayout.LayoutParams(-1,dp(52)));
        Button options=button(host.getString(R.string.action_route_options),this::routeOptions);footerIcon(options,R.drawable.ic_route_highway);LinearLayout.LayoutParams gap=new LinearLayout.LayoutParams(-1,dp(52));gap.topMargin=dp(8);footer.addView(options,gap);
        Button calculate=button(route==null?host.getString(R.string.action_calculate_route):host.getString(R.string.action_update_route),()->{close();host.planSelectedRoute();});calculate.setEnabled(Store.originChosen&&Store.destinationChosen);LinearLayout.LayoutParams action=new LinearLayout.LayoutParams(-1,dp(48));action.topMargin=dp(10);footer.addView(calculate,action);root.addView(footer);
        // A short landscape window cannot fit a pinned footer and a usable stop list.
        if(host.getResources().getConfiguration().screenHeightDp<520){root.removeView(metrics);root.removeView(footer);fields.addView(metrics,0);fields.addView(footer);if(route==null)metrics.setVisibility(View.GONE);}
    }
    private void footerIcon(Button button,int resource){android.graphics.drawable.Drawable icon=host.getDrawable(resource).mutate();icon.setTint(accent);icon.setBounds(0,0,dp(28),dp(28));button.setCompoundDrawablesRelative(icon,null,null,null);button.setCompoundDrawablePadding(dp(10));button.setPadding(dp(20),0,dp(20),0);}
    private void waypointActions(int index){
        waypointActionsDialog=new AlertDialog.Builder(host).setTitle(host.getString(R.string.waypoint_number_label,index+1)).setItems(new String[]{host.getString(R.string.waypoint_action_change_location),host.getString(R.string.waypoint_action_move_earlier),host.getString(R.string.waypoint_action_move_later),host.getString(R.string.waypoint_action_remove)},(d,choice)->{
            if(choice==0){search(index+4);return;}
            if(choice==3)Store.viaPoints.remove(index);else{int to=index+(choice==1?-1:1);if(to>=0&&to<Store.viaPoints.size())JourneyPlan.move(Store.viaPoints,index,to);}
            host.routePointsChanged();edit();
        }).create();waypointActionsDialog.show();
    }
    private void reorderActions(int index){
        new AlertDialog.Builder(host).setTitle(host.getString(R.string.dialog_title_reorder_stop)).setItems(new String[]{host.getString(R.string.waypoint_action_move_earlier),host.getString(R.string.waypoint_action_move_later)},(d,choice)->{int to=index+(choice==0?-1:1);if(to>=0&&to<Store.viaPoints.size()+2)moveStop(index,to);}).show();
    }
    private void moveStop(int from,int to){
        if(from==to)return;if(!Store.originChosen||!Store.destinationChosen){host.error(host.getString(R.string.error_reorder_needs_points));return;}
        List<Graph.Node> points=new ArrayList<>();points.add(Store.graph.nodes[Store.start]);points.addAll(Store.viaPoints);points.add(Store.graph.nodes[Store.end]);JourneyPlan.move(points,from,to);
        int origin=Store.coordinate(points.get(0).lat,points.get(0).lon,points.get(0).label),end=Store.coordinate(points.get(points.size()-1).lat,points.get(points.size()-1).lon,points.get(points.size()-1).label);
        if(origin<0||end<0){host.error(host.getString(R.string.error_stop_outside_map));return;}
        if(Store.end!=end)Store.revokeAuthorisation();if(Store.start!=origin)Store.originFromGps=false;Store.start=origin;Store.end=end;
        Store.viaPoints.clear();Store.viaPoints.addAll(points.subList(1,points.size()-1));host.routePointsChanged();edit();
    }
    void routeOptions(){
        if(!host.canChangeMap())return;LinearLayout root=routePage(host.getString(R.string.action_route_options),false);ScrollView scroll=new ScrollView(host);LinearLayout content=column();scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        CountryRouteOptions options=new CountryRouteOptions(host);List<String> countries=options.shownCountries();
        for(int i=0;i<countries.size();i++){
            String code=countries.get(i);LinearLayout country=new LinearLayout(host);country.setGravity(Gravity.CENTER_VERTICAL);country.setPadding(dp(14),dp(6),dp(18),dp(6));
            country.addView(new InterfacePages.CountryFlag(host,code,true),new LinearLayout.LayoutParams(dp(48),dp(48)));TextView name=label(CountryRouteOptions.name(code),18,text);name.setTypeface(null,Typeface.BOLD);country.addView(name,new LinearLayout.LayoutParams(0,dp(54),1));TextView role=label(Store.route==null?host.getString(R.string.country_role_settings):i==0?host.getString(R.string.country_role_starting):host.getString(R.string.country_role_route),13,muted);country.addView(role);content.addView(country);divider(content);
            for(CountryPreferences.Option option:CountryPreferences.Option.values()){
                LinearLayout line=new LinearLayout(host);line.setGravity(Gravity.CENTER_VERTICAL);line.setPadding(dp(16),dp(8),dp(18),dp(8));line.setMinimumHeight(dp(76));line.addView(new RouteGlyph(host,option.key,accent),new LinearLayout.LayoutParams(dp(30),dp(30)));
                LinearLayout copy=column();copy.setPadding(dp(18),0,dp(8),0);copy.addView(label(option.title,17,text));if(option==CountryPreferences.Option.CONGESTION)copy.addView(label(host.getString(R.string.country_note_congestion_unavailable),12,muted));line.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
                Switch control=new Switch(host);control.setContentDescription(CountryRouteOptions.name(code)+": "+option.title);control.setThumbTintList(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{accent,0xff777777}));control.setTrackTintList(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{0xff37658e,0xff444444}));control.setChecked(options.get(code,option,Store.truck));control.setEnabled(option!=CountryPreferences.Option.CONGESTION);line.addView(control,new LinearLayout.LayoutParams(dp(56),dp(48)));
                control.setOnCheckedChangeListener((view,checked)->{options.set(code,option,checked);Store.route=null;host.routePointsChanged();});content.addView(line);divider(content);
            }
        }
        LinearLayout bottom=column();bottom.setPadding(dp(16),dp(16),dp(16),0);bottom.addView(button(host.getString(R.string.action_add_country),()->{List<String> entries=Arrays.asList(CountryRouteOptions.COUNTRIES.split(";"));String[] names=new String[entries.size()];for(int i=0;i<names.length;i++)names[i]=entries.get(i).split("\\|")[0];new AlertDialog.Builder(host).setTitle(host.getString(R.string.dialog_title_country_route_options)).setItems(names,(d,index)->{options.showCountry(entries.get(index).split("\\|")[1]);routeOptions();}).show();}),new LinearLayout.LayoutParams(-1,dp(52)));
        Button access=button(host.getString(R.string.action_route_access_permissions),host::accessPermissions);LinearLayout.LayoutParams gap=new LinearLayout.LayoutParams(-1,dp(52));gap.topMargin=dp(8);bottom.addView(access,gap);content.addView(bottom);
    }
    static double[] coordinates(String value){String[] pieces=value.trim().split(",",-1);if(pieces.length!=2)throw new IllegalArgumentException("Use latitude, longitude.");double lat=Double.parseDouble(pieces[0].trim()),lon=Double.parseDouble(pieces[1].trim());if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>85||Math.abs(lon)>180)throw new IllegalArgumentException("Coordinates are outside the map's latitude/longitude range.");return new double[]{lat,lon};}
    void search(int target){
        LinearLayout root=page(target==1?host.getString(R.string.search_title_starting_point):target==2?host.getString(R.string.action_add_waypoint):target==3?host.getString(R.string.search_title_destination):target>=4?host.getString(R.string.search_title_change_waypoint):host.getString(R.string.search_title_search),false);final Dialog shown=dialog;
        LinearLayout bar=new LinearLayout(host);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setBackground(surface());bar.setPadding(dp(2),0,dp(2),0);query=new EditText(host);query.setSingleLine(true);query.setBackground(null);query.setTextSize(19);query.setTextColor(text);query.setHintTextColor(muted);query.setHint(host.getString(R.string.search_hint));query.setContentDescription(host.getString(R.string.search_content_desc));query.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH|android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);bar.addView(glyph("‹",host.getString(R.string.action_close_search),this::close),new LinearLayout.LayoutParams(dp(40),dp(52)));bar.addView(query,new LinearLayout.LayoutParams(0,dp(60),1));bar.addView(glyph("×",host.getString(R.string.action_clear_search),()->query.setText("")),new LinearLayout.LayoutParams(dp(40),dp(52)));root.addView(bar);
        if(target==1){Button gps=button(host.getString(R.string.start_point_current_position),()->{close();host.useGpsOrigin();edit();});gps.setContentDescription(host.getString(R.string.action_use_gps_content_desc));LinearLayout.LayoutParams position=new LinearLayout.LayoutParams(-1,dp(56));position.topMargin=dp(10);root.addView(gps,position);}
        LinearLayout shortcuts=new LinearLayout(host);String[] names={host.getString(R.string.shortcut_favourites),host.getString(R.string.shortcut_fuel),host.getString(R.string.shortcut_parking),host.getString(R.string.shortcut_services)};String[] kinds={"","fuel","parking","car_repair"};ArrayList<Graph.Node> points=new ArrayList<>();TextView status=label(host.getString(R.string.status_recent_searches),14,muted);status.setPadding(dp(8),dp(14),0,dp(8));
        ArrayAdapter<Graph.Node> adapter=new ArrayAdapter<Graph.Node>(host,android.R.layout.simple_list_item_2,android.R.id.text1,points){public View getView(int position,View convert,android.view.ViewGroup parent){View v=super.getView(position,convert,parent);Graph.Node p=getItem(position);TextView title=v.findViewById(android.R.id.text1),sub=v.findViewById(android.R.id.text2);title.setText(p.label.isEmpty()?host.getString(R.string.item_title_coordinates):p.label);title.setTextColor(text);title.setTextSize(18);sub.setTextColor(muted);sub.setText(String.format(Locale.ROOT,host.getString(R.string.coordinate_item_format),p.lat,p.lon));v.setMinimumHeight(dp(78));return v;}};
        for(int i=0;i<names.length;i++){final int at=i;Runnable action=()->{if(at==0){close();host.favourites();return;}int token=++generation;status.setText(host.getString(R.string.status_nearby_category,names[at].toLowerCase(Locale.ROOT)));double lat=Double.isFinite(Store.lat)?Store.lat:Store.graph.nodes[Store.start].lat,lon=Double.isFinite(Store.lon)?Store.lon:Store.graph.nodes[Store.start].lon;Store.mapWorker.execute(()->{List<Graph.Node> found=Store.display==null?Collections.emptyList():Store.display.nearby(kinds[at],lat,lon);handler.post(()->{if(token!=generation||!shown.isShowing())return;points.clear();points.addAll(found);adapter.notifyDataSetChanged();status.setText(found.isEmpty()?host.getString(R.string.status_no_places):host.getString(R.string.status_nearby_category,names[at].toLowerCase(Locale.ROOT)));});});};
            LinearLayout tile=column();tile.setGravity(Gravity.CENTER);tile.setBackground(surface());tile.setFocusable(true);tile.setContentDescription(names[i]);tile.setOnClickListener(v->action.run());
            ImageView icon=new ImageView(host);icon.setImageResource(SHORTCUT_ICONS[i]);icon.setColorFilter(accent);icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            tile.addView(icon,new LinearLayout.LayoutParams(dp(26),dp(26)));
            TextView caption=label(names[i],11,muted);caption.setGravity(Gravity.CENTER);tile.addView(caption);
            LinearLayout.LayoutParams tileSize=new LinearLayout.LayoutParams(0,dp(66),1);tileSize.rightMargin=dp(6);shortcuts.addView(tile,tileSize);}
        LinearLayout.LayoutParams shortcutRow=new LinearLayout.LayoutParams(-1,-2);shortcutRow.topMargin=dp(8);root.addView(shortcuts,shortcutRow);root.addView(status);
        TextView mapWarning=label(host.getString(R.string.search_map_warning),12,muted);mapWarning.setPadding(dp(8),dp(4),dp(8),dp(6));mapWarning.setContentDescription(host.getString(R.string.search_map_warning));root.addView(mapWarning);
        results=new ListView(host);results.setAdapter(adapter);root.addView(results,new LinearLayout.LayoutParams(-1,0,1));
        Runnable recent=()->{points.clear();try{JSONArray a=new JSONArray(host.getSharedPreferences("search_history",0).getString("places","[]"));for(int i=0;i<a.length();i++){JSONObject p=a.getJSONObject(i);points.add(new Graph.Node(p.getDouble("lat"),p.getDouble("lon"),p.getString("name")));}}catch(JSONException ignored){}adapter.notifyDataSetChanged();status.setText(points.isEmpty()?host.getString(R.string.status_empty_search):host.getString(R.string.status_recent_searches));};recent.run();
        query.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){}public void afterTextChanged(Editable e){}public void onTextChanged(CharSequence s,int st,int before,int count){int token=++generation;handler.removeCallbacksAndMessages(null);points.clear();adapter.notifyDataSetChanged();String raw=s.toString().trim();if(raw.isEmpty()){recent.run();return;}if(raw.indexOf(',')>=0&&raw.matches("[+\\-0-9.,\\s]+")){try{double[] c=coordinates(raw);points.add(new Graph.Node(c[0],c[1],raw));adapter.notifyDataSetChanged();status.setText(host.getString(R.string.status_view_coordinate));}catch(IllegalArgumentException e){status.setText(host.getString(R.string.error_invalid_coordinates));}return;}if(raw.length()<2){status.setText(host.getString(R.string.status_min_two_chars));return;}status.setText(host.getString(R.string.status_searching));DisplayDatabase display=Store.display;Graph graph=Store.graph;handler.postDelayed(()->Store.mapWorker.execute(()->{try{List<Graph.Node> found=new ArrayList<>();if(display!=null)found.addAll(display.search(raw));else for(int id:graph.search(raw))found.add(graph.nodes[id]);handler.post(()->{if(token!=generation||!shown.isShowing())return;points.clear();points.addAll(found);adapter.notifyDataSetChanged();status.setText(found.isEmpty()?host.getString(R.string.status_no_address):host.getResources().getQuantityString(R.plurals.status_mapped_places,found.size(),found.size()));});}catch(RuntimeException error){handler.post(()->{if(token==generation&&shown.isShowing())status.setText(host.getString(R.string.status_search_unavailable));});}}),250);}});
        results.setOnItemClickListener((parent,v,position,id)->{if(position>=points.size())return;Graph.Node p=points.get(position);remember(p);host.getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(query.getWindowToken(),0);close();if(target==0)host.previewPlace(p);else{host.chooseJourneyPoint(p,target==3?0:target);edit();}});
        query.setOnEditorActionListener((v,action,event)->{if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH&&points.size()==1){results.performItemClick(results.getChildAt(0),0,0);return true;}return false;});query.requestFocus();
        shown.setOnDismissListener(d->{generation++;handler.removeCallbacksAndMessages(null);});
    }
    private void remember(Graph.Node point){try{JSONArray old=new JSONArray(host.getSharedPreferences("search_history",0).getString("places","[]")),recent=new JSONArray();recent.put(new JSONObject().put("lat",point.lat).put("lon",point.lon).put("name",point.label));for(int i=0;i<old.length()&&recent.length()<15;i++){JSONObject item=old.getJSONObject(i);if(Math.abs(item.getDouble("lat")-point.lat)>.00000001||Math.abs(item.getDouble("lon")-point.lon)>.00000001)recent.put(item);}host.getSharedPreferences("search_history",0).edit().putString("places",recent.toString()).apply();}catch(JSONException ignored){}}
    private static String duration(double s){long m=Math.max(1,Math.round(s/60));return m<60?m+" min":m/60+" h "+m%60+" min";}
}
