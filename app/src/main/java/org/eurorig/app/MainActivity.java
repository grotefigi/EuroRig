package org.eurorig.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.content.res.ColorStateList;
import android.location.*;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import org.eurorig.routing.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class MainActivity extends Activity {
    private int BG,PANEL,TEXT,MUTED,LIME;
    private AppPalette palette;
    private boolean darkMode;
    private TextView status, profile, endpoints, instruction, stats, speedDisplay;
    private LinearLayout routeCard;
    private Button gpsButton, overviewButton, modeButton;
    private Button zoomInButton,zoomOutButton;
    private boolean mapGlyphs;
    private boolean guidanceWasActive;
    private Button plan, drive;
    private RoadMapView map;
    private final Handler main=new Handler(Looper.getMainLooper());
    private boolean busy, simulating, resumed;
    private boolean previouslyActive;
    private Graph displayedGraph;
    private DisplayDatabase displayedDatabase;
    private int requestGeneration, simulationEdge;
    private double simulationFraction;
    private Progress simulatedProgress;
    private Router.Route exporting;
    private LocationListener pendingGps;
    private boolean gpsAcquiring;
    private final Runnable ticker=new Runnable(){public void run(){
        if(!resumed)return;
        boolean active=simulating||Store.navigating||MapDownloadService.running;
        if(simulating) simulationTick();
        if(active||previouslyActive)refresh();
        previouslyActive=simulating||Store.navigating||MapDownloadService.running;
        main.postDelayed(this,Store.navigating?500:1500);
    }};
    public void onCreate(Bundle state){
        applyAppearance();super.onCreate(state);buildUi();
        busy=true;refresh();
        Store.worker.execute(()->{
            try {Store.load(getApplicationContext());main.post(()->{if(isDestroyed())return;busy=false;map.setGraph(Store.graph);refresh();});}
            catch(IOException|RuntimeException|LinkageError e){
                // Recover the interface while retaining the damaged region on disk.
                Store.closeNative(this);Store.graph=null;
                main.post(()->{busy=false;map.setGraph(null);refresh();error("Saved map could not load. Import or download the map again. "+e.getMessage());});
            }
        });
    }
    protected void onResume(){super.onResume();resumed=true;main.removeCallbacks(ticker);main.post(ticker);}
    protected void onPause(){resumed=false;main.removeCallbacks(ticker);cancelGps();super.onPause();}
    protected void onDestroy(){requestGeneration++;main.removeCallbacksAndMessages(null);if(simulating){Store.lat=Double.NaN;Store.lon=Double.NaN;}super.onDestroy();}
    public void onConfigurationChanged(android.content.res.Configuration configuration){
        super.onConfigurationChanged(configuration);RoadMapView old=map;boolean followingInitialized=guidanceWasActive;buildUi();if(Store.graph!=null)map.setGraph(Store.graph);map.restoreCamera(old);guidanceWasActive=followingInitialized;refresh();
    }
    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private void applyAppearance(){
        palette=new AppPalette(this);darkMode=palette.dark;
        BG=palette.background;PANEL=palette.secondary;TEXT=palette.text;MUTED=palette.muted;LIME=palette.accent;
        setTheme(darkMode?R.style.AppTheme:R.style.AppThemeLight);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(darkMode?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }
    private void appearanceDialog(){
        Switch toggle=new Switch(this);toggle.setText(R.string.dark_mode);toggle.setTextColor(TEXT);toggle.setPadding(dp(24),dp(16),dp(24),dp(16));toggle.setMinHeight(dp(56));toggle.setChecked(darkMode);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Appearance").setView(toggle).setNegativeButton("Close",null).create();
        toggle.setOnCheckedChangeListener((view,checked)->{dialog.dismiss();getSharedPreferences("settings",0).edit().putBoolean("dark_mode",checked).apply();RoadMapView old=map;boolean initialized=guidanceWasActive;applyAppearance();buildUi();map.setGraph(Store.graph);map.restoreCamera(old);guidanceWasActive=initialized;refresh();});dialog.show();
    }
    private GradientDrawable background(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextColor(color);t.setTextSize(size);t.setFontFeatureSettings("kern, tnum");return t;}
    private LinearLayout vertical(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private void feedback(View view,int color,boolean outlined){
        GradientDrawable normal=background(color,12);if(outlined)normal.setStroke(dp(1),palette.border);
        GradientDrawable focused=background(color,12);focused.setStroke(dp(2),color==LIME?palette.onAccent:color==palette.danger?Color.WHITE:LIME);
        StateListDrawable states=new StateListDrawable();states.addState(new int[]{android.R.attr.state_focused},focused);states.addState(new int[]{},normal);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(darkMode?0x40ffffff:0x250f172a),states,background(Color.WHITE,12)));
    }
    private void actionStyle(Button view,int color,int foreground,boolean outlined){
        if(Integer.valueOf(color).equals(view.getTag()))return;
        feedback(view,color,outlined);view.setTextColor(foreground);view.setTag(color);
    }
    private void icon(Button view,int resource,boolean above){
        android.graphics.drawable.Drawable drawable=getDrawable(resource).mutate();drawable.setTint(view.getCurrentTextColor());drawable.setBounds(0,0,dp(20),dp(20));
        view.setCompoundDrawablePadding(dp(4));
        view.setCompoundDrawablesRelative(above?null:drawable,above?drawable:null,null,null);
    }
    private void tappableLabel(TextView view){
        view.setFocusable(true);feedback(view,PANEL,true);
        view.setAccessibilityDelegate(new View.AccessibilityDelegate(){public void onInitializeAccessibilityNodeInfo(View host,android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName(Button.class.getName());}});
    }
    private Button button(String value,Runnable action){Button b=new Button(this);b.setText(value);b.setTextSize(14);b.setAllCaps(false);b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));b.setTextColor(TEXT);feedback(b,PANEL,true);b.setStateListAnimator(null);b.setPadding(dp(12),dp(4),dp(12),dp(4));b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(dp(48));b.setOnClickListener(v->action.run());return b;}
    private void buildUi(){
        guidanceWasActive=false;
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v,insets)->{root.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        setContentView(root);root.requestApplyInsets();
        map=new RoadMapView(this);map.pick=n->{if(editable())choosePoint(n);};map.cameraChanged=this::updateMapControls;
        root.addView(map,new FrameLayout.LayoutParams(-1,-1));
        boolean wide=getResources().getConfiguration().screenWidthDp>getResources().getConfiguration().screenHeightDp;
        boolean largeText=getResources().getConfiguration().fontScale>1.3;
        mapGlyphs=largeText||getResources().getConfiguration().screenWidthDp<400;
        int panelWidth=wide?dp(Math.min(330,Math.max(240,getResources().getConfiguration().screenWidthDp*.42f))):-1;
        LinearLayout top=vertical();top.setPadding(dp(12),dp(12),dp(12),dp(12));top.setBackground(background(palette.surface,20));top.setElevation(dp(4));
        FrameLayout.LayoutParams topPosition=new FrameLayout.LayoutParams(panelWidth,-2,Gravity.TOP|Gravity.START);topPosition.setMargins(dp(8),dp(8),dp(8),0);root.addView(top,topPosition);
        LinearLayout brandRow=new LinearLayout(this);brandRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand=text("EuroRig",19,TEXT);brand.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));brandRow.addView(brand,new LinearLayout.LayoutParams(-2,-2));
        status=text("Opening maps…",12,MUTED);status.setGravity(Gravity.END);status.setPadding(dp(12),0,0,0);brandRow.addView(status,new LinearLayout.LayoutParams(0,-2,1));top.addView(brandRow);
        routeCard=vertical();top.addView(routeCard);
        Button search=button("Search destination or coordinates",()->{if(Store.graph==null)mapsDialog();else search(false);});search.setTextSize(15);search.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);icon(search,R.drawable.ic_search,false);LinearLayout.LayoutParams searchSize=new LinearLayout.LayoutParams(-1,-2);searchSize.topMargin=dp(12);search.setMinHeight(dp(56));routeCard.addView(search,searchSize);
        LinearLayout profileRow=new LinearLayout(this);profileRow.setOrientation(largeText?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);
        profile=text("Your truck",14,TEXT);profile.setGravity(Gravity.CENTER_VERTICAL);profile.setPadding(dp(12),dp(6),dp(4),dp(6));profile.setContentDescription("Edit vehicle dimensions and ADR profile");profile.setOnClickListener(v->{if(canChangeMap())truckDialog();});tappableLabel(profile);LinearLayout.LayoutParams profileSize=new LinearLayout.LayoutParams(largeText?-1:0,-2,largeText?0:1);if(!largeText)profileSize.rightMargin=dp(8);profile.setMinHeight(dp(48));profileRow.addView(profile,profileSize);
        modeButton=button("Economical",this::routingOptions);LinearLayout.LayoutParams modeSize=new LinearLayout.LayoutParams(largeText?-1:dp(122),-2);if(largeText)modeSize.topMargin=dp(8);profileRow.addView(modeButton,modeSize);LinearLayout.LayoutParams profileRowSize=new LinearLayout.LayoutParams(-1,-2);profileRowSize.topMargin=dp(8);routeCard.addView(profileRow,profileRowSize);
        instruction=text("Choose a destination",18,TEXT);instruction.setTypeface(Typeface.DEFAULT,Typeface.BOLD);instruction.setPadding(dp(8),dp(4),dp(8),dp(4));top.addView(instruction);
        LinearLayout bottom=vertical();bottom.setPadding(dp(12),dp(12),dp(12),dp(4));bottom.setBackground(background(palette.surface,20));bottom.setElevation(dp(4));
        FrameLayout.LayoutParams bottomPosition=new FrameLayout.LayoutParams(panelWidth,-2,Gravity.BOTTOM|Gravity.START);bottomPosition.setMargins(dp(8),0,dp(8),dp(8));root.addView(bottom,bottomPosition);
        endpoints=text("Choose route endpoints",14,TEXT);endpoints.setGravity(Gravity.CENTER_VERTICAL);endpoints.setPadding(dp(12),dp(8),dp(12),dp(8));endpoints.setLineSpacing(dp(4),1);endpoints.setMinHeight(dp(56));endpoints.setContentDescription("Edit starting point and destination");endpoints.setOnClickListener(v->planner());tappableLabel(endpoints);bottom.addView(endpoints,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout journey=new LinearLayout(this);journey.setGravity(Gravity.CENTER_VERTICAL);
        speedDisplay=text("—\nkm/h",30,TEXT);speedDisplay.setTypeface(Typeface.DEFAULT,Typeface.BOLD);speedDisplay.setGravity(Gravity.CENTER);speedDisplay.setBackground(background(PANEL,12));speedDisplay.setMinHeight(dp(80));journey.addView(speedDisplay,new LinearLayout.LayoutParams(dp(100),-2));
        stats=text("Download your first country to begin",14,MUTED);stats.setPadding(dp(8),dp(8),dp(8),dp(8));journey.addView(stats,new LinearLayout.LayoutParams(0,-2,1));bottom.addView(journey);
        LinearLayout actions=new LinearLayout(this);plan=button("Plan route",this::calculate);drive=button("Start guidance",this::startGuidance);
        LinearLayout.LayoutParams actionSize=new LinearLayout.LayoutParams(0,-2,1);actionSize.rightMargin=dp(8);plan.setMinHeight(dp(52));drive.setMinHeight(dp(52));actions.addView(plan,actionSize);actions.addView(drive,new LinearLayout.LayoutParams(0,-2,1));bottom.addView(actions);
        LinearLayout dock=new LinearLayout(this);String[] labels={"Truck","Maps","Route","More"};Runnable[] callbacks={()->{if(canChangeMap())truckDialog();},this::mapsDialog,this::routeDialog,this::moreDialog};
        int[] dockIcons={R.drawable.ic_vehicle,R.drawable.ic_maps,R.drawable.ic_route,R.drawable.ic_more};
        for(int i=0;i<labels.length;i++){Button control=button(labels[i],callbacks[i]);feedback(control,palette.surface,false);control.setPadding(dp(4),dp(4),dp(4),dp(4));control.setTextSize(12);icon(control,dockIcons[i],true);dock.addView(control,new LinearLayout.LayoutParams(0,-2,1));}LinearLayout.LayoutParams dockSize=new LinearLayout.LayoutParams(-1,-2);dockSize.topMargin=dp(8);bottom.addView(dock,dockSize);
        LinearLayout controls=vertical();gpsButton=button("Use GPS",this::mapGps);overviewButton=button("Overview",()->{if(Store.route!=null)map.fitRoute(Store.route);else map.fit();updateMapControls();});
        for(Button control:new Button[]{gpsButton,overviewButton}){control.setTextSize(mapGlyphs?0:12);LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(dp(mapGlyphs?48:104),dp(48));size.bottomMargin=dp(8);controls.addView(control,size);}
        if(mapGlyphs){gpsButton.setText("");icon(gpsButton,R.drawable.ic_location,true);overviewButton.setText("");icon(overviewButton,R.drawable.ic_overview,true);}overviewButton.setContentDescription("Overview");
        LinearLayout zoom=new LinearLayout(this);Button plus=button("+",()->map.zoom(1.6)),minus=button("−",()->map.zoom(1/1.6));plus.setContentDescription("Zoom in");minus.setContentDescription("Zoom out");plus.setTextSize(24);minus.setTextSize(24);LinearLayout.LayoutParams plusSize=new LinearLayout.LayoutParams(dp(48),dp(48));plusSize.rightMargin=dp(8);zoom.addView(plus,plusSize);zoom.addView(minus,new LinearLayout.LayoutParams(dp(48),dp(48)));controls.addView(zoom,new LinearLayout.LayoutParams(-2,-2));
        zoomInButton=plus;zoomOutButton=minus;
        FrameLayout.LayoutParams controlsPosition=new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER_VERTICAL|Gravity.END);controlsPosition.setMargins(0,0,dp(16),0);root.addView(controls,controlsPosition);
        View sidebarView=null;
        if(wide){
            root.removeView(top);root.removeView(bottom);LinearLayout sidebar=vertical();sidebar.addView(top,new LinearLayout.LayoutParams(-1,-2));sidebar.addView(new View(this),new LinearLayout.LayoutParams(1,0,1));sidebar.addView(bottom,new LinearLayout.LayoutParams(-1,-2));
            ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(sidebar);FrameLayout.LayoutParams position=new FrameLayout.LayoutParams(panelWidth,-1,Gravity.START);position.setMargins(dp(8),dp(8),dp(8),dp(8));root.addView(scroll,position);sidebarView=scroll;
        }
        RoadMapView currentMap=map;
        View sidebar=sidebarView;boolean[] compactControls={false};
        root.getViewTreeObserver().addOnGlobalLayoutListener(()->{
            boolean compact=!wide&&bottom.getTop()-top.getBottom()<dp(180);
            currentMap.setViewport(wide?sidebar.getRight()-currentMap.getLeft()+dp(8):0,wide?0:top.getBottom()-currentMap.getTop()+dp(8),currentMap.getWidth()-(compact?0:dp(112)),wide?currentMap.getHeight():bottom.getTop()-currentMap.getTop()-dp(8));
            if(compact!=compactControls[0]){compactControls[0]=compact;controls.setOrientation(compact?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);for(Button control:new Button[]{gpsButton,overviewButton}){LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(dp(mapGlyphs?48:104),dp(48));if(compact)size.rightMargin=dp(8);else size.bottomMargin=dp(8);control.setLayoutParams(size);}}
            if(!wide){FrameLayout.LayoutParams position=(FrameLayout.LayoutParams)controls.getLayoutParams();int margin=Math.max(0,compact&&(Store.navigating||simulating)?top.getBottom()-root.getPaddingTop()+dp(8):(top.getBottom()+bottom.getTop()-controls.getHeight())/2-root.getPaddingTop());if(position.gravity!=(Gravity.TOP|Gravity.END)||position.topMargin!=margin){position.gravity=Gravity.TOP|Gravity.END;position.topMargin=margin;controls.setLayoutParams(position);}}
        });
    }
    private void planner(){
        if(!editable())return;
        new AlertDialog.Builder(this).setTitle("Route planner").setItems(new String[]{"From: "+(Store.originChosen?label(Store.start):"Choose starting point"),"To: "+(Store.destinationChosen?label(Store.end):"Choose destination"),"Start at GPS","Swap start and destination","Route preferences"},(d,i)->{
            if(i==0)search(true);else if(i==1)search(false);else if(i==2)startAtGps();else if(i==3){int previous=Store.start;Store.start=Store.end;Store.end=previous;boolean chosen=Store.originChosen;Store.originChosen=Store.destinationChosen;Store.destinationChosen=chosen;Store.route=null;saveEndpoints();refresh();}else routingOptions();
        }).setNegativeButton("Close",null).show();
    }
    private void saveEndpoints(){Store.saveEndpoints(this);}
    private void selectedPoint(int node,boolean starting){
        if(node<0){error("That point is outside the installed map. Choose a point within "+Store.graph.name+".");return;}
        if(starting){cancelGps();Store.start=node;Store.originChosen=true;}else{Store.end=node;Store.destinationChosen=true;}Store.route=null;saveEndpoints();
        Graph.Node point=Store.graph.nodes[node];map.showPoint(point.lat,point.lon);refresh();
    }
    private void refresh(){
        Graph g=Store.graph;boolean active=Store.navigating||simulating;
        boolean compact=(getResources().getConfiguration().screenHeightDp<560||getResources().getConfiguration().fontScale>1.3)&&getResources().getConfiguration().screenHeightDp>getResources().getConfiguration().screenWidthDp;
        instruction.setVisibility(compact&&!active?View.GONE:View.VISIBLE);status.setVisibility(compact&&!active&&!busy&&!MapDownloadService.running?View.GONE:View.VISIBLE);
        routeCard.setVisibility(active?View.GONE:View.VISIBLE);
        speedDisplay.setVisibility(active?View.VISIBLE:View.GONE);
        instruction.setTextSize(active?24:18);
        plan.setVisibility(active?View.GONE:View.VISIBLE);
        boolean fresh=simulating||(Store.fixTime>0&&SystemClock.elapsedRealtime()-Store.fixTime<15000);
        speedDisplay.setText(getString(R.string.speed_display,fresh?String.format(Locale.getDefault(),"%.0f",Store.speed):"—"));
        speedDisplay.setContentDescription(fresh?Math.round(Store.speed)+" kilometres per hour":"Speed unavailable: waiting for GPS");
        if(displayedGraph!=g){
            if(displayedGraph==null||Store.display!=displayedDatabase||Store.nativeRouter==null)map.setGraph(g);else map.updateGraph(g);
            displayedGraph=g;displayedDatabase=Store.display;
        }
        plan.setEnabled(!busy&&!Store.installing&&!active);drive.setEnabled(!busy&&!Store.installing&&Store.route!=null);
        plan.setAlpha(plan.isEnabled()?1:.45f);drive.setAlpha(drive.isEnabled()?1:.45f);
        actionStyle(plan,Store.route==null?LIME:PANEL,Store.route==null?palette.onAccent:TEXT,Store.route!=null);
        actionStyle(drive,active?palette.danger:Store.route==null?PANEL:LIME,active?Color.WHITE:Store.route==null?TEXT:palette.onAccent,Store.route==null&&!active);
        plan.setText(busy?"Working…":g==null?"Download maps":"Plan route");drive.setText(active?"Stop":g!=null&&g.demo?"Simulate route":"Start guidance");
        modeButton.setText(Store.deliveryAccess?"Delivery":Store.mode.title);modeButton.setContentDescription("Route preferences: "+Store.mode.title+(Store.deliveryAccess?", permitted delivery access":""));
        profile.setText(String.format(Locale.getDefault(),"%.1f t  ·  %.2f m high",Store.truck.weight,Store.truck.height));
        if(g==null){status.setText(busy?"Opening maps…":MapDownloadService.running?MapDownloadService.status:"No maps installed");endpoints.setText(R.string.download_country_start);endpoints.setVisibility(View.VISIBLE);instruction.setText(R.string.first_country);stats.setText(R.string.choose_first_map);map.updateGraph(null);map.invalidate();updateMapControls();return;}
        status.setText(MapDownloadService.running?MapDownloadService.status:g.demo?"DEVELOPMENT BUILD · Fictional training map":Store.navigating?"GPS GUIDANCE · Offline · Development build":"Offline · "+g.name);
        endpoints.setText(getString(R.string.route_endpoints,Store.originChosen?label(Store.start):"Choose starting point",Store.destinationChosen?label(Store.end):"Choose destination"));endpoints.setVisibility(active?View.GONE:View.VISIBLE);
        Router.Route r=Store.route;
        if(active){instruction.setText(simulating?getString(R.string.simulated_instruction,Store.guidance):Store.guidance);stats.setText(String.format(Locale.getDefault(),"%.1f km remaining%s\n%s",Store.remaining/1000,simulating?" · Fictional roads":"",fresh?"GPS position received":"Waiting for a fresh GPS fix"));}
        else if(r!=null){instruction.setText(Store.arrived?(g.demo?"Training route complete":"You have arrived"):r.edges.isEmpty()?"Start and destination are the same":"Route ready for your truck");stats.setText(String.format(Locale.getDefault(),"%.1f km  ·  ≈ %s  ·  %s",r.metres/1000,duration(r.seconds),r.nativeGeometry()?(Store.deliveryAccess?"Shortest delivery":Store.mode.title)+(r.restrictedMetres>0?" · "+Math.round(r.restrictedMetres)+" m restricted access":""):"Tagged restrictions checked"));}
        else{instruction.setText(R.string.select_destination);stats.setText(R.string.choose_destination);}
        map.updateGraph(Store.graph);
        if(active&&!guidanceWasActive&&fresh){map.locate(Store.lat,Store.lon);guidanceWasActive=true;}
        if(active&&fresh)map.updatePosition();else map.invalidate();
        if(!active)guidanceWasActive=false;updateMapControls();
        if(active)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private String label(int node){if(Store.graph==null)return "";Graph.Node n=Store.graph.nodes[node];return n.label.isEmpty()?String.format(Locale.ROOT,"%.5f, %.5f",n.lat,n.lon):n.label;}
    private String duration(double seconds){long minutes=Math.max(1,Math.round(seconds/60));return minutes<60?String.format(Locale.getDefault(),"%d min",minutes):String.format(Locale.getDefault(),"%d h %02d min",minutes/60,minutes%60);}
    private boolean canChangeMap(){if(busy||Store.installing){Toast.makeText(this,"Please wait for the map or route to finish loading",Toast.LENGTH_SHORT).show();return false;}if(Store.navigating||simulating){error("Stop guidance before changing the route, map or truck.");return false;}return true;}
    private boolean editable(){if(busy||Store.installing){Toast.makeText(this,"Please wait for the map or route to finish loading",Toast.LENGTH_SHORT).show();return false;}if(Store.navigating||simulating){error("Stop guidance before changing the route, map or truck.");return false;}if(Store.graph==null){mapsDialog();return false;}return true;}
    private void choosePoint(int node){new AlertDialog.Builder(this).setTitle(label(node)).setItems(new String[]{"Set destination","Set starting point","Save favourite"},(d,i)->{if(i==2){saveFavourite(node);return;}selectedPoint(node,i==1);}).show();}
    private void search(boolean starting){
        if(!editable())return;
        LinearLayout box=vertical();box.setPadding(dp(16),dp(8),dp(16),0);
        EditText query=new EditText(this);query.setSingleLine();query.setMinHeight(dp(48));query.setHint("Place, street or latitude, longitude");query.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH|android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);box.addView(query);
        TextView message=text("Search the installed country: "+Store.graph.name,13,MUTED);box.addView(message);
        ListView list=new ListView(this);box.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        ArrayList<Graph.Node> results=new ArrayList<>();java.util.concurrent.atomic.AtomicInteger generation=new java.util.concurrent.atomic.AtomicInteger();Runnable[] pending={null};
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,new ArrayList<>());list.setAdapter(adapter);
        Dialog dialog=new Dialog(this);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        TextView title=text(starting?"Choose starting point":"Search offline map",20,TEXT);title.setPadding(0,dp(8),0,dp(12));box.addView(title,0);
        box.addView(button("Cancel",dialog::dismiss),new LinearLayout.LayoutParams(-1,dp(48)));box.setBackgroundColor(BG);dialog.setContentView(box);
        query.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void afterTextChanged(Editable e){}
            public void onTextChanged(CharSequence text,int start,int before,int count){
                int token=generation.incrementAndGet();if(pending[0]!=null)main.removeCallbacks(pending[0]);results.clear();adapter.clear();String raw=text.toString().trim();
                if(raw.length()<2){message.setText(R.string.search_minimum);return;}
                if(raw.matches("[+\\-0-9.\\s]+,[+\\-0-9.\\s]*")){
                    try{String[] values=raw.split(",",-1);if(values.length!=2)throw new NumberFormatException();double lat=Double.parseDouble(values[0].trim()),lon=Double.parseDouble(values[1].trim());
                        if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>85||Math.abs(lon)>180)throw new NumberFormatException();
                        results.add(new Graph.Node(lat,lon,""));adapter.add(String.format(Locale.ROOT,"%.5f, %.5f",lat,lon));message.setText(R.string.select_coordinate);
                    }catch(NumberFormatException e){message.setText(R.string.coordinate_format);}return;
                }
                message.setText(R.string.search_working);DisplayDatabase display=Store.display;Graph graph=Store.graph;
                pending[0]=()->Store.mapWorker.execute(()->{
                    if(token!=generation.get())return;
                    try{ArrayList<Graph.Node> found=new ArrayList<>();if(display!=null)found.addAll(display.search(raw));else for(int id:graph.search(raw))found.add(graph.nodes[id]);
                        main.post(()->{if(token!=generation.get()||!dialog.isShowing())return;results.clear();results.addAll(found);adapter.clear();for(Graph.Node point:found)adapter.add(point.label+String.format(Locale.ROOT,"\n%.5f, %.5f",point.lat,point.lon));message.setText(found.isEmpty()?"No results. Try a city, street or coordinates.":found.size()+" places in "+graph.name);});
                    }catch(RuntimeException failure){main.post(()->{if(token==generation.get()&&dialog.isShowing())message.setText(R.string.search_failed);});}
                });main.postDelayed(pending[0],250);
            }
        });
        list.setOnItemClickListener((parent,view,position,id)->{
            if(position>=results.size())return;Graph.Node point=results.get(position);int node=Store.coordinate(point.lat,point.lon,point.label);
            if(node<0){message.setText(getString(R.string.coordinate_outside,Store.graph.name));return;}
            getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(query.getWindowToken(),0);dialog.dismiss();selectedPoint(node,starting);
        });
        dialog.setOnDismissListener(d->{generation.incrementAndGet();if(pending[0]!=null)main.removeCallbacks(pending[0]);});dialog.show();
        dialog.getWindow().setLayout(-1,Math.round(getResources().getDisplayMetrics().heightPixels*.8f));dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);query.requestFocus();
        // Fixed dialog heights can extend beneath the keyboard on recent Android versions.
        box.getViewTreeObserver().addOnGlobalLayoutListener(()->{
            android.graphics.Rect visible=new android.graphics.Rect();box.getWindowVisibleDisplayFrame(visible);
            int height=Math.min(Math.round(getResources().getDisplayMetrics().heightPixels*.8f),visible.height()-dp(32));
            if(dialog.isShowing()&&height>dp(160)&&dialog.getWindow().getAttributes().height!=height)dialog.getWindow().setLayout(-1,height);
        });
    }
    private void truckDialog(){
        ScrollView scroll=new ScrollView(this);LinearLayout box=vertical();box.setPadding(dp(20),dp(8),dp(20),dp(8));scroll.addView(box);
        box.setFocusableInTouchMode(true);box.requestFocus();
        Truck t=Store.truck;String[] names={"Height (m)","Width (m)","Length (m)","Loaded gross weight (t)","Maximum loaded axle weight (t)","Total truck and trailer axles","Maximum speed (km/h)"};double[] values={t.height,t.width,t.length,t.weight,t.axleWeight,t.axles,t.topSpeed};EditText[] fields=new EditText[7];
        for(int i=0;i<7;i++){box.addView(text(names[i],13,MUTED));fields[i]=new EditText(this);fields[i].setContentDescription(names[i]);fields[i].setSelectAllOnFocus(true);fields[i].setSingleLine();fields[i].setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);fields[i].setMinHeight(dp(48));fields[i].setText(java.math.BigDecimal.valueOf(values[i]).stripTrailingZeros().toPlainString());box.addView(fields[i]);}
        String[] options={"General hazardous material","Avoid toll roads","Avoid ferries","Avoid unpaved roads"};boolean[] checks={(t.hazardousLoad&1)!=0,t.avoidTolls,t.avoidFerries,t.avoidUnpaved};CheckBox[] boxes=new CheckBox[4];
        for(int i=0;i<4;i++){boxes[i]=new CheckBox(this);boxes[i].setMinHeight(dp(48));boxes[i].setText(options[i]);boxes[i].setChecked(checks[i]);box.addView(boxes[i]);}
        CheckBox water=new CheckBox(this),explosives=new CheckBox(this);
        water.setMinHeight(dp(48));water.setText(R.string.load_water);water.setChecked((t.hazardousLoad&2)!=0);box.addView(water);
        explosives.setMinHeight(dp(48));explosives.setText(R.string.load_explosives);explosives.setChecked((t.hazardousLoad&4)!=0);box.addView(explosives);
        box.addView(text("ADR tunnel restriction code for this load",13,MUTED));
        Spinner tunnel=new Spinner(this);tunnel.setContentDescription("ADR tunnel restriction code");tunnel.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"No tunnel code","B · Exclude B, C, D, E","C · Exclude C, D, E","D · Exclude D, E","E · Exclude E"}));tunnel.setSelection(t.tunnelCode==0?0:t.tunnelCode-1);box.addView(tunnel);
        box.addView(text("Use the code applicable to the actual consignment. Mixed and quantity-dependent codes must be resolved from the transport documents. Mapped unknown tunnel categories are excluded when a code is selected.",12,MUTED));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Your truck").setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();dialog.show();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{
            double[] n=new double[7];for(int i=0;i<7;i++)n[i]=Double.parseDouble(fields[i].getText().toString().replace(',','.'));
            if(n[5]!=Math.rint(n[5]))throw new IllegalArgumentException("Axle count must be a whole number.");
            int load=(boxes[0].isChecked()?1:0)|(water.isChecked()?2:0)|(explosives.isChecked()?4:0),code=tunnel.getSelectedItemPosition()==0?0:tunnel.getSelectedItemPosition()+1;
            Store.saveTruck(this,new Truck(n[0],n[1],n[2],n[3],n[4],load!=0,boxes[1].isChecked(),boxes[2].isChecked(),boxes[3].isChecked(),(int)n[5],n[6],load,code));dialog.dismiss();refresh();
        }catch(IllegalArgumentException e){error(e instanceof NumberFormatException?"Enter all measurements as numbers.":e.getMessage());}});
    }
    private void routingOptions(){
        if(!canChangeMap())return;
        LinearLayout box=vertical();box.setPadding(dp(20),dp(8),dp(20),dp(8));RadioGroup choices=new RadioGroup(this);
        for(RoutingMode option:RoutingMode.values()){
            RadioButton choice=new RadioButton(this);choice.setMinHeight(dp(48));choice.setId(900+option.ordinal());
            choice.setText(getString(R.string.routing_choice,option.title,option==RoutingMode.SHORTEST?"Prioritize distance":option==RoutingMode.EASIEST?"Favour fewer turns":"Strongly favour highways"));choices.addView(choice);
        }
        choices.check(900+Store.mode.ordinal());box.addView(choices);
        CheckBox access=new CheckBox(this);access.setMinHeight(dp(48));access.setText(R.string.delivery_permission);access.setChecked(Store.deliveryAccess);box.addView(access);
        box.addView(text("Delivery access is limited to 2 km from the destination. It uses shortest routing and preserves height, width, length, gross/axle weight, ADR and turn restrictions. Unknown limits, barriers and bridges on restricted access are excluded. Permission applies to this trip on this device.",12,MUTED));
        ScrollView scroll=new ScrollView(this);scroll.addView(box);
        new AlertDialog.Builder(this).setTitle("Route preferences").setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Save",(d,w)->{
            Store.mode=RoutingMode.values()[choices.indexOfChild(choices.findViewById(choices.getCheckedRadioButtonId()))];Store.deliveryAccess=access.isChecked();Store.route=null;
            getSharedPreferences("settings",0).edit().putString("routing_mode",Store.mode.name()).apply();refresh();
        }).show();
    }
    private void calculate(){
        if(!editable())return;
        if(!Store.originChosen||!Store.destinationChosen){planner();return;}
        if(pendingGps!=null&&!gpsAcquiring&&Store.fixTime>0&&SystemClock.elapsedRealtime()-Store.fixTime<15000){int current=Store.coordinate(Store.lat,Store.lon);if(current<0){error("Your location is outside the installed map.");return;}Store.start=current;saveEndpoints();}
        final Graph graph=Store.graph;final int start=Store.start,end=Store.end;final Truck truck=Store.truck;final int generation=++requestGeneration;
        busy=true;Store.route=null;refresh();Store.worker.execute(()->{
            try{Router.Route route=Store.calculate(graph,start,end,truck);main.post(()->{if(generation!=requestGeneration||isDestroyed())return;Store.route=route;Store.arrived=false;busy=false;if(route.nativeGeometry())map.fitRoute(route);refresh();});}
            catch(RuntimeException|LinkageError e){main.post(()->{if(generation!=requestGeneration||isDestroyed())return;Store.route=null;busy=false;refresh();error("No route could be planned for this truck. Check the endpoints, installed country and vehicle limits.\n\n"+e.getMessage());});}
        });
    }
    private void startGuidance(){
        if(Store.navigating||simulating){stopGuidance();return;}
        if(Store.installing)return;
        if(Store.route==null||Store.route.edges.isEmpty()){error("Plan a route with different endpoints first.");return;}
        if(Store.graph.demo){simulating=true;Store.arrived=false;Store.travelled=0;Store.progressRoute=Store.route;simulationEdge=0;simulationFraction=0;simulatedProgress=new Progress(Store.route);Store.speed=60;Store.remaining=Store.route.metres;Store.guidance="Training route. These roads are fictional.";refresh();return;}
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(Build.VERSION.SDK_INT>=33?new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.POST_NOTIFICATIONS}:new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},100);return;
        }
        LocationManager manager=getSystemService(LocationManager.class);
        if(!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)){error("Enable device location, then start guidance.");return;}
        // The planned origin must match the driver's current location before guidance.
        Location fix=manager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        if(fix==null||!fix.hasAccuracy()||fix.getAccuracy()>50||SystemClock.elapsedRealtimeNanos()-fix.getElapsedRealtimeNanos()>30_000_000_000L){
            error("A fresh GPS fix is needed. Tap Use GPS, then plan the route.");return;
        }
        Graph.Node origin=Store.graph.nodes[Store.start];
        if(Geo.distance(fix.getLatitude(),fix.getLongitude(),origin.lat,origin.lon)>250){error("Your route starts too far from your location. Tap Use GPS, then replan.");return;}
        new AlertDialog.Builder(this).setTitle("Development navigation").setMessage("This prototype checks a limited set of mapped restrictions. Missing map data can hide restrictions. It has not passed road validation. Use road signs and an approved route plan. Voice requires an installed offline voice.")
            .setNegativeButton("Cancel",null).setPositiveButton("Start",(d,w)->{cancelGps();Store.lat=fix.getLatitude();Store.lon=fix.getLongitude();Store.fixTime=SystemClock.elapsedRealtime()-(SystemClock.elapsedRealtimeNanos()-fix.getElapsedRealtimeNanos())/1_000_000;Store.bearing=fix.hasBearing()&&fix.hasSpeed()&&fix.getSpeed()>=1?fix.getBearing():Double.NaN;map.locate(Store.lat,Store.lon);Store.guidance="Waiting for GPS";startForegroundService(new Intent(this,NavigationService.class));previouslyActive=true;}).show();
    }
    public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(request==100){if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)startGuidance();else error("Precise location is needed for GPS guidance. Route planning still works offline.");}else if(request==101){if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)startAtGps();else error("Precise location is needed for GPS. You can still choose endpoints manually.");}}
    private void stopGuidance(){simulating=false;stopService(new Intent(this,NavigationService.class));Store.navigating=false;Store.lat=Double.NaN;Store.lon=Double.NaN;Store.bearing=Double.NaN;Store.fixTime=0;refresh();}
    private void simulationTick(){
        Router.Route route=Store.route;if(route==null||simulationEdge>=route.edges.size()){simulating=false;return;}
        Graph.Edge edge=route.edges.get(simulationEdge);simulationFraction+=120/Math.max(1,edge.metres);
        if(simulationFraction>=1){simulationFraction=0;simulationEdge++;if(simulationEdge>=route.edges.size()){simulating=false;Store.arrived=true;Store.guidance="Simulation complete";return;}edge=route.edges.get(simulationEdge);}
        Graph.Node a=route.graph.nodes[edge.from],b=route.graph.nodes[edge.to];Store.lat=a.lat+(b.lat-a.lat)*simulationFraction;Store.lon=a.lon+(b.lon-a.lon)*simulationFraction;Store.bearing=Geo.bearing(a,b);Store.fixTime=SystemClock.elapsedRealtime();
        Progress.Fix fix=simulatedProgress.update(Store.lat,Store.lon);Store.remaining=fix.remaining;Store.travelled=route.metres-fix.remaining;Store.guidance=Math.round(fix.toManeuver)+" m · "+route.instruction(fix.maneuver);
    }
    private void mapsDialog(){
        TextView progress=text(MapDownloadService.status,14,TEXT);progress.setPadding(dp(24),dp(12),dp(24),dp(12));
        Dialog dialog=new Dialog(this);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);LinearLayout box=vertical();box.setBackgroundColor(BG);box.setPadding(dp(16),dp(12),dp(16),dp(12));TextView title=text("Offline maps",20,TEXT);box.addView(title);progress.setMaxLines(4);box.addView(progress);
        ListView list=new ListView(this);list.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,new String[]{"Download Romania","Choose another country","Download all Europe","Pause downloads","Downloaded countries","Import country (.eurorig)","Map download source","Installed map details"}));box.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout actions=new LinearLayout(this);actions.addView(button("Download status",()->{dialog.dismiss();error(MapDownloadService.status);}),new LinearLayout.LayoutParams(0,-2,1));actions.addView(button("Close",dialog::dismiss),new LinearLayout.LayoutParams(0,-2,1));box.addView(actions);dialog.setContentView(box);
        list.setOnItemClickListener((parent,view,w,id)->{dialog.dismiss();
                if(w==0)download("romania");
                else if(w==1)countryCatalogue();
                else if(w==2)download(null);
                else if(w==3){if(MapDownloadService.running)startService(new Intent(this,MapDownloadService.class).setAction("PAUSE"));else Toast.makeText(this,"No download is running",Toast.LENGTH_SHORT).show();}
                else if(w==4)downloadedCountries();
                else if(w==5){if(canChangeMap())startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),202);}
                else if(w==6)mapSource();
                else{Graph g=Store.graph;if(g==null){error("No maps installed yet.");return;}
                    new AlertDialog.Builder(this).setTitle(g.name).setMessage(g.date+"\n"+g.attribution+"\n\nOffline truck routes, roads and place search. Only the selected country's coverage is active in this development build. Seamless cross-border routing is still being developed.\n\nCheck road signs. Truck-law validation and time-dependent restrictions are unfinished.").setPositiveButton("Close",null).show();}
        });dialog.show();dialog.getWindow().setLayout(-1,Math.round(getResources().getDisplayMetrics().heightPixels*.8f));
        Runnable tick=new Runnable(){public void run(){if(dialog.isShowing()){progress.setText(MapDownloadService.status);main.postDelayed(this,1000);}}};main.post(tick);dialog.setOnDismissListener(d->main.removeCallbacks(tick));
    }
    private void download(String country){
        if(MapDownloadService.running){Toast.makeText(this,"A download is already running. Pause it before starting another.",Toast.LENGTH_LONG).show();return;}
        if(getSharedPreferences("maps",0).getString("catalog",BuildConfig.MAP_CATALOG_URL).isEmpty()){error("A public map download source has not been published yet. Import the Romania country package, or set a catalogue URL under Map download source.");return;}
        Intent intent=new Intent(this,MapDownloadService.class);if(country!=null)intent.putExtra("country",country);startForegroundService(intent);previouslyActive=true;
        Toast.makeText(this,"Download started. Progress is shown in notifications and Offline maps.",Toast.LENGTH_LONG).show();
    }
    private void countryCatalogue(){
        String url=getSharedPreferences("maps",0).getString("catalog",BuildConfig.MAP_CATALOG_URL);
        if(url.isEmpty()){error("Romania is the first test country. Other countries appear here as packages are published. Configure a map download source or import a package.");return;}
        Toast.makeText(this,"Loading available countries…",Toast.LENGTH_SHORT).show();
        Store.mapWorker.execute(()->{try{
            org.json.JSONObject catalogue=new org.json.JSONObject(new String(new org.eurorig.maps.DownloadClient(BuildConfig.DEBUG).catalog(url),StandardCharsets.UTF_8));
            org.json.JSONArray entries=catalogue.getJSONArray("maps");String[] names=new String[entries.length()],ids=new String[entries.length()];
            for(int i=0;i<names.length;i++){org.json.JSONObject entry=entries.getJSONObject(i);names[i]=entry.getString("name")+" · "+Math.round(entry.getLong("bytes")/1048576.0)+" MB";ids[i]=entry.getString("id");}
            main.post(()->{if(!isDestroyed())new AlertDialog.Builder(this).setTitle("Download a country").setItems(names,(d,w)->download(ids[w])).setNegativeButton("Close",null).show();});
        }catch(Exception e){main.post(()->error("Map catalogue: "+e.getMessage()));}});
    }
    private void mapSource(){
        EditText field=new EditText(this);field.setSingleLine();field.setHint("https://…/catalog.json");field.setText(getSharedPreferences("maps",0).getString("catalog",BuildConfig.MAP_CATALOG_URL));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Map download source").setMessage("Use a EuroRig map catalogue hosted by the project or a community mirror.").setView(field).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{String address=field.getText().toString().trim();if(!address.isEmpty())new org.eurorig.maps.DownloadClient(BuildConfig.DEBUG).checked(address);getSharedPreferences("maps",0).edit().putString("catalog",address).apply();dialog.dismiss();}catch(IOException e){field.setError(e.getMessage());}});
    }
    private void downloadedCountries(){
        if(!canChangeMap())return;
        ArrayList<String> names=new ArrayList<>();ArrayList<File> files=new ArrayList<>();
        for(Map.Entry<String,?> entry:getSharedPreferences("maps",0).getAll().entrySet())if(entry.getKey().startsWith("ready_")&&entry.getValue() instanceof String){
            String[] pair=((String)entry.getValue()).split("\\|",2);if(pair.length!=2)continue;
            File file=new File(getFilesDir(),"downloads/"+pair[1]);if(file.isFile()){names.add(pair[0]);files.add(file);}
        }
        if(names.isEmpty()){error("No downloaded countries yet. Imported country packages are listed under Installed map details.");return;}
        new AlertDialog.Builder(this).setTitle("Select offline country").setItems(names.toArray(new String[0]),(d,w)->{
            if(!canChangeMap()||!Store.beginInstall())return;busy=true;refresh();Store.worker.execute(()->{try(InputStream in=new FileInputStream(files.get(w))){Graph g=RegionPackages.install(this,in);main.post(()->installGraph(g));}catch(Exception|LinkageError e){main.post(()->{busy=false;refresh();error("Country installation: "+e.getMessage());});}finally{Store.installing=false;}});
        }).setNegativeButton("Close",null).show();
    }
    private void installGraph(Graph g){Store.originChosen=false;Store.destinationChosen=false;Store.graph=g;Store.route=null;Store.start=0;Store.end=Math.min(3,g.nodes.length-1);if(Store.nativeRouter!=null)Store.setRegionEndpoints();Store.lat=Double.NaN;Store.lon=Double.NaN;busy=false;if(!isDestroyed()){map.setGraph(g);refresh();}}
    protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;
        final android.net.Uri uri=data.getData();
        if(request==202){
            if(!canChangeMap()||!Store.beginInstall())return;busy=true;refresh();Store.worker.execute(()->{
                try(InputStream in=getContentResolver().openInputStream(uri)){
                    if(in==null)throw new IOException("Cannot open region file");Graph g=RegionPackages.install(this,in);main.post(()->installGraph(g));
                }catch(IOException|RuntimeException|LinkageError e){main.post(()->{busy=false;if(!isDestroyed()){refresh();error("Region import failed: "+e.getMessage());}});}finally{Store.installing=false;}
            });
        }else if(request==200){
            if(!canChangeMap()||!Store.beginInstall())return;busy=true;refresh();Store.worker.execute(()->{
                File temporary=new File(getFilesDir(),"import.tmp");
                try{
                    try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(temporary)){
                        if(in==null)throw new IOException("Cannot open file");byte[] buffer=new byte[65536];long total=0;int n;
                        while((n=in.read(buffer))!=-1){total+=n;if(total>128L*1024*1024)throw new IOException("Map exceeds the 128 MB prototype limit");out.write(buffer,0,n);}
                    }
                    Graph g;try(InputStream in=new FileInputStream(temporary)){g=Graph.read(in);}
                    Files.move(temporary.toPath(),new File(getFilesDir(),"installed.europack").toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
                    Store.closeNative(this);
                    main.post(()->installGraph(g));
                }catch(IOException|RuntimeException e){temporary.delete();main.post(()->{busy=false;if(!isDestroyed()){refresh();error("Import failed: "+e.getMessage());}});}finally{Store.installing=false;}
            });
        }else if(request==201&&exporting!=null){
            final Router.Route r=exporting;exporting=null;
            Store.worker.execute(()->{try(OutputStream out=getContentResolver().openOutputStream(uri)){
                if(out==null)throw new IOException("Cannot write file");out.write(gpx(r).getBytes(StandardCharsets.UTF_8));main.post(()->Toast.makeText(this,"Route exported",Toast.LENGTH_SHORT).show());
            }catch(IOException e){main.post(()->error("Export failed: "+e.getMessage()));}});
        }
    }
    private String gpx(Router.Route r){
        StringBuilder b=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><gpx version=\"1.1\" creator=\"EuroRig\" xmlns=\"http://www.topografix.com/GPX/1/1\"><trk><name>EuroRig route</name><trkseg>");
        if(!r.edges.isEmpty()){Graph.Node start=r.graph.nodes[r.edges.get(0).from];b.append(point(start));for(Graph.Edge edge:r.edges)b.append(point(r.graph.nodes[edge.to]));}
        return b.append("</trkseg></trk></gpx>").toString();
    }
    private String point(Graph.Node n){return "<trkpt lat=\""+n.lat+"\" lon=\""+n.lon+"\"/>";}
    private void routeDialog(){
        Router.Route r=Store.route;if(r==null){error("Plan a route first.");return;}
        ArrayList<String> steps=new ArrayList<>();steps.add(r.graph.demo?"FICTIONAL TRAINING ROUTE":"Prototype route · check restrictions on the road");
        if(!r.edges.isEmpty()){int index=0;while(index<r.edges.size()){steps.add(String.format(Locale.getDefault(),"%.1f km · %s",r.cumulative[index]/1000,r.instruction(index)));index=r.nextManeuver(index);}steps.add("Arrive at destination");}
        new AlertDialog.Builder(this).setTitle("Route instructions").setItems(steps.toArray(new String[0]),null).setNegativeButton("Close",null).setPositiveButton("Export GPX",(d,w)->{
            exporting=r;startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/gpx+xml").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"EuroRig-route.gpx"),201);
        }).show();
    }
    private void moreDialog(){new AlertDialog.Builder(this).setTitle("EuroRig").setItems(new String[]{"Change starting point","Start at GPS","Fit map","Favourites",Store.voiceEnabled?"Mute voice":"Enable voice","Appearance","About and limitations"},(d,i)->{
        if(i==0)search(true);else if(i==1)startAtGps();else if(i==2){if(Store.graph==null)mapsDialog();else map.fit();}else if(i==3)favourites();else if(i==4){
            Store.voiceEnabled=!Store.voiceEnabled;getSharedPreferences("settings",0).edit().putBoolean("voice",Store.voiceEnabled).apply();
            if(!Store.voiceEnabled&&Store.navigating)startService(new Intent(this,NavigationService.class).setAction("MUTE"));
            Toast.makeText(this,Store.voiceEnabled?"Offline voice enabled for the next trip":"Visual guidance only",Toast.LENGTH_SHORT).show();
        }else if(i==5)appearanceDialog();else about();
    }).show();}
    private void updateMapControls(){
        boolean active=Store.navigating||simulating,hasMap=Store.graph!=null;
        gpsButton.setEnabled(hasMap&&!busy&&!Store.installing);
        overviewButton.setEnabled(hasMap&&!busy);
        zoomInButton.setEnabled(hasMap&&!busy);zoomOutButton.setEnabled(hasMap&&!busy);
        for(Button control:new Button[]{gpsButton,overviewButton,zoomInButton,zoomOutButton})control.setAlpha(control.isEnabled()?1:.45f);
        String locationLabel=active?(map.following()?"Following":"Recenter"):"Use GPS";
        gpsButton.setText(mapGlyphs?"":locationLabel);gpsButton.setTooltipText(locationLabel);overviewButton.setTooltipText("Overview");
        gpsButton.setContentDescription(active?"Recenter map and follow GPS position":"Use current GPS location as starting point");
    }
    private void mapGps(){
        if(Store.navigating||simulating){
            if(Double.isFinite(Store.lat)&&Store.fixTime>0&&SystemClock.elapsedRealtime()-Store.fixTime<15000){map.locate(Store.lat,Store.lon);updateMapControls();}
            else Toast.makeText(this,"Waiting for a fresh GPS fix",Toast.LENGTH_SHORT).show();
        }else startAtGps();
    }
    private void startAtGps(){
        if(!editable())return;
        if(Store.graph.demo){error("GPS cannot be used with the fictional training map. Import real local coverage first.");return;}
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},101);return;}
        LocationManager locations=getSystemService(LocationManager.class);
        if(!locations.isProviderEnabled(LocationManager.GPS_PROVIDER)){error("Enable device location, then tap Use GPS.");return;}
        status.setText(R.string.acquiring_gps);
        cancelGps();busy=true;gpsAcquiring=true;refresh();status.setText(R.string.acquiring_gps);
        boolean[] acquired={false};
        LocationListener listener=new LocationListener(){
            public void onLocationChanged(Location l){if(isDestroyed())return;
                long age=SystemClock.elapsedRealtimeNanos()-l.getElapsedRealtimeNanos();
                if(!l.hasAccuracy()||l.getAccuracy()>50||age<0||age>10_000_000_000L){status.setText(R.string.gps_improving);return;}
                Store.lat=l.getLatitude();Store.lon=l.getLongitude();Store.fixTime=SystemClock.elapsedRealtime()-age/1_000_000;
                if(l.hasBearing()&&l.hasSpeed()&&l.getSpeed()>=1)Store.bearing=l.getBearing();
                if(acquired[0]){map.updatePosition();return;}
                busy=false;gpsAcquiring=false;
                int n=Store.coordinate(l.getLatitude(),l.getLongitude());
                if(n<0){cancelGps();error("Your location is outside routable map coverage.");refresh();return;}
                acquired[0]=true;
                Store.start=n;Store.originChosen=true;Store.route=null;saveEndpoints();Store.lat=l.getLatitude();Store.lon=l.getLongitude();Store.fixTime=SystemClock.elapsedRealtime()-age/1_000_000;Store.bearing=Double.NaN;map.locate(Store.lat,Store.lon);refresh();
            }
            public void onProviderEnabled(String p){}public void onProviderDisabled(String p){}public void onStatusChanged(String p,int s,Bundle b){}
        };
        pendingGps=listener;
        try{locations.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,0,listener,Looper.getMainLooper());main.postDelayed(()->{if(pendingGps==listener&&!acquired[0]){cancelGps();if(!isDestroyed()){refresh();error("No accurate GPS fix yet. Try again outside.");}}},20000);}
        catch(SecurityException|IllegalArgumentException e){cancelGps();error("GPS unavailable");refresh();}
    }
    private void cancelGps(){if(pendingGps!=null){getSystemService(LocationManager.class).removeUpdates(pendingGps);pendingGps=null;}if(gpsAcquiring)busy=false;gpsAcquiring=false;}
    private void saveFavourite(int n){
        Graph.Node p=Store.graph.nodes[n];String value=p.lat+","+p.lon+"|"+label(n);
        android.content.SharedPreferences prefs=getSharedPreferences("favourites",0);Set<String> set=new HashSet<>(prefs.getStringSet("places",Collections.emptySet()));set.add(value);prefs.edit().putStringSet("places",set).apply();Toast.makeText(this,"Favourite saved on this device",Toast.LENGTH_SHORT).show();
    }
    private void favourites(){
        if(!editable())return;
        ArrayList<String> items=new ArrayList<>(getSharedPreferences("favourites",0).getStringSet("places",Collections.emptySet()));Collections.sort(items);
        if(items.isEmpty()){error("Tap a map point and choose Save favourite.");return;}
        String[] labels=new String[items.size()];for(int i=0;i<labels.length;i++)labels[i]=items.get(i).substring(items.get(i).indexOf('|')+1);
        new AlertDialog.Builder(this).setTitle("Local favourites").setItems(labels,(d,i)->{
            String[] coordinates=items.get(i).substring(0,items.get(i).indexOf('|')).split(",");int n=Store.coordinate(Double.parseDouble(coordinates[0]),Double.parseDouble(coordinates[1]));
            selectedPoint(n,false);
        }).setNegativeButton("Close",null).show();
    }
    private void about(){new AlertDialog.Builder(this).setTitle("EuroRig "+BuildConfig.VERSION_NAME).setMessage("Made by drivers, for drivers. Free, open source, local navigation.\n\nMIT code. OSM maps: © OpenStreetMap contributors, ODbL 1.0. No accounts, subscriptions or analytics. Internet is used to download maps; routes, roads, search and GPS run on the device.\n\nNo maps are bundled. Romania is the first test country. Europe excluding Russia is the coverage target; publication of all country packages and seamless cross-border routing are pending.\n\nThis development build has not passed road validation. Mapped ADR load types and B-E tunnel codes are checked. Country truck laws, conditional restrictions and complete ADR rules need further work. Offline voice needs an installed voice.\n\nAndroid 8+ minimum; Tab S9 checks and Android 8/17 emulator tests are recorded in VERIFICATION.md. Road validation and low-end hardware tests remain necessary.").setNeutralButton("Licences",(d,w)->licences()).setPositiveButton("Close",null).show();}
    private void licences(){
        try{
            String[] files=getAssets().list("licenses");if(files==null)return;
            new AlertDialog.Builder(this).setTitle("Dependency and map licences").setItems(files,(d,index)->{
                try(InputStream in=getAssets().open("licenses/"+files[index])){
                    ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)bytes.write(b,0,n);
                    ScrollView scroll=new ScrollView(this);TextView body=text(bytes.toString("UTF-8"),13,TEXT);body.setPadding(dp(18),dp(12),dp(18),dp(12));scroll.addView(body);
                    new AlertDialog.Builder(this).setTitle(files[index]).setView(scroll).setPositiveButton("Close",null).show();
                }catch(IOException e){error(e.getMessage());}
            }).setPositiveButton("Close",null).show();
        }catch(IOException e){error(e.getMessage());}
    }
    private void error(String message){if(!isFinishing()&&!isDestroyed())new AlertDialog.Builder(this).setTitle("EuroRig").setMessage(message).setPositiveButton("OK",null).show();}
}
