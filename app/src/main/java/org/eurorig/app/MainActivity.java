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
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class MainActivity extends Activity {
    private static final int BG=0xff10191c, PANEL=0xff1e2c30, TEXT=0xffe4eeee, MUTED=0xff9eb4b9, LIME=0xffc4f16d;
    private TextView status, profile, endpoints, instruction, stats;
    private Button plan, drive;
    private RoadMapView map;
    private final Handler main=new Handler(Looper.getMainLooper());
    private boolean busy, simulating, resumed;
    private boolean previouslyActive;
    private Graph displayedGraph;
    private int requestGeneration, simulationEdge;
    private double simulationFraction;
    private Progress simulatedProgress;
    private Router.Route exporting;
    private LocationListener pendingGps;
    private final Runnable ticker=new Runnable(){public void run(){
        if(!resumed)return;
        boolean active=simulating||Store.navigating||MapDownloadService.running;
        if(simulating) simulationTick();
        if(active||previouslyActive)refresh();
        previouslyActive=simulating||Store.navigating||MapDownloadService.running;
        main.postDelayed(this,1500);
    }};
    public void onCreate(Bundle state){
        super.onCreate(state);buildUi();
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
        super.onConfigurationChanged(configuration);buildUi();if(Store.graph!=null)map.setGraph(Store.graph);refresh();
    }
    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private GradientDrawable background(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextColor(color);t.setTextSize(size);t.setFontFeatureSettings("kern");return t;}
    private LinearLayout vertical(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private Button button(String value,Runnable action){Button b=new Button(this);b.setText(value);b.setTextSize(13);b.setAllCaps(false);b.setTextColor(TEXT);b.setBackground(background(PANEL,12));b.setPadding(dp(10),0,dp(10),0);b.setMinHeight(dp(48));b.setOnClickListener(v->{if(busy)return;action.run();});return b;}
    private void buildUi(){
        boolean wide=getResources().getConfiguration().screenWidthDp>=650 && getResources().getConfiguration().screenWidthDp>getResources().getConfiguration().screenHeightDp;
        LinearLayout root=vertical();root.setBackgroundColor(BG);root.setPadding(dp(16),dp(10),dp(16),dp(8));
        LinearLayout content=root;
        if(wide){
            LinearLayout outer=new LinearLayout(this);outer.setBackgroundColor(BG);
            outer.setOnApplyWindowInsetsListener((v,insets)->{outer.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
            outer.addView(root,new LinearLayout.LayoutParams(dp(320),-1));
            content=vertical();content.setPadding(0,dp(10),dp(16),dp(8));outer.addView(content,new LinearLayout.LayoutParams(0,-1,1));setContentView(outer);outer.requestApplyInsets();
        }else{
            root.setOnApplyWindowInsetsListener((v,insets)->{root.setPadding(dp(16)+insets.getSystemWindowInsetLeft(),dp(10)+insets.getSystemWindowInsetTop(),dp(16)+insets.getSystemWindowInsetRight(),dp(8)+insets.getSystemWindowInsetBottom());return insets;});
            setContentView(root);root.requestApplyInsets();
        }
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand=text("EuroRig",28,TEXT);brand.setTypeface(Typeface.DEFAULT,Typeface.BOLD);header.addView(brand,new LinearLayout.LayoutParams(0,dp(44),1));
        TextView local=text("●  FULLY LOCAL",10,LIME);local.setPadding(dp(12),dp(9),dp(12),dp(9));local.setBackground(background(PANEL,20));header.addView(local);root.addView(header);
        status=text("Loading local map…",12,MUTED);status.setPadding(0,dp(4),0,dp(10));root.addView(status);
        LinearLayout routeCard=vertical();routeCard.setPadding(dp(14),dp(10),dp(14),dp(10));routeCard.setBackground(background(PANEL,16));
        endpoints=text("Choose route endpoints",14,TEXT);endpoints.setPadding(0,0,0,dp(6));routeCard.addView(endpoints);
        Button search=button("Search destination or coordinates",()->{if(Store.graph==null)mapsDialog();else search(false);});search.setBackground(background(0xff2c3e43,10));routeCard.addView(search,new LinearLayout.LayoutParams(-1,dp(46)));
        profile=text("40 t · 4.00 m · Articulated truck",12,LIME);profile.setPadding(0,dp(8),0,0);profile.setOnClickListener(v->{if(canChangeMap())truckDialog();});routeCard.addView(profile);root.addView(routeCard);
        map=new RoadMapView(this);map.setBackground(background(0xff162225,16));map.setClipToOutline(true);map.pick=n->{if(!editable())return;choosePoint(n);};
        LinearLayout.LayoutParams mapParams=new LinearLayout.LayoutParams(-1,0,1);mapParams.topMargin=wide?0:dp(12);mapParams.bottomMargin=dp(12);content.addView(map,mapParams);
        instruction=text("Your road. Your rig. Your navigation.",18,TEXT);instruction.setTypeface(Typeface.DEFAULT,Typeface.BOLD);content.addView(instruction);
        stats=text("Set your truck profile, then plan a route.",12,MUTED);stats.setPadding(0,dp(5),0,dp(10));content.addView(stats);
        LinearLayout actions=new LinearLayout(this);plan=button("Plan route",this::calculate);drive=button("Start guidance",this::startGuidance);
        drive.setBackground(background(LIME,12));drive.setTextColor(BG);
        LinearLayout.LayoutParams a=new LinearLayout.LayoutParams(0,dp(50),1);a.rightMargin=dp(8);actions.addView(plan,a);actions.addView(drive,new LinearLayout.LayoutParams(0,dp(50),1));content.addView(actions);
        if(wide)root.addView(new View(this),new LinearLayout.LayoutParams(1,0,1));
        LinearLayout dock=new LinearLayout(this);dock.setPadding(0,dp(10),0,0);
        String[] labels={"Truck","Maps","Route","More"};Runnable[] callbacks={()->{if(canChangeMap())truckDialog();},this::mapsDialog,this::routeDialog,this::moreDialog};
        for(int i=0;i<labels.length;i++){Button b=button(labels[i],callbacks[i]);b.setTextColor(MUTED);b.setBackgroundColor(Color.TRANSPARENT);dock.addView(b,new LinearLayout.LayoutParams(0,dp(48),1));}root.addView(dock);
    }
    private void refresh(){
        Graph g=Store.graph;boolean active=Store.navigating||simulating;
        if(displayedGraph!=g){displayedGraph=g;map.setGraph(g);}
        plan.setEnabled(!busy&&!Store.installing&&g!=null&&!active);drive.setEnabled(!busy&&!Store.installing&&Store.route!=null);
        plan.setText(busy?"Working…":"Plan route");drive.setText(active?"Stop":g!=null&&g.demo?"Simulate route":"Start guidance");
        profile.setText(String.format(Locale.getDefault(),"%.1f t  ·  %.2f m high  ·  %.2f m wide  ·  %.1f m long",Store.truck.weight,Store.truck.height,Store.truck.width,Store.truck.length));
        if(g==null){status.setText(busy?"Opening maps…":MapDownloadService.running?MapDownloadService.status:"No maps installed");endpoints.setText(R.string.download_country_start);instruction.setText(R.string.first_country);stats.setText(R.string.choose_first_map);map.updateGraph(null);map.invalidate();return;}
        status.setText(g.demo?"DEVELOPMENT BUILD · Fictional training map":Store.navigating?"GPS GUIDANCE · Offline · Development build":"OFFLINE · "+g.name+(Store.nativeRouter!=null?" · Valhalla truck":" · Prototype router"));
        endpoints.setText(getString(R.string.route_endpoints,label(Store.start),label(Store.end)));
        Router.Route r=Store.route;
        if(active){instruction.setText(simulating?getString(R.string.simulated_instruction,Store.guidance):Store.guidance);stats.setText(String.format(Locale.getDefault(),"%.1f km remaining · %.0f km/h%s",Store.remaining/1000,Store.speed,simulating?" · Fictional roads":""));}
        else if(r!=null){instruction.setText(Store.arrived?(g.demo?"Training route complete":"You have arrived"):r.edges.isEmpty()?"Start and destination are the same":"Route ready for your truck");stats.setText(String.format(Locale.getDefault(),"%.1f km  ·  approximately %d min  ·  %s",r.metres/1000,Math.max(1,Math.round(r.seconds/60)),r.nativeGeometry()?"Valhalla truck costing":"Tagged restrictions checked"));}
        else{instruction.setText(R.string.tagline);stats.setText(R.string.choose_destination);}
        map.updateGraph(Store.graph);map.invalidate();
        if(active)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private String label(int node){if(Store.graph==null)return "";Graph.Node n=Store.graph.nodes[node];return n.label.isEmpty()?String.format(Locale.ROOT,"%.5f, %.5f",n.lat,n.lon):n.label;}
    private boolean canChangeMap(){if(busy||Store.installing)return false;if(Store.navigating||simulating){error("Stop guidance before changing the route, map or truck.");return false;}return true;}
    private boolean editable(){if(busy||Store.installing)return false;if(Store.navigating||simulating){error("Stop guidance before changing the route, map or truck.");return false;}return Store.graph!=null;}
    private void choosePoint(int node){new AlertDialog.Builder(this).setTitle(label(node)).setItems(new String[]{"Set destination","Set starting point","Save favourite"},(d,i)->{if(i==2){saveFavourite(node);return;}if(i==0)Store.end=node;else Store.start=node;Store.route=null;refresh();}).show();}
    private void search(boolean starting){
        if(!editable())return;
        LinearLayout box=vertical();box.setPadding(dp(20),dp(8),dp(20),0);
        EditText query=new EditText(this);query.setSingleLine();query.setHint("Name or latitude, longitude");box.addView(query);
        ListView list=new ListView(this);box.addView(list,new LinearLayout.LayoutParams(-1,dp(260)));
        ArrayList<Integer> ids=new ArrayList<>();ArrayList<Graph.Node> results=new ArrayList<>();int[] searchGeneration={0};double[] coordinate={Double.NaN,Double.NaN};ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,new ArrayList<>());list.setAdapter(adapter);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(starting?"Choose starting point":"Search offline map").setView(box).setNegativeButton("Cancel",null).create();
        query.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){
                int generation=++searchGeneration[0];ids.clear();results.clear();adapter.clear();coordinate[0]=Double.NaN;String raw=s.toString();
                if(raw.contains(",")){try{String[] parts=raw.split(",");if(parts.length==2){double lat=Double.parseDouble(parts[0].trim()),lon=Double.parseDouble(parts[1].trim());if(Double.isFinite(lat)&&Double.isFinite(lon)&&Math.abs(lat)<=85&&Math.abs(lon)<=180){if(Store.nativeRouter!=null){coordinate[0]=lat;coordinate[1]=lon;ids.add(-1);}else{int n=Store.graph.nearest(lat,lon,250,Store.truck);if(n>=0)ids.add(n);}}}}catch(NumberFormatException ignored){}}
                else if(Store.display!=null){
                    DisplayDatabase display=Store.display;
                    Store.mapWorker.execute(()->{try{List<Graph.Node> found=display.search(raw);main.post(()->{if(generation!=searchGeneration[0]||!dialog.isShowing())return;results.clear();results.addAll(found);adapter.clear();for(Graph.Node point:found)adapter.add(point.label);adapter.notifyDataSetChanged();});}catch(RuntimeException ignored){}});
                }else ids.addAll(Store.graph.search(raw));
                for(int n:ids)adapter.add(n<0?String.format(Locale.ROOT,"%.5f, %.5f",coordinate[0],coordinate[1]):label(n));adapter.notifyDataSetChanged();
            }
            public void afterTextChanged(Editable e){}
        });
        list.setOnItemClickListener((p,v,position,id)->{int node;if(!results.isEmpty()){Graph.Node point=results.get(position);node=Store.coordinate(point.lat,point.lon,point.label);}else{node=ids.get(position);if(node<0)node=Store.coordinate(coordinate[0],coordinate[1]);}if(node<0)return;if(starting)Store.start=node;else Store.end=node;Store.route=null;
            getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(query.getWindowToken(),0);
            dialog.dismiss();refresh();});dialog.show();
    }
    private void truckDialog(){
        ScrollView scroll=new ScrollView(this);LinearLayout box=vertical();box.setPadding(dp(20),dp(8),dp(20),dp(8));scroll.addView(box);
        box.setFocusableInTouchMode(true);box.requestFocus();
        Truck t=Store.truck;String[] names={"Height (m)","Width (m)","Length (m)","Loaded gross weight (t)","Maximum loaded axle weight (t)"};double[] values={t.height,t.width,t.length,t.weight,t.axleWeight};EditText[] fields=new EditText[5];
        for(int i=0;i<5;i++){box.addView(text(names[i],13,MUTED));fields[i]=new EditText(this);fields[i].setSingleLine();fields[i].setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);fields[i].setText(String.format(Locale.ROOT,"%.2f",values[i]));box.addView(fields[i]);}
        String[] options={"Hazardous goods (mapped hazmat restrictions)","Avoid toll roads","Avoid ferries","Avoid unpaved roads"};boolean[] checks={t.hazmat,t.avoidTolls,t.avoidFerries,t.avoidUnpaved};CheckBox[] boxes=new CheckBox[4];
        for(int i=0;i<4;i++){boxes[i]=new CheckBox(this);boxes[i].setText(options[i]);boxes[i].setChecked(checks[i]);box.addView(boxes[i]);}
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Your truck").setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();dialog.show();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{
            double[] n=new double[5];for(int i=0;i<5;i++)n[i]=Double.parseDouble(fields[i].getText().toString().replace(',','.'));
            Store.saveTruck(this,new Truck(n[0],n[1],n[2],n[3],n[4],boxes[0].isChecked(),boxes[1].isChecked(),boxes[2].isChecked(),boxes[3].isChecked()));dialog.dismiss();refresh();
        }catch(IllegalArgumentException e){error(e instanceof NumberFormatException?"Enter all measurements as numbers.":e.getMessage());}});
    }
    private void calculate(){
        if(!editable())return;
        final Graph graph=Store.graph;final int start=Store.start,end=Store.end;final Truck truck=Store.truck;final int generation=++requestGeneration;
        busy=true;refresh();Store.worker.execute(()->{
            try{Router.Route route=Store.calculate(graph,start,end,truck);main.post(()->{if(generation!=requestGeneration||isDestroyed())return;Store.route=route;Store.arrived=false;busy=false;if(route.nativeGeometry())map.fitRoute(route);refresh();});}
            catch(RuntimeException|LinkageError e){main.post(()->{if(generation!=requestGeneration||isDestroyed())return;Store.route=null;busy=false;refresh();error(e.getMessage());});}
        });
    }
    private void startGuidance(){
        if(Store.navigating||simulating){stopGuidance();return;}
        if(Store.installing)return;
        if(Store.route==null||Store.route.edges.isEmpty()){error("Plan a route with different endpoints first.");return;}
        if(Store.graph.demo){simulating=true;Store.arrived=false;simulationEdge=0;simulationFraction=0;simulatedProgress=new Progress(Store.route);Store.speed=60;Store.remaining=Store.route.metres;Store.guidance="Training route. These roads are fictional.";refresh();return;}
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(Build.VERSION.SDK_INT>=33?new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.POST_NOTIFICATIONS}:new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},100);return;
        }
        LocationManager manager=getSystemService(LocationManager.class);
        if(!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)){error("Enable device location, then start guidance.");return;}
        // The planned origin must match the driver's current location before guidance.
        Location fix=manager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        if(fix==null||!fix.hasAccuracy()||fix.getAccuracy()>50||SystemClock.elapsedRealtimeNanos()-fix.getElapsedRealtimeNanos()>30_000_000_000L){
            error("A fresh GPS fix is needed. Choose More → Start at GPS, then plan the route.");return;
        }
        Graph.Node origin=Store.graph.nodes[Store.start];
        if(Geo.distance(fix.getLatitude(),fix.getLongitude(),origin.lat,origin.lon)>250){error("Your route starts too far from your location. Choose More → Start at GPS, then replan.");return;}
        new AlertDialog.Builder(this).setTitle("Development navigation").setMessage("This prototype checks a limited set of mapped restrictions. Missing map data can hide restrictions. It has not passed road validation. Use road signs and an approved route plan. Voice requires an installed offline voice.")
            .setNegativeButton("Cancel",null).setPositiveButton("Start",(d,w)->{Store.guidance="Waiting for GPS";startForegroundService(new Intent(this,NavigationService.class));}).show();
    }
    public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(request==100){if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)startGuidance();else error("Precise location is needed for GPS guidance. Route planning still works offline.");}else if(request==101&&checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)startAtGps();}
    private void stopGuidance(){simulating=false;stopService(new Intent(this,NavigationService.class));Store.navigating=false;Store.lat=Double.NaN;Store.lon=Double.NaN;refresh();}
    private void simulationTick(){
        Router.Route route=Store.route;if(route==null||simulationEdge>=route.edges.size()){simulating=false;return;}
        Graph.Edge edge=route.edges.get(simulationEdge);simulationFraction+=120/Math.max(1,edge.metres);
        if(simulationFraction>=1){simulationFraction=0;simulationEdge++;if(simulationEdge>=route.edges.size()){simulating=false;Store.arrived=true;Store.guidance="Simulation complete";return;}edge=route.edges.get(simulationEdge);}
        Graph.Node a=route.graph.nodes[edge.from],b=route.graph.nodes[edge.to];Store.lat=a.lat+(b.lat-a.lat)*simulationFraction;Store.lon=a.lon+(b.lon-a.lon)*simulationFraction;
        Progress.Fix fix=simulatedProgress.update(Store.lat,Store.lon);Store.remaining=fix.remaining;Store.guidance=Math.round(fix.toManeuver)+" m · "+route.instruction(fix.maneuver);
    }
    private void mapsDialog(){
        new AlertDialog.Builder(this).setTitle("Offline maps")
            .setItems(new String[]{"Download Romania","Choose another country","Download all Europe","Pause downloads","Downloaded countries","Import country (.eurorig)","Map download source","Installed map details"},(d,w)->{
                if(w==0)download("romania");
                else if(w==1)countryCatalogue();
                else if(w==2)download(null);
                else if(w==3){if(MapDownloadService.running)startService(new Intent(this,MapDownloadService.class).setAction("PAUSE"));}
                else if(w==4)downloadedCountries();
                else if(w==5){if(canChangeMap())startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),202);}
                else if(w==6)mapSource();
                else{Graph g=Store.graph;if(g==null){error("No maps installed yet.");return;}
                    new AlertDialog.Builder(this).setTitle(g.name).setMessage(g.date+"\n"+g.attribution+"\n\nOffline truck routes, roads and place search. Only the selected country's coverage is active in this development build. Seamless cross-border routing is still being developed.\n\nCheck road signs. Truck-law validation and time-dependent restrictions are unfinished.").setPositiveButton("Close",null).show();}
            }).setNeutralButton("Download status",(d,w)->error(MapDownloadService.status)).setNegativeButton("Close",null).show();
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
    private void installGraph(Graph g){Store.graph=g;Store.route=null;Store.start=0;Store.end=Math.min(3,g.nodes.length-1);if(Store.nativeRouter!=null)Store.setRegionEndpoints();Store.lat=Double.NaN;Store.lon=Double.NaN;busy=false;if(!isDestroyed()){map.setGraph(g);refresh();}}
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
    private void moreDialog(){new AlertDialog.Builder(this).setTitle("EuroRig").setItems(new String[]{"Change starting point","Start at GPS","Fit map","Favourites",Store.voiceEnabled?"Mute voice":"Enable voice","About and limitations"},(d,i)->{
        if(i==0)search(true);else if(i==1)startAtGps();else if(i==2)map.fit();else if(i==3)favourites();else if(i==4){
            Store.voiceEnabled=!Store.voiceEnabled;getSharedPreferences("settings",0).edit().putBoolean("voice",Store.voiceEnabled).apply();
            if(!Store.voiceEnabled&&Store.navigating)startService(new Intent(this,NavigationService.class).setAction("MUTE"));
            Toast.makeText(this,Store.voiceEnabled?"Offline voice enabled for the next trip":"Visual guidance only",Toast.LENGTH_SHORT).show();
        }else about();
    }).show();}
    private void startAtGps(){
        if(!editable())return;
        if(Store.graph.demo){error("GPS cannot be used with the fictional training map. Import real local coverage first.");return;}
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},101);return;}
        LocationManager locations=getSystemService(LocationManager.class);status.setText(R.string.acquiring_gps);
        cancelGps();busy=true;
        LocationListener listener=new LocationListener(){
            public void onLocationChanged(Location l){locations.removeUpdates(this);pendingGps=null;busy=false;
                if(isDestroyed())return;
                if(l.getAccuracy()>50){error("GPS accuracy is too low. Try again outside.");refresh();return;}
                int n=Store.coordinate(l.getLatitude(),l.getLongitude());
                if(n<0){error("Your location is outside routable map coverage.");refresh();return;}
                Store.start=n;Store.route=null;Store.lat=l.getLatitude();Store.lon=l.getLongitude();map.locate(Store.lat,Store.lon);refresh();
            }
            public void onProviderEnabled(String p){}public void onProviderDisabled(String p){}public void onStatusChanged(String p,int s,Bundle b){}
        };
        pendingGps=listener;
        try{locations.requestSingleUpdate(LocationManager.GPS_PROVIDER,listener,Looper.getMainLooper());main.postDelayed(()->{if(pendingGps==listener){cancelGps();if(!isDestroyed()){refresh();error("No GPS fix yet. Try again outside.");}}},20000);}
        catch(SecurityException|IllegalArgumentException e){cancelGps();error("GPS unavailable");refresh();}
    }
    private void cancelGps(){if(pendingGps!=null){getSystemService(LocationManager.class).removeUpdates(pendingGps);pendingGps=null;busy=false;}}
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
            if(n<0)error("Favourite is outside the current routable map.");else{Store.end=n;Store.route=null;refresh();}
        }).setNegativeButton("Close",null).show();
    }
    private void about(){new AlertDialog.Builder(this).setTitle("EuroRig 0.3.0-dev").setMessage("Made by drivers, for drivers. Free, open source, local navigation.\n\nMIT code. OSM maps: © OpenStreetMap contributors, ODbL 1.0. No accounts, subscriptions or analytics. Internet is used to download maps; routes, roads, search and GPS run on the device.\n\nNo maps are bundled. Romania is the first test country. Europe excluding Russia is the coverage target; publication of all country packages and seamless cross-border routing are pending.\n\nThis development build has not passed road validation. ADR tunnel categories, country truck laws and time-dependent restrictions need further work. Offline voice needs an installed voice.\n\nAndroid 8+ minimum; physical-device and road tests are still needed.").setNeutralButton("Licences",(d,w)->licences()).setPositiveButton("Close",null).show();}
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
