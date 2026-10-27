package org.eurorig.app;
import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.*;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import org.eurorig.routing.*;
import org.eurorig.routing.parking.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
public final class MainActivity extends Activity {
    private int BG,PANEL,TEXT,MUTED,LIME;
    private FrameLayout interfaceRoot;
    private LinearLayout idlePanel,guidancePanel,journeyPanel,simulationPanel;
    private TextView journeyTime,journeyDistance,maneuverDistance,currentStreet;
    private LaneGuidanceView laneGuidance;
    private Button simulate;
    private SeekBar simulationSpeedSlider;
    private TextView simulationSpeedLabel, routeNotice;
    private LinearLayout simulationSpeedControls;
    private int simulationSpeed=50;
    private InterfacePages pages;
    private JourneyUi journeyUi;
    private LinearLayout planningHeader,placePanel,alternativeTabs;
    private List<Router.Route> shownChoices=Collections.emptyList();private Router.Route shownChoice;
    private TextView routeEndpoints,placeTitle,placeCoordinates;
    private Graph.Node previewPoint;
    private RecenterControl recenter;
    private boolean browseAway;
    private long browseDeadline;
    private Router.Route noticeRoute;private Truck noticeTruck;private List<RouteSection> noticeSections;
    private String cachedProfiles,cachedProfileName;
    private Router.Route paintedRoute;
    private RouteSimulation routeSimulation;
    private boolean simulationPaused;
    private long simulationClock;
    private boolean darkMode;
    private TextView status, profile, profileWeight, profileHeight, profileMassWarning, endpoints, instruction, stats, speedDisplay;
    private LinearLayout vehicleSummary;
    private LinearLayout routeCard, searchBar;
    private Button menuButton;
    private Button modeButton;
    private Button zoomInButton,zoomOutButton;
    private MapControlMenu mapControlMenu;
    private boolean guidanceWasActive;
    private Button plan, drive;
    private RoadMapView map;
    private final Handler main=new Handler(Looper.getMainLooper());
    private boolean busy, simulating, resumed;
    private boolean previouslyActive;
    private boolean resumePromptShowing,resumeRequested,resumeAfterPlan;
    private Graph displayedGraph;
    private DisplayDatabase displayedDatabase;
    private int requestGeneration, simulationEdge;
    private double simulationFraction;
    private Progress simulatedProgress;
    private Router.Route exporting;
    private LocationListener pendingGps;
    private Location browsingFix;
    private boolean locationFollowRequested,autoLocated;
    private TextView locationStatus;
    /** Compact next-parking line in the existing journey panel. */
    private TextView parkingNext;
    private boolean gpsAcquiring;
    private final Runnable ticker=new Runnable(){public void run(){
        if(!resumed)return;
        boolean active=simulating||Store.navigating||MapDownloadService.running;
        if(simulating) simulationTick();
        if(active||previouslyActive)refresh();
        else {if(pendingGps==null)resumeTracking();updateLocationStatus();if(map!=null)map.updatePosition();}
        previouslyActive=simulating||Store.navigating||MapDownloadService.running;
        main.postDelayed(this,simulating?200:Store.navigating?500:1500);
    }};
    @Override protected void attachBaseContext(Context base){
        int choice=base.getSharedPreferences("settings",0).getInt("app_language",0);
        if(choice==0){super.attachBaseContext(base);return;}
        Locale locale=choice==2?Locale.forLanguageTag("ro-RO"):Locale.ENGLISH;
        android.content.res.Configuration configuration=new android.content.res.Configuration(base.getResources().getConfiguration());
        configuration.setLocale(locale);
        super.attachBaseContext(base.createConfigurationContext(configuration));
    }
    public void onCreate(Bundle state){
        simulationSpeed=Math.max(0,Math.min(200,getSharedPreferences("settings",0).getInt("simulation_speed",50)));applyAppearance();super.onCreate(state);buildUi();
        busy=true;refresh();
        // Parking locations and their last reported counts, cached for offline use.
        Store.parking=ParkingRepository.get(this);
        Store.parking.observe(this::parkingUpdated);
        Store.worker.execute(()->{
            try {
                // Debug-only side-load path: a package dropped in app-private storage is installed
                // through the same validated path as the SAF importer, then removed.
                java.io.File pending=new java.io.File(getFilesDir(),"import.euroorig");
                if(pending.exists()){
                    try(java.io.InputStream in=new java.io.FileInputStream(pending)){RegionPackages.install(getApplicationContext(),in);}
                    pending.delete();
                }
                Store.load(getApplicationContext());main.post(()->{if(isDestroyed())return;busy=false;map.setGraph(Store.graph);refresh();resumeTracking();if(Store.interruptedTripPending(this))offerInterruptedTrip();});}
            catch(IOException|RuntimeException|LinkageError e){
                // Recover the interface while retaining the damaged region on disk.
                Store.closeNative(this);Store.graph=null;
                main.post(()->{busy=false;map.setGraph(null);refresh();error("Saved map could not load. Import or download the map again. "+e.getMessage());});
            }
        });
    }
    protected void onResume(){super.onResume();resumed=true;if(map!=null)map.startMap();simulationClock=SystemClock.elapsedRealtime();main.removeCallbacks(ticker);main.post(ticker);resumeTracking();}
    protected void onPause(){resumed=false;if(mapControlMenu!=null)mapControlMenu.setExpanded(false);if(recenter!=null)recenter.stop();browseDeadline=0;if(simulating){simulationPaused=true;Store.speed=0;}if(map!=null)map.pauseMap();main.removeCallbacks(ticker);cancelGps();super.onPause();}
    protected void onDestroy(){requestGeneration++;main.removeCallbacksAndMessages(null);if(simulating){Store.lat=Double.NaN;Store.lon=Double.NaN;Store.simulating=false;}if(pages!=null)pages.close();if(journeyUi!=null)journeyUi.close();if(recenter!=null)recenter.stop();if(map!=null)map.dispose();super.onDestroy();}
    public void onConfigurationChanged(android.content.res.Configuration configuration){
        super.onConfigurationChanged(configuration);RoadMapView old=map;boolean followingInitialized=guidanceWasActive;buildUi();if(Store.graph!=null)map.setGraph(Store.graph);map.restoreCamera(old);old.dispose();guidanceWasActive=followingInitialized;if(pages!=null)pages.configurationChanged();if(journeyUi!=null)journeyUi.configurationChanged();refresh();
    }
    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private void applyAppearance(){
        darkMode=getSharedPreferences("settings",0).getBoolean("dark_mode",false);
        BG=darkMode?0xff000000:0xfffff9f0;PANEL=darkMode?0xff211c17:0xfffffcf5;TEXT=darkMode?0xfff6efe5:0xff2c2118;MUTED=darkMode?0xffc0af9b:0xff6b5948;LIME=darkMode?0xffe89045:0xff9c3708;getSharedPreferences("settings",0).edit().putBoolean("dark_mode",darkMode).apply();
        setTheme(darkMode?R.style.AppTheme:R.style.AppThemeLight);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(darkMode?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }
    void appearanceDialog(){
        new AlertDialog.Builder(this).setTitle("Appearance")
            .setSingleChoiceItems(new String[]{"Light · Cream","Dark · OLED black"},darkMode?1:0,(dialog,which)->{
                dialog.dismiss();if((which==1)!=darkMode)setDarkMode(which==1);
            }).setNegativeButton("Close",null).show();
    }
    void setDarkMode(boolean enabled){
        getSharedPreferences("settings",0).edit().putBoolean("dark_mode",enabled).apply();
        RoadMapView old=map;boolean initialized=guidanceWasActive;applyAppearance();buildUi();
        map.setGraph(Store.graph);map.restoreCamera(old);old.dispose();guidanceWasActive=initialized;refresh();
    }
    private GradientDrawable background(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextColor(color);t.setTextSize(size);t.setFontFeatureSettings("kern");return t;}
    private LinearLayout vertical(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private Button button(String value,Runnable action){Button b=new Button(this);b.setText(value);b.setTextSize(13);b.setAllCaps(false);b.setTextColor(TEXT);b.setBackground(background(PANEL,12));b.setPadding(dp(10),0,dp(10),0);b.setMinHeight(dp(48));b.setOnClickListener(v->action.run());return b;}
    private TextView profileValue(String value){TextView t=text(value,14,TEXT);t.setSingleLine(true);t.setEllipsize(TextUtils.TruncateAt.END);t.setGravity(Gravity.CENTER_VERTICAL);t.setTypeface(null,Typeface.BOLD);t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);return t;}
    private LinearLayout profileCell(int drawable,TextView value){
        LinearLayout cell=new LinearLayout(this);cell.setGravity(Gravity.CENTER_VERTICAL);cell.setPadding(dp(6),0,dp(4),0);ImageView icon=new ImageView(this);icon.setImageResource(drawable);icon.setColorFilter(LIME);icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(dp(22),dp(24));ip.rightMargin=dp(5);cell.addView(icon,ip);cell.addView(value,new LinearLayout.LayoutParams(0,-1,1));return cell;
    }
    private void profileDivider(LinearLayout row){View line=new View(this);line.setBackgroundColor(darkMode?0xff493a2d:0xffe9dece);row.addView(line,new LinearLayout.LayoutParams(dp(1),dp(30)));}
    private void buildUi(){
        if(recenter!=null)recenter.stop();guidanceWasActive=false;paintedRoute=null;if(pages==null)pages=new InterfacePages(this);if(journeyUi==null)journeyUi=new JourneyUi(this);
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(BG);interfaceRoot=root;
        root.setOnApplyWindowInsetsListener((v,insets)->{root.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});setContentView(root);root.requestApplyInsets();WindowManager.LayoutParams window=getWindow().getAttributes();window.preferredRefreshRate=120;getWindow().setAttributes(window);
        map=new RoadMapView(this);map.pick=n->{if(editable())choosePoint(n);};map.parkingTap=this::parkingTapped;map.cameraChanged=this::updateMapControls;map.userGesture=this::browsingStarted;map.userIdle=this::browsingIdle;root.addView(map,new FrameLayout.LayoutParams(-1,-1));if(resumed)map.startMap();
        boolean wide=getResources().getConfiguration().screenWidthDp>getResources().getConfiguration().screenHeightDp;
        int width=getResources().getConfiguration().screenWidthDp;
        searchBar=new LinearLayout(this);searchBar.setGravity(Gravity.CENTER_VERTICAL);searchBar.setPadding(dp(4),dp(4),dp(4),dp(4));searchBar.setBackground(background(PANEL,28));searchBar.setElevation(dp(4));
         Button search=button(getString(R.string.action_search),()->{if(Store.graph==null)mapsDialog();else search(false);});search.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);search.setTextSize(18);search.setContentDescription(getString(R.string.search_accessibility));
        android.graphics.drawable.Drawable glass=getDrawable(R.drawable.ic_search_glass);glass.setTint(LIME);glass.setBounds(0,0,dp(24),dp(24));search.setCompoundDrawablesRelative(glass,null,null,null);search.setCompoundDrawablePadding(dp(12));search.setBackgroundColor(Color.TRANSPARENT);searchBar.addView(search,new LinearLayout.LayoutParams(0,dp(48),1));
         menuButton=button("☰",this::navigationMenu);menuButton.setTextSize(25);menuButton.setContentDescription(getString(R.string.navigation_menu_accessibility));menuButton.setBackground(background(darkMode?0xff3b2b1f:0xffe7d5be,22));searchBar.addView(menuButton,new LinearLayout.LayoutParams(dp(48),dp(48)));
        FrameLayout.LayoutParams searchPos=new FrameLayout.LayoutParams(wide?dp(Math.min(480,width-136)):-1,dp(56),Gravity.TOP|Gravity.START);searchPos.setMargins(dp(12),dp(12),dp(12),0);root.addView(searchBar,searchPos);

        idlePanel=vertical();routeCard=vertical();idlePanel.addView(routeCard);

        vehicleSummary=vertical();vehicleSummary.setBackground(background(PANEL,18));vehicleSummary.setElevation(dp(3));vehicleSummary.setFocusable(true);vehicleSummary.setOnClickListener(v->truckDialog());
        LinearLayout vehicle=new LinearLayout(this);vehicle.setGravity(Gravity.CENTER_VERTICAL);vehicle.setPadding(dp(8),0,dp(8),0);
        profile=profileValue("Your truck");profileWeight=profileValue("— t");profileHeight=profileValue("— m");
        vehicle.addView(profileCell(R.drawable.ic_profile_truck,profile),new LinearLayout.LayoutParams(0,dp(54),1));
        profileDivider(vehicle);vehicle.addView(profileCell(R.drawable.ic_profile_weight,profileWeight),new LinearLayout.LayoutParams(dp(96),dp(54)));
        profileDivider(vehicle);vehicle.addView(profileCell(R.drawable.ic_profile_height,profileHeight),new LinearLayout.LayoutParams(dp(86),dp(54)));
        vehicleSummary.addView(vehicle);profileMassWarning=text("",12,darkMode?0xffffb59d:0xffa63b26);profileMassWarning.setPadding(dp(12),0,dp(12),dp(8));profileMassWarning.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);vehicleSummary.addView(profileMassWarning);
        modeButton=button("Options",this::routingOptions);modeButton.setVisibility(View.GONE);LinearLayout.LayoutParams pill=new LinearLayout.LayoutParams(-1,-2);pill.topMargin=0;routeCard.addView(vehicleSummary,pill);status=text(getString(R.string.status_opening_maps),12,MUTED);status.setPadding(dp(12),dp(4),0,0);idlePanel.addView(status);locationStatus=text("",12,MUTED);locationStatus.setPadding(dp(12),dp(4),dp(12),dp(4));locationStatus.setOnClickListener(v->requestCurrentLocation());idlePanel.addView(locationStatus);
        FrameLayout.LayoutParams idlePos=new FrameLayout.LayoutParams(wide?dp(Math.min(480,width-200)):-1,-2,Gravity.TOP|Gravity.START);idlePos.setMargins(dp(12),dp(76),dp(12),0);root.addView(idlePanel,idlePos);
        planningHeader=vertical();planningHeader.setPadding(dp(16),dp(8),dp(16),dp(12));planningHeader.setBackground(background(darkMode?0xff101010:PANEL,22));LinearLayout plannerTitle=new LinearLayout(this);plannerTitle.setGravity(Gravity.CENTER_VERTICAL);TextView heading=text(getString(R.string.route_planner_title),18,TEXT);plannerTitle.addView(heading,new LinearLayout.LayoutParams(0,dp(48),1));plannerTitle.addView(button(getString(R.string.route_edit_route),this::planner),new LinearLayout.LayoutParams(dp(72),dp(48)));planningHeader.addView(plannerTitle);routeEndpoints=text("",16,TEXT);routeEndpoints.setPadding(dp(4),dp(6),0,dp(4));routeEndpoints.setOnClickListener(v->planner());planningHeader.addView(routeEndpoints);FrameLayout.LayoutParams hp=new FrameLayout.LayoutParams(wide?dp(Math.min(480,width-200)):-1,-2,Gravity.TOP|Gravity.START);hp.setMargins(dp(12),dp(12),dp(12),0);root.addView(planningHeader,hp);
        guidancePanel=vertical();guidancePanel.setPadding(dp(18),dp(14),dp(18),dp(14));guidancePanel.setBackground(background(0xffb94d10,18));maneuverDistance=text("",34,0xfffff6eb);maneuverDistance.setTypeface(Typeface.DEFAULT,Typeface.BOLD);guidancePanel.addView(maneuverDistance);instruction=text(getString(R.string.navigation_choose_destination),22,0xfffff6eb);instruction.setMaxLines(3);instruction.setOnClickListener(v->routeDialog());guidancePanel.addView(instruction);currentStreet=text("",13,0xfffff6eb);guidancePanel.addView(currentStreet);laneGuidance=new LaneGuidanceView(this);guidancePanel.addView(laneGuidance,new LinearLayout.LayoutParams(-1,dp(90)));FrameLayout.LayoutParams guidePos=new FrameLayout.LayoutParams(wide?dp(Math.min(480,(width-92)*.5f)):-1,-2,Gravity.TOP|Gravity.START);guidePos.setMargins(dp(12),dp(76),dp(12),0);root.addView(guidancePanel,guidePos);
        journeyPanel=vertical();journeyPanel.setPadding(dp(14),dp(10),dp(14),dp(10));journeyPanel.setBackground(background(darkMode?0xff101010:PANEL,18));
        alternativeTabs=new LinearLayout(this);journeyPanel.addView(alternativeTabs,new LinearLayout.LayoutParams(-1,-2));shownChoices=Collections.emptyList();shownChoice=null;
         endpoints=text(getString(R.string.route_choose_endpoints),15,TEXT);endpoints.setPadding(dp(4),dp(5),dp(4),dp(5));endpoints.setContentDescription(getString(R.string.route_endpoints_accessibility));endpoints.setOnClickListener(v->planner());journeyPanel.addView(endpoints);
        journeyTime=text(getString(R.string.route_ready_for_road),26,LIME);journeyTime.setGravity(Gravity.CENTER);journeyTime.setTypeface(Typeface.DEFAULT,Typeface.BOLD);journeyPanel.addView(journeyTime);journeyDistance=text(getString(R.string.route_download_map_to_begin),15,TEXT);journeyDistance.setGravity(Gravity.CENTER);journeyPanel.addView(journeyDistance);
        stats=text("",12,MUTED);stats.setPadding(0,dp(6),0,dp(6));journeyPanel.addView(stats);
        parkingNext=text("",12,MUTED);parkingNext.setPadding(dp(4),0,dp(4),dp(6));parkingNext.setVisibility(View.GONE);parkingNext.setOnClickListener(v->parkingDetails());journeyPanel.addView(parkingNext);
        LinearLayout actions=new LinearLayout(this);plan=button(getString(R.string.route_edit_route),()->{if(Store.route!=null)planner();else calculate();});drive=button(getString(R.string.action_start_guidance),this::startGuidance);drive.setBackground(background(0xff61a7f4,12));drive.setTextColor(0xfffff6eb);LinearLayout.LayoutParams actionSize=new LinearLayout.LayoutParams(0,dp(48),1);actionSize.rightMargin=dp(6);actions.addView(plan,actionSize);actions.addView(drive,new LinearLayout.LayoutParams(0,dp(48),1));journeyPanel.removeView(journeyTime);journeyPanel.removeView(journeyDistance);LinearLayout routeSummary=new LinearLayout(this);routeSummary.setGravity(Gravity.CENTER_VERTICAL);LinearLayout totals=vertical();journeyTime.setGravity(Gravity.START);journeyDistance.setGravity(Gravity.START);totals.addView(journeyTime);totals.addView(journeyDistance);routeSummary.addView(totals,new LinearLayout.LayoutParams(0,-2,1));routeSummary.addView(actions,new LinearLayout.LayoutParams(dp(68),dp(60)));journeyPanel.addView(routeSummary,1);
        simulationPanel=vertical();
        simulate=button(getString(R.string.route_simulate_selected),()->{
            if(simulating&&Store.arrived){stopGuidance();startSimulation();return;}
            if(simulating){simulationTick();simulationPaused=!simulationPaused;simulationClock=SystemClock.elapsedRealtime();Store.speed=simulationPaused?0:simulationSpeed;refresh();}else startSimulation();
        });simulationPanel.addView(simulate,new LinearLayout.LayoutParams(-1,dp(48)));
         simulationSpeedControls=vertical();simulationSpeedLabel=text(getString(R.string.simulation_speed_label,simulationSpeed),14,TEXT);simulationSpeedLabel.setGravity(Gravity.CENTER);simulationSpeedControls.addView(simulationSpeedLabel);
         simulationSpeedSlider=new SeekBar(this);simulationSpeedSlider.setMax(200);simulationSpeedSlider.setProgress(simulationSpeed);simulationSpeedSlider.setContentDescription(getString(R.string.simulation_speed_accessibility));
        simulationSpeedSlider.setProgressTintList(android.content.res.ColorStateList.valueOf(LIME));simulationSpeedSlider.setThumbTintList(android.content.res.ColorStateList.valueOf(LIME));
        simulationSpeedSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
             public void onProgressChanged(SeekBar bar,int value,boolean fromUser){if(!fromUser)return;if(simulating)simulationTick();simulationSpeed=value;if(routeSimulation!=null)routeSimulation.setSpeed(value);simulationSpeedLabel.setText(getString(R.string.simulation_speed_label,value));if(simulating){Store.speed=simulationPaused||Store.arrived?0:value;refresh();}}
            public void onStartTrackingTouch(SeekBar bar){}
            public void onStopTrackingTouch(SeekBar bar){getSharedPreferences("settings",0).edit().putInt("simulation_speed",simulationSpeed).apply();}
        });simulationSpeedControls.addView(simulationSpeedSlider,new LinearLayout.LayoutParams(-1,dp(40)));simulationPanel.addView(simulationSpeedControls);journeyPanel.addView(simulationPanel);
         TextView attribution=text(new File(getFilesDir(),"romania.mbtiles").isFile()?"© OpenStreetMap · © OpenMapTiles":"© OpenStreetMap contributors",12,MUTED);attribution.setGravity(Gravity.CENTER);attribution.setPadding(0,dp(4),0,0);attribution.setOnClickListener(v->about());attribution.setContentDescription(getString(R.string.map_attribution_accessibility));journeyPanel.addView(attribution);
        routeNotice=text("",13,darkMode?0xffe58c76:0xffb94935);routeNotice.setPadding(dp(4),dp(4),dp(4),0);routeNotice.setMaxLines(2);routeNotice.setOnClickListener(v->routeDialog());routeNotice.setBackground(background(0xffff941a,12));routeNotice.setTextColor(0xff141414);routeNotice.setPadding(dp(12),dp(10),dp(12),dp(10));if(wide){FrameLayout.LayoutParams noticePos=new FrameLayout.LayoutParams(dp(Math.max(200,width-Math.min(480,width-200)-36)),-2,Gravity.TOP|Gravity.END);noticePos.setMargins(dp(12),dp(12),dp(12),0);root.addView(routeNotice,noticePos);}else planningHeader.addView(routeNotice);
        FrameLayout.LayoutParams bottomPos=new FrameLayout.LayoutParams(wide?dp(Math.min(480,(width-24)*.46f)):-1,-2,Gravity.BOTTOM|Gravity.START);bottomPos.setMargins(dp(12),0,dp(12),dp(12));root.addView(journeyPanel,bottomPos);
        placePanel=vertical();placePanel.setPadding(dp(18),dp(14),dp(18),dp(14));placePanel.setBackground(background(PANEL,22));placeTitle=text(getString(R.string.place_selected),22,TEXT);placeTitle.setTypeface(null,Typeface.BOLD);placePanel.addView(placeTitle);placeCoordinates=text("",14,MUTED);placeCoordinates.setPadding(0,dp(8),0,dp(12));placePanel.addView(placeCoordinates);placePanel.addView(button(getString(R.string.action_get_directions),()->{Graph.Node point=previewPoint;if(point==null)return;chooseJourneyPoint(point,0);previewPoint=null;map.clearSearchPoint();refresh();if(Store.originChosen)calculate();else planner();}),new LinearLayout.LayoutParams(-1,dp(54)));placePanel.addView(button(getString(R.string.action_close_dialog),()->{previewPoint=null;map.clearSearchPoint();refresh();}),new LinearLayout.LayoutParams(-1,dp(48)));FrameLayout.LayoutParams pp=new FrameLayout.LayoutParams(wide?dp(480):-1,-2,Gravity.BOTTOM|Gravity.START);pp.setMargins(dp(12),0,dp(12),dp(12));root.addView(placePanel,pp);
        recenter=new RecenterControl(this,()->tryRecenter(false));recenter.setOnClickListener(v->tryRecenter(true));FrameLayout.LayoutParams rp=new FrameLayout.LayoutParams(dp(220),dp(56),Gravity.BOTTOM|Gravity.START);rp.setMargins(dp(12),0,dp(12),dp(12));root.addView(recenter,rp);recenter.setVisibility(browseAway?View.VISIBLE:View.GONE);if(browseAway&&browseDeadline>0)recenter.start(browseDeadline);
        speedDisplay=text("—\nkm/h",25,TEXT);speedDisplay.setGravity(Gravity.CENTER);speedDisplay.setTypeface(Typeface.DEFAULT,Typeface.BOLD);speedDisplay.setPadding(dp(8),dp(10),dp(8),dp(10));speedDisplay.setBackground(background(PANEL,18));FrameLayout.LayoutParams speedPos=new FrameLayout.LayoutParams(dp(86),dp(96),Gravity.BOTTOM|Gravity.END);speedPos.setMargins(0,0,dp(12),dp(wide?12:250));root.addView(speedDisplay,speedPos);
        mapControlMenu=new MapControlMenu(this,PANEL,LIME,()->map.zoom(1),()->{getSharedPreferences("settings",0).edit().putBoolean("map_3d",!getSharedPreferences("settings",0).getBoolean("map_3d",true)).putBoolean("north_up",false).apply();mapPreferencesChanged();},()->map.zoom(-1));
        mapControlMenu.cameraChanged(getSharedPreferences("settings",0).getBoolean("map_3d",true));zoomInButton=mapControlMenu.zoomIn;zoomOutButton=mapControlMenu.zoomOut;
        FrameLayout.LayoutParams miniPos=new FrameLayout.LayoutParams(dp(56),-2,Gravity.TOP|Gravity.END);miniPos.setMargins(dp(12),dp(260),dp(12),0);root.addView(mapControlMenu,miniPos);
        RoadMapView currentMap=map;
        root.getViewTreeObserver().addOnGlobalLayoutListener(()->{
            if(currentMap!=map)return;
            int h=currentMap.getHeight(),w=currentMap.getWidth();
            boolean hasJourney=Store.route!=null||Store.navigating||simulating;int upper=(Store.navigating||simulating?guidancePanel:Store.route!=null?planningHeader:idlePanel).getBottom()-currentMap.getTop();
            int journeyTop=hasJourney?journeyPanel.getTop()-currentMap.getTop():previewPoint!=null?placePanel.getTop()-currentMap.getTop():h;
            FrameLayout.LayoutParams sp=(FrameLayout.LayoutParams)speedDisplay.getLayoutParams();
            int bottom=wide?dp(12):Math.max(dp(12),h-journeyTop+dp(70));
            if(sp.bottomMargin!=bottom){sp.bottomMargin=bottom;speedDisplay.setLayoutParams(sp);}
            int menuHeight=mapControlMenu.getHeight();int minMenuY=wide?dp(76):upper+dp(12);int maxMenuY=journeyTop-menuHeight-dp(8);
            boolean narrowMap=!wide&&maxMenuY<minMenuY;int menuY=narrowMap?Math.max(upper+dp(8),(h-menuHeight)/2):Math.max(minMenuY,Math.min((h-menuHeight)/2,maxMenuY));
            FrameLayout.LayoutParams mp=(FrameLayout.LayoutParams)mapControlMenu.getLayoutParams();int mg=Gravity.TOP|(narrowMap?Gravity.START:Gravity.END);
            int menuRight=wide&&(Store.navigating||simulating)&&menuY+menuHeight>h-bottom-dp(96)&&menuY<h-bottom?dp(110):dp(12);
            if(mp.topMargin!=menuY||mp.gravity!=mg||mp.rightMargin!=menuRight){mp.topMargin=menuY;mp.gravity=mg;mp.rightMargin=menuRight;mapControlMenu.setLayoutParams(mp);}
            FrameLayout.LayoutParams rc=(FrameLayout.LayoutParams)recenter.getLayoutParams();int rb=hasJourney&&!wide?h-journeyTop+dp(8):dp(12);int rl=hasJourney&&wide?Math.min(w-dp(232),journeyPanel.getRight()+dp(12)):dp(12);if(rc.bottomMargin!=rb||rc.leftMargin!=rl){rc.bottomMargin=rb;rc.leftMargin=rl;recenter.setLayoutParams(rc);}
            currentMap.setViewport(wide&&hasJourney?journeyPanel.getRight()-currentMap.getLeft()+dp(12):dp(6),wide?dp(76):upper+dp(64),w-dp(wide?110:6),wide?h-dp(12):journeyTop-dp(68));
        });
    }
    private void navigationMenu(){overlayOpened();PopupMenu menu=new PopupMenu(this,menuButton);String[] names={getString(R.string.navigation_menu_main),getString(R.string.navigation_menu_edit_route),getString(R.string.navigation_menu_vehicle_profiles),getString(R.string.navigation_menu_offline_maps),getString(R.string.navigation_menu_favourites),getString(R.string.navigation_menu_settings),getString(R.string.navigation_menu_route_instructions),getString(R.string.navigation_menu_current_location),getString(R.string.navigation_menu_route_overview),getString(R.string.navigation_menu_simulate)};for(int i=0;i<names.length;i++)menu.getMenu().add(0,i,i,names[i]);menu.setOnMenuItemClickListener(item->{switch(item.getItemId()){case 0:pages.menu();break;case 1:planner();break;case 2:truckDialog();break;case 3:mapsDialog();break;case 4:favourites();break;case 5:pages.settings();break;case 6:routeDialog();break;case 7:mapGps();break;case 8:if(Store.route!=null)map.fitRoute(Store.route);else map.fit();break;default:startSimulation();}return true;});menu.show();}
    void overlayOpened(){if(mapControlMenu!=null)mapControlMenu.setExpanded(false);if(recenter!=null)recenter.stop();browseDeadline=0;if(simulating&&!simulationPaused){simulationPaused=true;Store.speed=0;simulationClock=SystemClock.elapsedRealtime();refresh();}}
    void mapPreferencesChanged(){NavigationSettings.reload(this);if(map!=null)map.preferencesChanged();if(mapControlMenu!=null)mapControlMenu.cameraChanged(getSharedPreferences("settings",0).getBoolean("map_3d",true));refreshInterface();}
    void profileChanged(Truck truck){Store.saveTruck(this,truck);if(map!=null)map.invalidateRestrictions();refresh();if(Store.interruptedTripPending(this))main.postDelayed(this::offerInterruptedTrip,350);}
    private void refreshInterface(){
        if(journeyTime==null)return;
        refreshAlternatives();boolean active=Store.navigating||simulating;
        searchBar.setVisibility(!active&&Store.route!=null?View.GONE:View.VISIBLE);
        NavigationSettings preferences=NavigationSettings.get(this);
        laneGuidance.update(Store.route,Store.travelled,active&&!Store.arrived&&Store.progressRoute==Store.route,darkMode);
        NavigationData.Road currentRoad=active&&Store.route!=null?Store.route.navigation.road(NavigationData.edgeAt(Store.route,Store.travelled)):new NavigationData.Road("","road",0,true);
        String roadText=NavigationRoadPresentation.text(this,currentRoad,preferences);
        currentStreet.setText(roadText);currentStreet.setVisibility(active&&preferences.currentStreet&&!roadText.isEmpty()?View.VISIBLE:View.GONE);
        idlePanel.setVisibility(active?View.GONE:View.VISIBLE);guidancePanel.setVisibility(active?View.VISIBLE:View.GONE);
        boolean wide=getResources().getConfiguration().orientation==2;
        endpoints.setVisibility(View.GONE);stats.setVisibility(active||wide?View.GONE:View.VISIBLE);
        simulationSpeedControls.setVisibility(simulating?View.VISIBLE:View.GONE);simulationPanel.setVisibility(simulating?View.VISIBLE:View.GONE);
        simulate.setText(simulating?(Store.arrived?getString(R.string.route_replay_selected):simulationPaused?getString(R.string.route_resume_simulation):getString(R.string.route_pause_simulation)):getString(R.string.route_simulate_selected));
        speedDisplay.setVisibility(active?View.VISIBLE:View.GONE);
        String warning=RouteWarningPresentation.text(this,Store.restrictionWarning());
        if(Store.route!=null){List<RouteSection> routeNotices=notices();if(warning.isEmpty()&&(RouteSection.metresOf(routeNotices,RouteSection.AUTHORISED_ACCESS)>0||RouteSection.metresOf(routeNotices,RouteSection.UNVERIFIED)>0))warning=getString(R.string.route_summary_restrictions_content_description);}
        routeNotice.setText(warning);routeNotice.setVisibility(warning.isEmpty()?View.GONE:View.VISIBLE);
        planningHeader.setVisibility(!active&&Store.route!=null?View.VISIBLE:View.GONE);journeyPanel.setVisibility(Store.route!=null?View.VISIBLE:View.GONE);
        placePanel.setVisibility(previewPoint!=null&&!active?View.VISIBLE:View.GONE);
        if(previewPoint!=null){placeTitle.setText(previewPoint.label.isEmpty()?getString(R.string.route_selected_coordinates):previewPoint.label);placeCoordinates.setText(preferences.point(previewPoint.lat,previewPoint.lon));}
        idlePanel.setVisibility(!active&&Store.route==null&&previewPoint==null?View.GONE:View.VISIBLE);
        routeEndpoints.setText("◉  "+(Store.originChosen?label(Store.start):getString(R.string.start_point_choose))
                +(Store.viaPoints.isEmpty()?"":"\n•  "+getString(R.string.route_waypoint_count,Store.viaPoints.size()))
                +"\n⚑  "+(Store.destinationChosen?label(Store.end):getString(R.string.destination_choose)));
        if(Store.route==null){journeyTime.setText(R.string.route_ready_for_road);journeyDistance.setText(Store.graph==null?R.string.route_download_map_to_begin:R.string.route_choose_start_and_destination);return;}
        Router.Route route=Store.route;
        double remaining=active?(preferences.nextWaypoint?route.toNextWaypoint(Store.travelled):Store.remaining):route.metres;
        double seconds=simulating?(simulationSpeed==0?0:remaining/(simulationSpeed/3.6)):route.seconds*remaining/Math.max(1,route.metres);
        journeyTime.setText(Store.arrived?getString(R.string.navigation_arrived):simulating&&simulationSpeed==0?getString(R.string.route_stationary):duration(seconds));
        String arrival=preferences.time(System.currentTimeMillis()+(long)(seconds*1000),this);
        if(!active){journeyTime.setText(arrival);journeyTime.setTextColor(TEXT);journeyTime.setTextSize(19);}else{journeyTime.setTextColor(LIME);journeyTime.setTextSize(26);}
        String distanceDetail=simulating?getString(R.string.route_simulation_distance,simulationSpeed):getString(R.string.route_arrival_distance,arrival);
        journeyDistance.setText(String.format(Locale.getDefault(),"%s · %s%s",preferences.distance(remaining),distanceDetail,simulationPaused?" · "+getString(R.string.route_pause_simulation):""));
        if(active){String guidance=Store.guidance;java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("^(?:(?:In|În) )?([0-9.,]+)\\s*(km|m)\\s*[,·]?\\s*(.*)$",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(guidance);
            if(matcher.find()){maneuverDistance.setText(matcher.group(1)+" "+matcher.group(2));instruction.setText(matcher.group(3));}
            else{maneuverDistance.setText(simulating?R.string.route_simulation:R.string.route_gps_guidance);instruction.setText(guidance);}
            instruction.setContentDescription(guidance);
        }
    }
    private void startSimulation(){if(!canChangeMap())return;if(Store.route==null||Store.route.edges.isEmpty()){error(R.string.route_plan_before_simulation);return;}try{if(Store.route.authorisationStale(Store.authorisationRevision(),Store.privateAccessPermission))throw new IllegalStateException(getString(R.string.navigation_access_declaration_changed));cancelGps();routeSimulation=new RouteSimulation(Store.route);routeSimulation.setSpeed(simulationSpeed);simulatedProgress=new Progress(Store.route);simulating=true;Store.simulating=true;simulationPaused=false;Store.arrived=false;Store.travelled=0;Store.progressRoute=Store.route;Store.remaining=Store.route.metres;simulationClock=SystemClock.elapsedRealtime();Store.speed=simulationSpeed;simulationTick();map.locate(Store.lat,Store.lon);main.removeCallbacks(ticker);main.post(ticker);refresh();}catch(RuntimeException e){error(e.getMessage());}}
    void planner(){if(editable())journeyUi.edit();}
    String pointLabel(int n){return label(n);}
    void openTrucks(){truckDialog();}
    void openRoutingOptions(){routingOptions();}
    void useGpsOrigin(){startAtGps();}
    void planSelectedRoute(){calculate();}
    void routePointsChanged(){Store.clearInterruptedTrip(this);Store.routeChoices=Collections.emptyList();Store.route=null;previewPoint=null;saveEndpoints();refresh();map.invalidateRestrictions();}
    void chooseJourneyPoint(Graph.Node point,int target){
        int node=Store.coordinate(point.lat,point.lon,point.label);if(node<0){error(R.string.error_stop_outside_map);return;}
        if(target>=4){int index=target-4;if(index>=Store.viaPoints.size()){error(R.string.error_waypoint_missing);return;}Store.viaPoints.set(index,point);routePointsChanged();return;}
        if(target==2){if(Store.viaPoints.size()>=JourneyPlan.MAX_WAYPOINTS){error(R.string.error_max_waypoints);return;}Store.viaPoints.add(point);routePointsChanged();}else selectedPoint(node,target==1);
    }
    void previewPlace(Graph.Node point){previewPoint=point;browseAway=false;browseDeadline=0;recenter.reset();map.searchPoint(point.lat,point.lon);refresh();}

    private void refreshAlternatives(){
        if(alternativeTabs==null)return;List<Router.Route> choices=Store.routeChoices;
        boolean active=Store.navigating||simulating;alternativeTabs.setVisibility(active||Store.route==null?View.GONE:View.VISIBLE);
        if(shownChoices==choices&&shownChoice==Store.route)return;shownChoices=choices;shownChoice=Store.route;alternativeTabs.removeAllViews();
        if(Store.route==null)return;
        if(!choices.contains(Store.route))choices=Collections.singletonList(Store.route);
        for(Router.Route candidate:choices){
            boolean selected=candidate==Store.route;List<RouteSection> sections=RouteSection.of(candidate.edges,Store.truck);
            double access=RouteSection.metresOf(sections,RouteSection.AUTHORISED_ACCESS);
            double unverified=RouteSection.metresOf(sections,RouteSection.UNVERIFIED);
            double advisory=RouteSection.metresOfSeverity(sections,RouteSection.UNVERIFIED,RouteSection.ADVISORY);
            double uncertain=unverified-advisory;
            String restrictions=access>0||uncertain>0||advisory>0
                    ?getString(R.string.route_choice_restrictions,Math.round(access),Math.round(uncertain),Math.round(advisory))
                    :getString(R.string.route_choice_no_recorded_restrictions);
            Button tab=button(duration(candidate.seconds)+"\n"+NavigationDistance.format(candidate.metres)+"\n"+restrictions,()->{
                if(busy||Store.navigating||simulating)return;Store.route=candidate;if(Store.nativeRouter!=null)Store.nativeRouter.selectRoute(candidate);Store.arrived=false;map.fitRoute(candidate);refresh();
            });
            tab.setTextSize(11);tab.setTextColor(selected?0xff61a7f4:TEXT);tab.setTypeface(null,selected?Typeface.BOLD:Typeface.NORMAL);
            tab.setContentDescription(getString(selected?R.string.route_choice_selected:R.string.route_choice_alternative,duration(candidate.seconds),NavigationDistance.format(candidate.metres),restrictions));
            tab.setBackground(background(selected?(darkMode?0xff101010:0xffe0edfc):PANEL,14));
            alternativeTabs.addView(tab,new LinearLayout.LayoutParams(0,dp(76),1));
        }
    }
    private String profileName(){String raw=getSharedPreferences("settings",0).getString("vehicle_profiles_json","");String active=getSharedPreferences("settings",0).getString("vehicle_active_id","");String key=raw+active;if(!key.equals(cachedProfiles)){cachedProfiles=key;cachedProfileName=getString(R.string.profile_name_fallback);try{org.json.JSONArray a=new org.json.JSONArray(raw);for(int i=0;i<a.length();i++){org.json.JSONObject p=a.getJSONObject(i);if(p.optString("id").equals(active))cachedProfileName=p.optString("name",getString(R.string.profile_name_fallback));}}catch(org.json.JSONException ignored){}}return cachedProfileName;}
    private List<RouteSection> notices(){if(noticeRoute!=Store.route||noticeTruck!=Store.truck||noticeSections==null){noticeRoute=Store.route;noticeTruck=Store.truck;noticeSections=Store.route==null?Collections.emptyList():RouteSection.of(Store.route.edges,Store.truck);}return noticeSections;}
    private void browsingStarted(){locationFollowRequested=false;autoLocated=true;browseAway=true;browseDeadline=0;recenter.reset();recenter.setVisibility(View.VISIBLE);}
    private void browsingIdle(){if(!browseAway||previewPoint!=null)return;browseDeadline=SystemClock.elapsedRealtime()+30000;recenter.start(browseDeadline);}
    private boolean freshPosition(){return Double.isFinite(Store.lat)&&Double.isFinite(Store.lon)&&(simulating||Store.fixTime>0&&SystemClock.elapsedRealtime()-Store.fixTime>=0&&SystemClock.elapsedRealtime()-Store.fixTime<15000);}
    private void tryRecenter(boolean user){if(!hasWindowFocus()||previewPoint!=null)return;if(!map.ready()){browseDeadline=-1;return;}if(!freshPosition()){recenter.waiting();browseDeadline=-1;if(user){resumeTracking();Toast.makeText(this,R.string.status_waiting_fresh_gps_location,Toast.LENGTH_SHORT).show();}return;}browseAway=false;browseDeadline=0;recenter.stop();recenter.setVisibility(View.GONE);map.locate(Store.lat,Store.lon);updateMapControls();}
    public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(focus&&resumed&&browseAway&&previewPoint==null){if(browseDeadline==-1)tryRecenter(false);else if(browseDeadline<=SystemClock.elapsedRealtime())browsingIdle();}}
    private void saveEndpoints(){Store.saveEndpoints(this);}
    private void selectedPoint(int node,boolean starting){
        if(node<0){error(getString(R.string.coordinate_outside,Store.graph.name));return;}
        previewPoint=null;map.clearSearchPoint();
        if(starting){gpsAcquiring=false;Store.originFromGps=false;Store.start=node;Store.originChosen=true;}else{
            // A declaration names one destination's entrance: picking a different destination voids it.
            if(Store.end!=node)Store.revokeAuthorisation();
            Store.end=node;Store.destinationChosen=true;}Store.clearInterruptedTrip(this);Store.route=null;saveEndpoints();
        Graph.Node point=Store.graph.nodes[node];map.showPoint(point.lat,point.lon);refresh();
    }
    /** Feed or cache changed: redraw the markers and the journey line, nothing else. */
    void parkingUpdated(){
        if(isDestroyed())return;
        refreshParkingMarkers();
        refreshParkingText();
    }
    private void refreshParkingMarkers(){
        ParkingRepository repository=Store.parking;
        if(map==null||repository==null)return;
        ArrayList<ParkingOverlay.Row> rows=new ArrayList<>();
        for(Parking.Site site:repository.sites()){
            Parking.Availability availability=repository.availability(site);
            rows.add(new ParkingOverlay.Row(site.key(),availability.exact()?String.valueOf(availability.spaces):"P?",site.lat,site.lon,availability.exact()));
        }
        map.setParking(rows);
    }
    private void parkingTapped(String key){
        ParkingRepository repository=Store.parking;
        Parking.Site site=repository==null?null:repository.site(key);
        if(site!=null)ParkingUi.details(this,site);
    }
    /** Nearest verified site ahead on this trip, or null when nothing was proven reachable. */
    private ParkingAccess.Match nextParking(){
        ParkingAccess.Match best=null;double closest=Double.NaN;
        for(ParkingAccess.Match match:Store.parkingMatches){
            double left=match.remaining(Store.travelled);
            if(left<0||left>20_000)continue;
            if(best==null||left<closest){best=match;closest=left;}
        }
        return best;
    }
    private void refreshParkingText(){
        if(parkingNext==null)return;
        ParkingRepository repository=Store.parking;ParkingAccess.Match next=nextParking();
        if(repository==null||Store.route==null||next==null){parkingNext.setVisibility(View.GONE);return;}
        Parking.Availability availability=repository.availability(next.site);
        String text="P  "+next.site.name+" · "+NavigationDistance.format(next.remaining(Store.travelled))
            +(availability.exact()?" · "+availability.spaces+" spaces reported · "+Parking.age(System.currentTimeMillis(),availability.measured):"");
        parkingNext.setText(text);
        parkingNext.setContentDescription(getString(R.string.next_truck_parking_accessibility,text));
        parkingNext.setVisibility(View.VISIBLE);
    }
    private void parkingDetails(){
        ParkingAccess.Match next=nextParking();
        if(next!=null)ParkingUi.details(this,next.site);else ParkingUi.list(this);
    }
    private void refresh(){try{
        refreshParkingText();
        Graph g=Store.graph;boolean active=Store.navigating||simulating;
        boolean compact=getResources().getConfiguration().screenHeightDp<560&&getResources().getConfiguration().screenHeightDp>getResources().getConfiguration().screenWidthDp;
        instruction.setVisibility(compact&&!active?View.GONE:View.VISIBLE);status.setVisibility(compact&&!active&&!busy&&!MapDownloadService.running?View.GONE:View.VISIBLE);
        routeCard.setVisibility(active?View.GONE:View.VISIBLE);
        speedDisplay.setVisibility(active?View.VISIBLE:View.GONE);
        instruction.setTextSize(active?24:18);
        plan.setVisibility(View.GONE);
        boolean fresh=simulating||(Store.fixTime>0&&SystemClock.elapsedRealtime()-Store.fixTime>=0&&SystemClock.elapsedRealtime()-Store.fixTime<15000);
        SpannableString speedText=new SpannableString(getString(R.string.speed_display,fresh?String.format(Locale.getDefault(),"%.0f",Store.speed):"—"));
        int speedUnit=speedText.toString().indexOf("km/h");if(speedUnit>=0)speedText.setSpan(new android.text.style.RelativeSizeSpan(.55f),speedUnit,speedText.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);speedDisplay.setText(speedText);
        speedDisplay.setContentDescription(fresh?getString(R.string.speed_available_accessibility,Math.round(Store.speed)):getString(R.string.speed_unavailable_waiting_gps));
        if(displayedGraph!=g){
            if(displayedGraph==null||Store.display!=displayedDatabase||Store.nativeRouter==null)map.setGraph(g);else map.updateGraph(g);
            displayedGraph=g;displayedDatabase=Store.display;
        }
        plan.setEnabled(!busy&&!Store.installing&&!active);drive.setEnabled(!busy&&!Store.installing&&Store.route!=null);
        plan.setAlpha(plan.isEnabled()?1:.45f);drive.setAlpha(drive.isEnabled()?1:.45f);
        plan.setText(busy?R.string.route_working:g==null?R.string.route_download_maps:Store.route!=null?R.string.route_edit_route:R.string.route_plan);drive.setText(active?getString(R.string.action_stop_guidance):"➜");drive.setTextSize(active?14:30);drive.setContentDescription(active?getString(R.string.action_stop_guidance):g!=null&&g.demo?getString(R.string.action_simulate_route):getString(R.string.action_start_guidance));
        String routeMode=Store.deliveryAccess?getString(R.string.routing_delivery_mode):getString(routeModeString(Store.mode));
        modeButton.setText(routeMode);modeButton.setContentDescription(getString(R.string.route_preferences_accessibility,routeMode));
        Truck shown=Store.truck;
        // Loaded mass and permitted maximum mass are different facts; never collapse them.
        String name=profileName();profile.setText(name);profileWeight.setText(String.format(Locale.getDefault(),"%.1f t",shown.weight));profileHeight.setText(String.format(Locale.getDefault(),"%.2f m",shown.height));
        String profileIssue=Store.profileIssue(shown);
        String massWarning=!profileIssue.isEmpty()?ProfileIssuePresentation.text(this,shown,profileIssue):shown.exceedsPermittedMass()?getString(R.string.profile_mass_exceeds_permitted,shown.permittedWeight):"";
        profileMassWarning.setText(massWarning);profileMassWarning.setVisibility(massWarning.isEmpty()?View.GONE:View.VISIBLE);
        vehicleSummary.setContentDescription(getString(R.string.profile_summary_accessibility,name,shown.weight,shown.height,massWarning));
        if(g==null){status.setText(busy?getString(R.string.status_opening_maps):MapDownloadService.running?MapDownloadService.status:getString(R.string.status_no_maps_installed));endpoints.setText(R.string.download_country_start);endpoints.setVisibility(View.VISIBLE);instruction.setText(R.string.first_country);stats.setText(R.string.choose_first_map);map.updateGraph(null);map.invalidate();updateMapControls();return;}
        status.setText(MapDownloadService.running?MapDownloadService.status:g.demo?getString(R.string.status_development_fictional_map):Store.navigating?getString(R.string.status_gps_guidance_development):getString(R.string.status_offline_map,g.name));
        endpoints.setText(getString(R.string.route_endpoints,Store.originChosen?label(Store.start):getString(R.string.start_point_choose),Store.destinationChosen?label(Store.end):getString(R.string.destination_choose)));endpoints.setVisibility(active?View.GONE:View.VISIBLE);
        Router.Route r=Store.route;
        if(r!=null&&paintedRoute!=r){if(active)map.showRoute(r);else map.fitRoute(r);paintedRoute=r;}
        if(active){instruction.setText(simulating?getString(R.string.simulated_instruction,Store.guidance):Store.guidance);stats.setText(getString(R.string.route_remaining,NavigationDistance.format(Store.remaining),"",simulating?getString(R.string.route_simulation_gps_status):fresh?getString(R.string.route_gps_position_received):getString(R.string.route_waiting_fresh_fix)));}
        else if(r!=null){
        if(Store.relocationFrom()!=null&&map!=null)map.showRelocation(Store.relocatedAtOrigin(),Store.relocationFrom(),Store.relocationTo(),Store.relocationMetres());
        else if(map!=null)map.clearRelocation();
        String caution=Store.restrictionWarning();
        instruction.setText(Store.arrived?(g.demo?getString(R.string.training_route_complete):nonempty(Store.guidance,getString(R.string.navigation_route_end))):r.edges.isEmpty()?getString(R.string.route_start_destination_same):ArrivalPresentation.routeText(this,Store.arrivalStatus(),caution));
        List<RouteSection> sections=notices();
        double accessMetres=RouteSection.metresOf(sections,RouteSection.AUTHORISED_ACCESS);
        double unverifiedMetres=RouteSection.metresOf(sections,RouteSection.UNVERIFIED);
        double advisoryMetres=RouteSection.metresOfSeverity(sections,RouteSection.UNVERIFIED,RouteSection.ADVISORY);
        double limitMetres=unverifiedMetres-advisoryMetres;
        String routeChoice=r.nativeGeometry()?(Store.deliveryAccess?getString(R.string.route_summary_delivery):getString(routeModeString(Store.mode))):getString(R.string.route_summary_tagged);
        StringBuilder summary=new StringBuilder(getString(R.string.route_summary_format,NavigationDistance.format(r.metres),duration(r.seconds),routeChoice));
        if(accessMetres>0)summary.append(getString(R.string.route_summary_access_restricted,Math.round(accessMetres)));
        if(limitMetres>0)summary.append(getString(R.string.route_summary_unverified,Math.round(limitMetres)));
        if(advisoryMetres>0)summary.append(getString(R.string.route_summary_advisory,Math.round(advisoryMetres)));
        summary.append(getString(R.string.route_summary_map_disclaimer));
        stats.setText(summary.toString());
        // The painted route and this explanation come from the same sections; tapping explains them.
        boolean explained=accessMetres>0||unverifiedMetres>0;
        stats.setContentDescription(explained?getString(R.string.route_summary_restrictions_content_description):null);
        stats.setOnClickListener(explained?v->routeSectionsDialog(sections):null);
        stats.setClickable(explained);}
        else{instruction.setText(R.string.select_destination);stats.setText(R.string.choose_destination);map.clearRoute();paintedRoute=null;}
        map.updateGraph(Store.graph);
        if(active&&!guidanceWasActive&&fresh){map.locate(Store.lat,Store.lon);guidanceWasActive=true;}
        if(active&&fresh)map.updatePosition();else map.invalidate();
        if(!active)guidanceWasActive=false;updateMapControls();
        if(active)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }finally{refreshInterface();updateLocationStatus();}}
    private String label(int node){if(Store.graph==null)return "";Graph.Node n=Store.graph.nodes[node];return n.label.isEmpty()?String.format(Locale.ROOT,"%.5f, %.5f",n.lat,n.lon):n.label;}
    private String nonempty(String value,String fallback){return value==null||value.isEmpty()?fallback:value;}
    private String duration(double seconds){long minutes=Math.max(1,Math.round(seconds/60));return minutes<60?String.format(Locale.getDefault(),"%d min",minutes):String.format(Locale.getDefault(),"%d h %02d min",minutes/60,minutes%60);}
    private int routeModeString(RoutingMode mode){
        switch(mode){case SHORTEST:return R.string.routing_mode_shortest;case EASIEST:return R.string.routing_mode_easiest;case FASTEST:return R.string.routing_mode_fastest;default:return R.string.routing_mode_economical;}
    }
    private int routeModeDetailString(RoutingMode mode){
        switch(mode){case SHORTEST:return R.string.routing_detail_shortest;case EASIEST:return R.string.routing_detail_easiest;case FASTEST:return R.string.routing_detail_fastest;default:return R.string.routing_detail_economical;}
    }
    /** Per-section explanations for the colours painted on the route. */
    private void routeSectionsDialog(List<RouteSection> sections){
        StringBuilder text=new StringBuilder();
        for(RouteSection section:sections){
            if(section.cls==RouteSection.PLAIN)continue;
            text.append(getString(R.string.route_section_distance,RouteSectionPresentation.label(this,section.cls,section.severity),Math.round(section.metres)));
            if(!section.evidence.isEmpty())text.append(getString(R.string.route_section_recorded,section.evidence));
            text.append(RouteSectionPresentation.explanation(this,section.cls,section.severity)).append("\n\n");
        }
        if(text.length()==0)text.append(getString(R.string.route_sections_empty)).append("\n\n");
        text.append(getString(R.string.route_summary_map_disclaimer).trim());
        new AlertDialog.Builder(this).setTitle(R.string.route_restrictions_title).setMessage(text.toString())
            .setPositiveButton(R.string.action_close_dialog,null).show();
    }
    boolean canChangeMap(){if(busy||Store.installing){Toast.makeText(this,R.string.route_wait_for_load,Toast.LENGTH_SHORT).show();return false;}if(Store.navigating||simulating){error(R.string.route_stop_before_change);return false;}return true;}
    private boolean editable(){if(busy||Store.installing){Toast.makeText(this,R.string.route_wait_for_load,Toast.LENGTH_SHORT).show();return false;}if(Store.navigating||simulating){error(R.string.route_stop_before_change);return false;}if(Store.graph==null){mapsDialog();return false;}return true;}
    private void choosePoint(int node){new AlertDialog.Builder(this).setTitle(label(node)).setItems(new String[]{getString(R.string.route_set_destination),getString(R.string.route_set_start),getString(R.string.route_save_favourite)},(d,i)->{if(i==2){saveFavourite(node);return;}selectedPoint(node,i==1);}).show();}
    private void search(boolean starting){if(editable())journeyUi.search(starting?1:0);}
    private void truckDialog(){if(canChangeMap())pages.profiles();}
    private void legacyTruckDialog(){
        ScrollView scroll=new ScrollView(this);LinearLayout box=vertical();box.setPadding(dp(20),dp(8),dp(20),dp(8));scroll.addView(box);
        box.setFocusableInTouchMode(true);box.requestFocus();
        Truck t=Store.truck;String[] names={"Height (m)","Width (m)","Length (m)","Loaded gross weight now (t)","Permitted maximum mass (t), from the vehicle documents","Maximum loaded axle weight (t)","Total truck and trailer axles","Maximum speed (km/h)"};double[] values={t.height,t.width,t.length,t.weight,t.permittedWeight,t.axleWeight,t.axles,t.topSpeed};EditText[] fields=new EditText[8];
        for(int i=0;i<8;i++){box.addView(text(names[i],13,MUTED));fields[i]=new EditText(this);fields[i].setContentDescription(names[i]);fields[i].setSelectAllOnFocus(true);fields[i].setSingleLine();fields[i].setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);fields[i].setText(java.math.BigDecimal.valueOf(values[i]).stripTrailingZeros().toPlainString());box.addView(fields[i]);}
        String[] options={"General hazardous material","Avoid toll roads","Avoid ferries","Avoid unpaved roads"};boolean[] checks={(t.hazardousLoad&1)!=0,t.avoidTolls,t.avoidFerries,t.avoidUnpaved};CheckBox[] boxes=new CheckBox[4];
        for(int i=0;i<4;i++){boxes[i]=new CheckBox(this);boxes[i].setText(options[i]);boxes[i].setChecked(checks[i]);box.addView(boxes[i]);}
        CheckBox water=new CheckBox(this),explosives=new CheckBox(this);
        water.setText(R.string.load_water);water.setChecked((t.hazardousLoad&2)!=0);box.addView(water);
        explosives.setText(R.string.load_explosives);explosives.setChecked((t.hazardousLoad&4)!=0);box.addView(explosives);
        box.addView(text("ADR tunnel restriction code for this load",13,MUTED));
        Spinner tunnel=new Spinner(this);tunnel.setContentDescription("ADR tunnel restriction code");tunnel.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"No tunnel code","B · Exclude B, C, D, E","C · Exclude C, D, E","D · Exclude D, E","E · Exclude E"}));tunnel.setSelection(t.tunnelCode==0?0:t.tunnelCode-1);box.addView(tunnel);
        box.addView(text("Use the code applicable to the actual consignment. Mixed and quantity-dependent codes must be resolved from the transport documents. Mapped unknown tunnel categories are excluded when a code is selected.",12,MUTED));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Your truck").setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();dialog.show();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{
            double[] n=new double[8];for(int i=0;i<8;i++)n[i]=Double.parseDouble(fields[i].getText().toString().replace(',','.'));
            if(n[6]!=Math.rint(n[6]))throw new IllegalArgumentException("Axle count must be a whole number.");
            int load=(boxes[0].isChecked()?1:0)|(water.isChecked()?2:0)|(explosives.isChecked()?4:0),code=tunnel.getSelectedItemPosition()==0?0:tunnel.getSelectedItemPosition()+1;
            Store.saveTruck(this,new Truck(n[0],n[1],n[2],n[3],n[5],load!=0,boxes[1].isChecked(),boxes[2].isChecked(),boxes[3].isChecked(),(int)n[6],n[7],load,code,n[4]));dialog.dismiss();
            if(map!=null)map.invalidateRestrictions();
            refresh();
        }catch(IllegalArgumentException e){error(e instanceof NumberFormatException?"Enter all measurements as numbers.":e.getMessage());}});
    }
    void routingOptions(){if(canChangeMap())journeyUi.routeOptions();}
    void accessPermissions(){
        if(!canChangeMap())return;
        LinearLayout box=vertical();box.setPadding(dp(20),dp(8),dp(20),dp(8));RadioGroup choices=new RadioGroup(this);
        for(RoutingMode option:RoutingMode.values()){
            RadioButton choice=new RadioButton(this);choice.setId(900+option.ordinal());
            choice.setText(getString(R.string.routing_choice,getString(routeModeString(option)),getString(routeModeDetailString(option))));choices.addView(choice);
        }
        choices.check(900+Store.mode.ordinal());box.addView(choices);
        CheckBox access=new CheckBox(this);access.setText(R.string.delivery_permission);access.setChecked(Store.deliveryAccess);box.addView(access);
        CheckBox privateAccess=new CheckBox(this);
        privateAccess.setText(R.string.route_private_property_permission);
        privateAccess.setChecked(Store.privateAccessPermission);box.addView(privateAccess);
        box.addView(text(getString(R.string.route_access_explanation,2),12,MUTED));
        ScrollView scroll=new ScrollView(this);scroll.addView(box);
        new AlertDialog.Builder(this).setTitle(R.string.route_preferences_title).setView(scroll).setNegativeButton(R.string.action_cancel,null).setPositiveButton(R.string.action_save,(d,w)->{
            Store.mode=RoutingMode.values()[choices.indexOfChild(choices.findViewById(choices.getCheckedRadioButtonId()))];Store.deliveryAccess=access.isChecked();
            // Revoke before dropping the route, so the revocation can still see what it invalidates.
            if(Store.privateAccessPermission&&!privateAccess.isChecked())Store.revokeAuthorisation();
            Store.route=null;
            Store.privateAccessPermission=privateAccess.isChecked();
            getSharedPreferences("settings",0).edit().putString("routing_mode",Store.mode.name()).apply();
            if(map!=null)map.invalidateRestrictions();
            refresh();
        }).show();
    }
    private void calculate(){
        if(!editable())return;
        if(gpsAcquiring&&!accuratePosition()){error(getString(R.string.route_waiting_gps_origin));return;}
        if(!Store.originChosen||!Store.destinationChosen){planner();return;}
        if(Store.originFromGps){if(!accuratePosition()){error(getString(R.string.route_fresh_gps_origin));return;}int current=Store.coordinate(Store.lat,Store.lon);if(current<0){error(R.string.route_location_outside_map);return;}Store.start=current;saveEndpoints();}
        final Graph graph=Store.graph;final int start=Store.start,end=Store.end;final Truck truck=Store.truck;
        if(!JourneyPlan.hasTravel(graph.nodes[start],graph.nodes[end],Store.viaPoints)){
            new AlertDialog.Builder(this).setTitle(R.string.route_same_endpoint_title).setMessage(R.string.route_same_endpoint_message)
                .setNegativeButton(R.string.screen_title_edit_route,(dialog,which)->planner()).setPositiveButton(R.string.search_title_destination,(dialog,which)->journeyUi.search(0)).show();return;
        }
        final int generation=++requestGeneration;
        busy=true;Store.routeChoices=Collections.emptyList();Store.route=null;refresh();Store.worker.execute(()->{
            try{List<Router.Route> choices=Store.calculateRoutes(graph,start,end,truck);Router.Route route=choices.get(0);main.post(()->{if(generation!=requestGeneration||isDestroyed())return;Store.routeChoices=choices;Store.route=route;Store.arrived=false;busy=false;if(route.nativeGeometry())map.fitRoute(route);refresh();if(resumeAfterPlan){resumeAfterPlan=false;startGuidance();}});}
            catch(RuntimeException|LinkageError e){main.post(()->{if(generation!=requestGeneration||isDestroyed())return;Store.route=null;busy=false;resumeAfterPlan=false;refresh();error(getString(R.string.route_plan_failed,errorDetail(e)));});}
        });
    }
    private void startGuidance(){
        if(Store.navigating||simulating){stopGuidance();return;}
        if(Store.installing)return;
        String profileIssue=Store.profileIssue(Store.truck);if(!profileIssue.isEmpty()){error(ProfileIssuePresentation.text(this,Store.truck,profileIssue));pages.profiles();return;}
        if(Store.route==null||Store.route.edges.isEmpty()){error(R.string.route_different_endpoints_required);return;}
        if(Store.graph.demo){startSimulation();return;}
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(Build.VERSION.SDK_INT>=33?new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.POST_NOTIFICATIONS}:new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},100);return;
        }
        LocationManager manager=getSystemService(LocationManager.class);
        if(!locationAvailable(manager)){error(R.string.navigation_enable_location);return;}
        // The planned origin must match the driver's current location before guidance.
        Location fix=LocationFixes.current(manager,50,10000);
        if(fix==null){
            error(R.string.navigation_fresh_fix_select_origin);return;
        }
        Graph.Node origin=Store.graph.nodes[Store.start];
        if(Geo.distance(fix.getLatitude(),fix.getLongitude(),origin.lat,origin.lon)>250){error(R.string.navigation_route_start_too_far);return;}
        new AlertDialog.Builder(this).setTitle(R.string.navigation_development_title).setMessage(R.string.navigation_development_warning)
            .setNegativeButton(R.string.action_cancel,null).setPositiveButton(R.string.action_start_guidance,(d,w)->{Location latest=LocationFixes.current(manager,50,10000);if(latest==null){error(R.string.navigation_waiting_for_fresh_accurate_location);return;}Graph.Node currentOrigin=Store.graph.nodes[Store.start];if(Geo.distance(latest.getLatitude(),latest.getLongitude(),currentOrigin.lat,currentOrigin.lon)>250){error(R.string.navigation_location_moved_from_start);return;}cancelGps();LocationFixes.save(latest);Store.bearing=latest.hasBearing()&&latest.hasSpeed()&&latest.getSpeed()>=1?latest.getBearing():Double.NaN;map.locate(Store.lat,Store.lon);Store.guidance=getString(R.string.navigation_waiting_for_gps);try{startForegroundService(new Intent(this,NavigationService.class));previouslyActive=true;}catch(RuntimeException failure){resumeTracking();error(getString(R.string.navigation_start_failed,errorDetail(failure)));}}).show();
    }
    private void offerInterruptedTrip(){
        if(isDestroyed()||resumePromptShowing||!Store.interruptedTripPending(this)||Store.graph==null)return;
        resumePromptShowing=true;
        String profileIssue=Store.profileIssue(Store.truck);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(R.string.trip_resume_title)
            .setMessage(!profileIssue.isEmpty()?ProfileIssuePresentation.text(this,Store.truck,profileIssue):getString(R.string.trip_resume_message,truckSummary()))
            .setNegativeButton(R.string.trip_discard,(d,w)->discardInterruptedTrip())
            .setNeutralButton(R.string.trip_later,null)
            .setPositiveButton(!profileIssue.isEmpty()?R.string.trip_review_truck:R.string.trip_check_gps_resume,(d,w)->{
                if(!Store.profileIssue(Store.truck).isEmpty()){pages.profiles();return;}
                resumeInterruptedTrip();
            }).create();
        dialog.setOnDismissListener(d->resumePromptShowing=false);dialog.show();
    }
    private void discardInterruptedTrip(){Store.clearInterruptedTrip(this);resumeRequested=false;resumeAfterPlan=false;Store.originChosen=false;Store.originFromGps=false;Store.destinationChosen=false;Store.viaPoints.clear();Store.route=null;Store.routeChoices=Collections.emptyList();saveEndpoints();refresh();}
    private String truckSummary(){Truck t=Store.truck;StringBuilder adr=new StringBuilder();if((t.hazardousLoad&1)!=0)adr.append(getString(R.string.load_general_hazard)).append(", ");if((t.hazardousLoad&2)!=0)adr.append(getString(R.string.load_water)).append(", ");if((t.hazardousLoad&4)!=0)adr.append(getString(R.string.load_explosives)).append(", ");if(adr.length()>0)adr.setLength(adr.length()-2);else adr.append(getString(R.string.load_none));return getString(R.string.trip_truck_summary,profileName(),t.weight,t.permittedWeight,t.height,t.width,t.length,t.axleWeight,t.axles,t.topSpeed,adr,t.tunnelCode==0?getString(R.string.profile_tunnel_none):Character.toString((char)('A'+t.tunnelCode-1)));}
    private void resumeInterruptedTrip(){
        if(!Store.profileIssue(Store.truck).isEmpty()){pages.profiles();return;}
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){requestPermissions(Build.VERSION.SDK_INT>=33?new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.POST_NOTIFICATIONS}:new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},103);return;}
        LocationManager manager=getSystemService(LocationManager.class);
        if(!locationAvailable(manager)){offerLocationSettings();return;}
        Location fix=LocationFixes.current(manager,50,10000);
        if(fix==null){resumeRequested=true;gpsAcquiring=true;resumeTracking();error(R.string.trip_waiting_for_fresh_fix);return;}
        LocationFixes.save(fix);int origin=Store.coordinate(fix.getLatitude(),fix.getLongitude());
        if(origin<0){error(R.string.trip_location_outside_map);return;}
        int passed=Math.min(Store.interruptedPassedStops(this),Store.viaPoints.size());if(passed>0)Store.viaPoints.subList(0,passed).clear();
        Store.start=origin;Store.originChosen=true;Store.originFromGps=true;Store.route=null;Store.routeChoices=Collections.emptyList();
        if(!Store.destinationChosen||!JourneyPlan.hasTravel(Store.graph.nodes[Store.start],Store.graph.nodes[Store.end],Store.viaPoints)){error(R.string.trip_no_remaining_travel);return;}
        saveEndpoints();resumeAfterPlan=true;calculate();
    }
    public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(request==100){if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)startGuidance();else error(R.string.navigation_precise_location_guidance_required);}else if(request==103){if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)resumeInterruptedTrip();else error(R.string.navigation_precise_location_resume_required);}else if(request==101||request==102){if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED){if(request==101)startAtGps();else requestCurrentLocation();}else{updateLocationStatus();error(R.string.navigation_precise_location_follow_required);}}}

    private void stopGuidance(){Store.clearInterruptedTrip(this);Store.arrived=false;Store.progressRoute=null;Store.travelled=0;Store.speed=0;Store.remaining=Store.route==null?0:Store.route.metres;paintedRoute=null;simulating=false;simulationPaused=false;routeSimulation=null;Store.simulating=false;stopService(new Intent(this,NavigationService.class));Store.navigating=false;Store.lat=Double.NaN;Store.lon=Double.NaN;Store.bearing=Double.NaN;Store.fixTime=0;Store.fixAccuracy=Float.NaN;browsingFix=null;refresh();resumeTracking();}
    private void simulationTick(){
        if(!simulating||routeSimulation==null)return;
        if(Store.route!=routeSimulation.route||Store.route.authorisationStale(Store.authorisationRevision(),Store.privateAccessPermission)){stopGuidance();return;}
        long now=SystemClock.elapsedRealtime();double elapsed=Math.max(0,(now-simulationClock)/1000.0);simulationClock=now;
        if(!simulationPaused)routeSimulation.advance(elapsed);
        Graph.Node point=routeSimulation.position();Store.lat=point.lat;Store.lon=point.lon;Store.bearing=routeSimulation.bearing();Store.fixTime=now;Store.fixAccuracy=0;Store.speed=simulationPaused||routeSimulation.arrived()?0:simulationSpeed;
        Progress.Fix fix=simulatedProgress.update(Store.lat,Store.lon);Store.travelled=routeSimulation.distance();Store.remaining=Math.max(0,Store.route.metres-Store.travelled);
        Store.guidance=NavigationDistance.format(fix.toManeuver)+" · "+Store.route.instruction(fix.maneuver);
        if(routeSimulation.arrived()){Store.arrived=true;Store.guidance=getString(R.string.route_simulation_complete);simulationPaused=true;}
    }
    void mapsDialog(){pages.maps();}
    void importCartography(){if(canChangeMap())startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),204);}
    private void cartographyInstalled(){if(isDestroyed())return;RoadMapView.resetTiles();RoadMapView old=map;buildUi();map.setGraph(Store.graph);map.restoreCamera(old);old.dispose();busy=false;refresh();}
    void legacyMapsDialog(){
        TextView progress=text(MapDownloadService.status,14,TEXT);progress.setPadding(dp(24),dp(12),dp(24),dp(12));
        Dialog dialog=new Dialog(this);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);LinearLayout box=vertical();box.setBackgroundColor(BG);box.setPadding(dp(16),dp(12),dp(16),dp(12));TextView title=text("Offline maps",20,TEXT);box.addView(title);progress.setMaxLines(4);box.addView(progress);
        ListView list=new ListView(this);list.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,new String[]{"Download Romania","Choose another country","Download all Europe","Pause downloads","Downloaded countries","Import country (.eurorig)","Map download source","Select installed map","Installed map details"}));box.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout actions=new LinearLayout(this);actions.addView(button("Download status",()->{dialog.dismiss();error(MapDownloadService.status);}),new LinearLayout.LayoutParams(0,dp(48),1));actions.addView(button("Close",dialog::dismiss),new LinearLayout.LayoutParams(0,dp(48),1));box.addView(actions);dialog.setContentView(box);
        list.setOnItemClickListener((parent,view,w,id)->{dialog.dismiss();
                if(w==0)download("romania");
                else if(w==1)countryCatalogue();
                else if(w==2)download(null);
                else if(w==3){if(MapDownloadService.running)startService(new Intent(this,MapDownloadService.class).setAction("PAUSE"));else Toast.makeText(this,"No download is running",Toast.LENGTH_SHORT).show();}
                else if(w==4)downloadedCountries();
                else if(w==5){if(canChangeMap())startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),202);}
                else if(w==6)mapSource();
                else if(w==7)selectInstalledMap();else installedMapDetails();
        });dialog.show();dialog.getWindow().setLayout(-1,Math.round(getResources().getDisplayMetrics().heightPixels*.8f));
        Runnable tick=new Runnable(){public void run(){if(dialog.isShowing()){progress.setText(MapDownloadService.status);main.postDelayed(this,1000);}}};main.post(tick);dialog.setOnDismissListener(d->main.removeCallbacks(tick));
    }
    void download(String country){
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
    void downloadedCountries(){
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
    void installedMapDetails(){
        Graph graph=Store.graph;if(graph==null){error("No maps installed yet.");return;}
        new AlertDialog.Builder(this).setTitle("Installed map details").setMessage(detailsText(this,graph,Store.display)).setPositiveButton("Close",null).show();
    }
    /** The installed-map details text, kept out of the dialog so a device check can read it. */
    static String detailsText(Context context,Graph graph,DisplayDatabase display){
        StringBuilder details=new StringBuilder("Map: ").append(graph.name).append('\n').append(graph.attribution);
        if(display!=null){
            double[] bounds=display.bounds;
            details.append("\nSource dates:\n").append(display.sourceDates)
                .append(String.format(Locale.ROOT,"\n\nData bounds (south, west, north, east): %.5f, %.5f, %.5f, %.5f",bounds[0],bounds[1],bounds[2],bounds[3]));
        }else details.append("\nSource date: ").append(graph.date).append("\nDetailed coverage metadata is unavailable for this package.");
        details.append("\n\n").append(context.getString(R.string.map_coverage_warning))
            .append("\n\n").append(context.getString(R.string.map_route_coverage_warning));
        return details.toString();
    }
    void selectInstalledMap(){
        if(!canChangeMap())return;
        List<RegionPackages.InstalledRegion> regions=RegionPackages.installed(this);
        if(regions.isEmpty()){error("No complete installed maps can be selected.");return;}
        String[] choices=regions.stream().map(RegionPackages.InstalledRegion::toString).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle("Select installed map").setItems(choices,(dialog,index)->{
            RegionPackages.InstalledRegion chosen=regions.get(index);
            if(chosen.active){installedMapDetails();return;}
            new AlertDialog.Builder(this).setTitle("Switch offline map?")
                .setMessage("Use "+chosen.name+" from "+chosen.date+"? The other installed maps stay on this device. Current route endpoints and interrupted-trip progress will clear.")
                .setNegativeButton("Cancel",null).setPositiveButton("Switch map",(confirm,which)->{
                    if(!canChangeMap()||!Store.beginInstall())return;busy=true;refresh();
                    Store.worker.execute(()->{try{Graph graph=RegionPackages.select(this,chosen.id);main.post(()->installGraph(graph));}
                        catch(Exception|LinkageError failure){main.post(()->{busy=false;if(!isDestroyed()){refresh();error("Map selection failed: "+failure.getMessage());}});}
                        finally{Store.installing=false;}});
                }).show();
        }).setNegativeButton("Close",null).show();
    }
    private void installGraph(Graph g){Store.clearInterruptedTrip(this);Store.originChosen=false;Store.originFromGps=false;Store.destinationChosen=false;Store.viaPoints.clear();Store.graph=g;Store.route=null;Store.start=0;Store.end=Math.min(3,g.nodes.length-1);if(Store.nativeRouter!=null)Store.setRegionEndpoints();Store.lat=Double.NaN;Store.lon=Double.NaN;busy=false;if(!isDestroyed()){map.setGraph(g);refresh();}}
    protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;
        final android.net.Uri uri=data.getData();if(request==203){pages.photoResult(uri);return;}
        if(request==204){
            if(!canChangeMap()||!Store.beginInstall())return;busy=true;refresh();Store.mapWorker.execute(()->{
                try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("Cannot open map detail");Cartography.install(this,in);main.post(this::cartographyInstalled);}
                catch(IOException|RuntimeException e){main.post(()->{busy=false;if(!isDestroyed()){refresh();error("Map detail import: "+e.getMessage());}});}finally{Store.installing=false;}
            });return;
        }
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
        Router.Route r=Store.route;if(r==null){error(R.string.route_different_endpoints_required);return;}
        ArrayList<String> steps=new ArrayList<>();steps.add(r.graph.demo?getString(R.string.route_fictional_training_route):getString(R.string.route_prototype_instruction));
        if(!r.edges.isEmpty()){int index=0;while(index<r.edges.size()){steps.add(String.format(Locale.getDefault(),"%s · %s",NavigationDistance.format(r.cumulative[index]),r.instruction(index)));index=r.nextManeuver(index);}steps.add(ArrivalPresentation.text(this,Store.arrivalStatus()));}
        new AlertDialog.Builder(this).setTitle(R.string.route_instructions_title).setItems(steps.toArray(new String[0]),null).setNegativeButton(R.string.action_close_dialog,null).setPositiveButton(R.string.action_export_gpx,(d,w)->{
            exporting=r;startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/gpx+xml").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"EuroRig-route.gpx"),201);
        }).show();
    }
    private void moreDialog(){new AlertDialog.Builder(this).setTitle("EuroRig").setItems(new String[]{getString(R.string.more_menu_change_start),getString(R.string.more_menu_start_at_gps),getString(R.string.more_menu_fit_map),getString(R.string.more_menu_favourites),getString(Store.voiceEnabled?R.string.menu_mute_voice:R.string.menu_enable_voice),getString(R.string.more_menu_appearance),getString(R.string.more_menu_about)},(d,i)->{
        if(i==0)search(true);else if(i==1)startAtGps();else if(i==2){if(Store.graph==null)mapsDialog();else map.fit();}else if(i==3)favourites();else if(i==4){
            Store.voiceEnabled=!Store.voiceEnabled;getSharedPreferences("settings",0).edit().putBoolean("voice",Store.voiceEnabled).apply();
            if(!Store.voiceEnabled&&Store.navigating)startService(new Intent(this,NavigationService.class).setAction("MUTE"));
            Toast.makeText(this,getString(Store.voiceEnabled?R.string.voice_enabled_next_trip:R.string.voice_visual_guidance_only),Toast.LENGTH_SHORT).show();
        }else if(i==5)appearanceDialog();else about();
    }).show();}
    private void updateMapControls(){
        boolean hasMap=Store.graph!=null;
        zoomInButton.setEnabled(hasMap&&!busy);zoomOutButton.setEnabled(hasMap&&!busy);
        mapControlMenu.toggle.setEnabled(hasMap&&!busy);mapControlMenu.camera.setEnabled(hasMap&&!busy);
        for(Button control:new Button[]{zoomInButton,zoomOutButton,mapControlMenu.camera})control.setAlpha(control.isEnabled()?1:.45f);
    }
    private void mapGps(){
        if(Store.navigating||simulating){if(freshPosition())tryRecenter(true);else Toast.makeText(this,R.string.route_waiting_fresh_fix,Toast.LENGTH_SHORT).show();return;}
        requestCurrentLocation();
    }
    /** Recenter without changing a manually chosen origin, destination or route. */
    private void requestCurrentLocation(){
        locationFollowRequested=true;
        if(!hasLocationPermission()){requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},102);return;}
        LocationManager locations=getSystemService(LocationManager.class);
        if(!locationAvailable(locations)){offerLocationSettings();return;}
        resumeTracking();if(freshPosition()){browseAway=false;browseDeadline=0;recenter.stop();recenter.setVisibility(View.GONE);map.locate(Store.lat,Store.lon);locationFollowRequested=false;autoLocated=true;}updateLocationStatus();
    }
    private void offerLocationSettings(){new AlertDialog.Builder(this).setTitle(R.string.navigation_enable_location_title).setMessage(R.string.navigation_enable_location_detail).setNegativeButton(R.string.action_later,null).setPositiveButton(R.string.action_location_settings,(d,w)->startActivity(new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))).show();}
    private boolean accuratePosition(){return freshPosition()&&Float.isFinite(Store.fixAccuracy)&&Store.fixAccuracy<=50&&SystemClock.elapsedRealtime()-Store.fixTime<=10000;}
    private void startAtGps(){
        if(!editable())return;
        if(Store.graph.demo){error(R.string.error_training_map_gps);return;}
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},101);return;}
        if(!locationAvailable(getSystemService(LocationManager.class))){offerLocationSettings();return;}
        gpsAcquiring=true;locationFollowRequested=true;resumeTracking();
        if(accuratePosition())applyGpsOrigin();else Toast.makeText(this,R.string.status_waiting_accurate_route_origin,Toast.LENGTH_LONG).show();updateLocationStatus();
    }
    private void applyGpsOrigin(){
        int n=Store.coordinate(Store.lat,Store.lon);gpsAcquiring=false;
        if(n<0){error(R.string.route_location_outside_map);return;}
        Store.start=n;Store.originChosen=true;Store.originFromGps=true;Store.route=null;saveEndpoints();refresh();if(journeyUi!=null&&journeyUi.showing())journeyUi.edit();
    }
    /** Foreground browsing subscribes to platform GPS/network, without clearing the chosen route. */
    private void resumeTracking(){
        if(!resumed||Store.graph==null||Store.graph.demo||Store.navigating||simulating)return;
        if(pendingGps!=null)return;
        if(!hasLocationPermission()){updateLocationStatus();return;}
        LocationManager locations=getSystemService(LocationManager.class);
        if(!locationAvailable(locations)){updateLocationStatus();return;}
        LocationListener listener=new LocationListener(){
            public void onLocationChanged(Location l){
                if(isDestroyed()||pendingGps!=this||Store.navigating||simulating)return;
                if(!LocationFixes.usable(l,5000,15000))return;
                // A coarse network result must not pull a fresh, accurate GPS marker sideways.
                if(LocationFixes.usable(browsingFix,5000,10000)&&l.getAccuracy()>Math.max(50,browsingFix.getAccuracy()*1.5f))return;
                browsingFix=new Location(l);LocationFixes.save(l);
                if((gpsAcquiring||resumeRequested)&&accuratePosition()){if(gpsAcquiring)applyGpsOrigin();if(resumeRequested){resumeRequested=false;resumeInterruptedTrip();}}
                if(map.ready()&&(locationFollowRequested||!autoLocated&&!browseAway&&previewPoint==null&&Store.route==null)){
                    browseAway=false;browseDeadline=0;recenter.stop();recenter.setVisibility(View.GONE);map.locate(Store.lat,Store.lon);locationFollowRequested=false;autoLocated=true;
                }
                map.updatePosition();updateLocationStatus();if(browseAway&&browseDeadline==-1&&(journeyUi==null||!journeyUi.showing()))tryRecenter(false);
            }
            public void onProviderEnabled(String p){updateLocationStatus();}public void onProviderDisabled(String p){updateLocationStatus();}public void onStatusChanged(String p,int state,Bundle extras){}
        };
        pendingGps=listener;
        if(!LocationFixes.request(locations,listener)){pendingGps=null;updateLocationStatus();return;}
        Location known=LocationFixes.current(locations,5000,15000);if(known!=null)listener.onLocationChanged(known);updateLocationStatus();
    }
    private void updateLocationStatus(){
        if(locationStatus==null)return;
        String text;
        boolean precise=checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
        if(!hasLocationPermission())text=getString(R.string.location_permission_required);
        else if(!locationAvailable(getSystemService(LocationManager.class)))text=getString(R.string.location_device_off);
        else if(freshPosition()&&Float.isFinite(Store.fixAccuracy))text=getString(R.string.location_accuracy_format,getString(precise&&Store.fixAccuracy<=50?R.string.location_gps_ready:R.string.location_approximate),Store.fixAccuracy,gpsAcquiring?getString(R.string.location_waiting_precise_route_start):"");
        else text=getString(precise?R.string.location_waiting_clear_sky:R.string.location_waiting_approximate);
        locationStatus.setText(text);locationStatus.setContentDescription(getString(R.string.location_tap_to_recenter,text));
    }
    private boolean hasLocationPermission(){return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED||checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED;}
    private void cancelGps(){if(pendingGps!=null){try{getSystemService(LocationManager.class).removeUpdates(pendingGps);}catch(SecurityException ignored){}pendingGps=null;}gpsAcquiring=false;}
    private void saveFavourite(int n){
        Graph.Node p=Store.graph.nodes[n];String value=p.lat+","+p.lon+"|"+label(n);
        android.content.SharedPreferences prefs=getSharedPreferences("favourites",0);Set<String> set=new HashSet<>(prefs.getStringSet("places",Collections.emptySet()));set.add(value);prefs.edit().putStringSet("places",set).apply();Toast.makeText(this,"Favourite saved on this device",Toast.LENGTH_SHORT).show();
    }
    void favourites(){
        if(!editable())return;
        ArrayList<String> items=new ArrayList<>(getSharedPreferences("favourites",0).getStringSet("places",Collections.emptySet()));Collections.sort(items);
        if(items.isEmpty()){error("Tap a map point and choose Save favourite.");return;}
        String[] labels=new String[items.size()];for(int i=0;i<labels.length;i++)labels[i]=items.get(i).substring(items.get(i).indexOf('|')+1);
        new AlertDialog.Builder(this).setTitle("Local favourites").setItems(labels,(d,i)->{
            String[] coordinates=items.get(i).substring(0,items.get(i).indexOf('|')).split(",");int n=Store.coordinate(Double.parseDouble(coordinates[0]),Double.parseDouble(coordinates[1]));
            selectedPoint(n,false);
        }).setNegativeButton("Close",null).show();
    }
    void about(){new AlertDialog.Builder(this).setTitle(getString(R.string.about_title,BuildConfig.VERSION_NAME)).setMessage(R.string.about_message).setNeutralButton(R.string.about_licences,(d,w)->licences()).setPositiveButton(R.string.action_close_dialog,null).show();}
    private void licences(){
        try{
            String[] files=getAssets().list("licenses");if(files==null)return;
            new AlertDialog.Builder(this).setTitle(R.string.about_licences_title).setItems(files,(d,index)->{
                try(InputStream in=getAssets().open("licenses/"+files[index])){
                    ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)bytes.write(b,0,n);
                    ScrollView scroll=new ScrollView(this);TextView body=text(bytes.toString("UTF-8"),13,TEXT);body.setPadding(dp(18),dp(12),dp(18),dp(12));scroll.addView(body);
                    new AlertDialog.Builder(this).setTitle(files[index]).setView(scroll).setPositiveButton(R.string.action_close_dialog,null).show();
                }catch(IOException e){error(e.getMessage());}
            }).setPositiveButton(R.string.action_close_dialog,null).show();
        }catch(IOException e){error(e.getMessage());}
    }
    /**
     * Location is usable when the GPS provider is on, or when the system location switch is on and a
     * provider is present. A test provider replaces the real GPS provider on some devices, and asking
     * only about GPS_PROVIDER would then report location as disabled while fixes are arriving.
     */
    private static boolean locationAvailable(android.location.LocationManager manager){
        try{
            if(manager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER))return true;
            if(android.os.Build.VERSION.SDK_INT>=28)return manager.isLocationEnabled();
        }catch(RuntimeException ignored){}
        return false;
    }
    void error(int message){error(getString(message));}
    void error(String message){
        if(isFinishing()||isDestroyed())return;
        AlertDialog.Builder builder=new AlertDialog.Builder(this).setTitle("EuroRig").setMessage(message)
                .setPositiveButton(R.string.action_ok,null);
        // An access refusal at the destination is the one case where the driver can name the entrance
        // they mean. The declaration is theirs; it is not evidence about the property.
        boolean accessRefusal=message!=null&&(message.contains("Access restricted")
                ||message.contains("Private road")||message.contains("access point"));
        Graph.Node destination=Store.graph!=null?Store.graph.nodes[Store.end]:null;
        if(accessRefusal&&Store.display!=null&&Store.destinationChosen&&destination!=null){
            long approach=Store.display.wayAt(destination.lat,destination.lon,40);
            if(approach>0)builder.setNeutralButton(R.string.action_declare_entrance,
                    (dialog,which)->declareEntrance(approach));
        }
        builder.show();
    }
    private String errorDetail(Throwable failure){String detail=failure.getMessage();return detail==null||detail.trim().isEmpty()?getString(R.string.error_no_further_details):detail;}
    /** Records the driver's declaration for the destination's own approach and recalculates. */
    private void declareEntrance(long approachWay){
        Graph.Node destination=Store.graph.nodes[Store.end];
        Store.destinationAuthorisation=new org.eurorig.routing.DestinationAuthorisation(
                destination.lat,destination.lon,approachWay,
                Store.deliveryAccess?org.eurorig.routing.DestinationAuthorisation.DELIVERY
                        :org.eurorig.routing.DestinationAuthorisation.PRIVATE,
                System.currentTimeMillis());
        android.util.Log.i("Declare",String.format(java.util.Locale.ROOT,
                "declared entrance way %d for destination %.5f,%.5f kind %s",approachWay,
                destination.lat,destination.lon,Store.deliveryAccess?"delivery":"private"));
        Store.route=null;
        if(map!=null)map.clearRoute();
        calculate();
    }
}
