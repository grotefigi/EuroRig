package org.eurorig.app;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.SystemClock;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import org.eurorig.routing.*;
import org.json.*;
import java.io.*;

/** Preview-only checks. No map install, driver profile edits or real guidance is started. */
final class NativeCorrectnessChecks {
    static void run(Instrumentation t,boolean baseline)throws Exception{
        NativeInterfaceChecks.require(t.getTargetContext().getPackageName().endsWith(".preview"),"Correctness checks require isolated preview storage");
        payloads();
        interruptedTripPersistence(t);
        if(!baseline){ProfilePersistenceChecks.run(t.getContext());profileRecovery(t);}
        markers(t,baseline?"marker-before":"marker-after");
        if(!baseline)gestures(t);
    }
    private static void interruptedTripPersistence(Instrumentation t){
        ContextWrapper isolated=new ContextWrapper(t.getContext()){
            @Override public SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences("trip-resume-qa",mode);}
        };
        try{
            Store.clearInterruptedTrip(isolated);
            NativeInterfaceChecks.require(!Store.interruptedTripPending(isolated),"No stale interrupted-trip prompt after explicit clear");
            Store.markInterruptedTrip(isolated);
            NativeInterfaceChecks.require(Store.interruptedTripPending(isolated),"Active trip is durably marked for recovery");
            Store.saveInterruptedPassedStops(isolated,2);
            ContextWrapper nextProcess=new ContextWrapper(t.getContext()){
                @Override public SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences("trip-resume-qa",mode);}
            };
            NativeInterfaceChecks.require(Store.interruptedTripPending(nextProcess),"Recovery marker survives a fresh context");
            NativeInterfaceChecks.require(Store.interruptedPassedStops(nextProcess)==2,"Passed-stop checkpoint survives a fresh context");
            Store.clearInterruptedTrip(nextProcess);
            NativeInterfaceChecks.require(!Store.interruptedTripPending(isolated)&&Store.interruptedPassedStops(isolated)==0,"Explicit trip stop clears recovery marker and progress");
        }finally{Store.clearInterruptedTrip(isolated);}
    }
    private static void profileRecovery(Instrumentation t){
        NativeInterfaceChecks.require(!Store.navigating&&Store.route==null,"Profile recovery checks require idle isolated process");
        SharedPreferences p=t.getContext().getSharedPreferences("profile-corruption-qa",0);
        Truck previous=Store.truck;boolean previousReview=Store.truckNeedsReview;String previousRefused=Store.guidanceRefused;Runnable previousStopper=Store.guidanceStopper;
        try{
            validProfile(p);Store.loadTruck(p);
            NativeInterfaceChecks.require(!Store.truckNeedsReview&&Store.truck.weight==44.125&&Store.truck.topSpeed==70.125,"Valid decimal profile retains exact values");
            for(double mass:new double[]{Math.nextUp(100d),200d}){
                validProfile(p);p.edit().putString("weight",Double.toString(mass)).putString("permitted","200").commit();Store.loadTruck(p);
                String issue=NativeRouter.profileSupportIssue(Store.truck);
                NativeInterfaceChecks.require(!Store.truckNeedsReview&&Store.truck.weight==mass&&Store.truck.permittedWeight==200&&!issue.isEmpty(),"Unsupported mass remains exact and is not treated as corrupt");
                NativeInterfaceChecks.require(VehicleProfiles.truck(VehicleProfiles.fromTruck(Store.truck,"Heavy saved truck")).weight==mass,"Unsupported named profile survives reopening");
                for(int choice=0;choice<2;choice++){
                    boolean rejected=false;try{if(choice==0)Store.calculate(null,0,0,Store.truck);else Store.calculateRoutes(null,0,0,Store.truck);}
                    catch(IllegalStateException expected){rejected=issue.equals(expected.getMessage());}
                    NativeInterfaceChecks.require(rejected,"Unsupported profile refused before graph or engine access");
                }
                NativeInterfaceChecks.require(!Store.beginGuidance()&&issue.equals(Store.guidanceRefused),"Unsupported profile cannot begin guidance");
                int[] unsupportedStop={0};Store.navigating=true;Store.guidanceStopper=()->unsupportedStop[0]++;Store.loadTruck(p);
                NativeInterfaceChecks.require(!Store.navigating&&unsupportedStop[0]==1&&Store.truck.weight==mass,"Loading unsupported profile stops guidance without replacing its values");
            }
            validProfile(p);p.edit().putString("weight","100").putString("permitted","200").commit();Store.loadTruck(p);
            NativeInterfaceChecks.require(!Store.truckNeedsReview&&Store.profileIssue(Store.truck).isEmpty()&&Store.truck.weight==100&&Store.truck.permittedWeight==200,"Editing back to 100 t restores native eligibility and preserves permitted mass");
            for(String key:new String[]{"height","top_speed","axles","hazmat"}){
                validProfile(p);p.edit().putString(key,"invalid").commit();Store.loadTruck(p);
                NativeInterfaceChecks.require(Store.truckNeedsReview,"Malformed "+key+" requires explicit profile review");
                refused(()->Store.calculate(null,0,0,previous));refused(()->Store.calculateRoutes(null,0,0,previous));
                NativeInterfaceChecks.require(!Store.beginGuidance()&&Store.guidanceRefused.equals(Store.TRUCK_REVIEW),"Malformed profile cannot begin guidance");
            }
            validProfile(p);p.edit().putString("weight","NaN").commit();int[] stopped={0};Store.navigating=true;Store.guidanceStopper=()->stopped[0]++;Store.loadTruck(p);
            NativeInterfaceChecks.require(Store.truckNeedsReview&&!Store.navigating&&stopped[0]==1,"Invalid profile immediately stops active guidance");
            // Save into the companion test APK, never the driver's truck preferences.
            Store.saveTruck(t.getContext(),previous);
            NativeInterfaceChecks.require(!Store.truckNeedsReview&&Store.truck==previous,"Saving a reviewed valid profile restores eligibility");
            validProfile(p);p.edit().putFloat("height",3.9f).commit();Store.loadTruck(p);
            NativeInterfaceChecks.require(!Store.truckNeedsReview&&Store.truck.height==3.9,"Legacy float profile keeps its entered decimal value");
        }finally{p.edit().clear().commit();Store.truck=previous;Store.truckNeedsReview=previousReview;Store.guidanceRefused=previousRefused;Store.guidanceStopper=previousStopper;Store.navigating=false;}
    }
    private static void validProfile(SharedPreferences p){p.edit().clear().putString("height","4.25").putString("width","2.6").putString("length","18").putString("weight","44.125").putString("permitted","46").putString("axle","11.5").putString("top_speed","70.125").putInt("axles",6).putBoolean("hazmat",true).commit();}
    private static void refused(Runnable action){boolean rejected=false;try{action.run();}catch(IllegalStateException expected){rejected=Store.TRUCK_REVIEW.equals(expected.getMessage());}NativeInterfaceChecks.require(rejected,"Corrupted profile blocks planning before engine access");}
    private static void payloads()throws Exception{
        NativeInterfaceChecks.require(NativeRouter.instructionLanguage(1).equals("en-US")&&NativeRouter.instructionLanguage(2).equals("ro-RO"),"Voice-language choice selects matching maneuver-language tags");
        NativeInterfaceChecks.require(NativeRouter.request(45,27,45.1,27.1,Truck.standard(),RoutingMode.ECONOMICAL,false,"ro-RO").getString("language").equals("ro-RO"),"Romanian maneuver language reaches the native request");
        for(RoutingMode mode:RoutingMode.values())for(boolean delivery:new boolean[]{false,true}){
            Truck precise=new Truck(3.89,2.5,17,41,10,true,true,false,false,6,77,2,3,40);
            JSONObject truck=NativeRouter.request(45,27,45.1,27.1,precise,mode,delivery).getJSONObject("costing_options").getJSONObject("truck");
            for(Object[] field:new Object[][]{{"height",3.89},{"width",2.5},{"length",17.0},{"weight",41.0},{"axle_load",10.0},{"top_speed",77.0}})
                NativeInterfaceChecks.require(truck.getDouble((String)field[0])==(double)field[1],"Exact native payload: "+field[0]+" in "+mode);
            NativeInterfaceChecks.require(truck.getInt("axle_count")==6&&truck.getBoolean("hazmat"),"Axles and hazardous-load flag retained");
            NativeInterfaceChecks.require(truck.getBoolean("exclude_tolls")&&!truck.getBoolean("exclude_ferries")&&!truck.getBoolean("exclude_unpaved"),"First preference combination retained");
            NativeInterfaceChecks.require(!truck.getBoolean("ignore_restrictions")&&!truck.getBoolean("ignore_access")&&!truck.getBoolean("ignore_oneways"),"Hard-rule ignore switches remain disabled");
            Truck opposite=new Truck(4,2.55,16.5,100,11.5,false,false,true,true);
            JSONObject other=NativeRouter.request(45,27,45.1,27.1,opposite,mode,delivery).getJSONObject("costing_options").getJSONObject("truck");
            NativeInterfaceChecks.require(other.getDouble("weight")==100&&!other.getBoolean("hazmat")&&!other.getBoolean("exclude_tolls")&&other.getBoolean("exclude_ferries")&&other.getBoolean("exclude_unpaved"),"100 t boundary and opposite preferences retained");
            for(double mass:new double[]{Math.nextUp(100d),200d}){
                boolean rejected=false;
                try{NativeRouter.request(45,27,45.1,27.1,new Truck(4,2.55,16.5,mass,11.5,false,false,true,true),mode,delivery);}
                catch(IllegalArgumentException expected){rejected=expected.getMessage().contains("100");}
                NativeInterfaceChecks.require(rejected,"Native bridge refuses loaded mass above 100 t in "+mode);
            }
        }
    }
    private static void markers(Instrumentation t,String folder)throws Exception{
        File root=new File(t.getTargetContext().getFilesDir(),folder);NativeInterfaceChecks.require(root.isDirectory()||root.mkdirs(),"Marker receipt directory");
        NativeInterfaceChecks.ui(t,()->{
            for(boolean truck:new boolean[]{false,true})for(int heading:new int[]{0,37,181})for(int size:new int[]{129,257}){
                VehicleMarkerView view=new VehicleMarkerView(t.getTargetContext());view.style(truck,heading);view.layout(0,0,size,size);
                Bitmap image=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(image);view.draw(canvas);
                try(FileOutputStream out=new FileOutputStream(new File(root,(truck?"truck":"arrow")+"-"+heading+"-"+size+".png"))){NativeInterfaceChecks.require(image.compress(Bitmap.CompressFormat.PNG,100,out),"Marker bitmap encoded");}
                catch(IOException failure){throw new AssertionError(failure);}finally{image.recycle();}
            }
        });
    }
    private static void gestures(Instrumentation t)throws Exception{
        MainActivity activity=(MainActivity)t.startActivitySync(new Intent(t.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        int[] actions={0};MapControlMenu[] holder=new MapControlMenu[1];ViewGroup parent=(ViewGroup)activity.getWindow().getDecorView();
        try{
            NativeInterfaceChecks.ui(t,()->{MapControlMenu menu=new MapControlMenu(activity,0xff111111,0xff5599ff,()->actions[0]++,()->actions[0]++,()->actions[0]++);holder[0]=menu;parent.addView(menu,new ViewGroup.LayoutParams(120,ViewGroup.LayoutParams.WRAP_CONTENT));menu.setExpanded(true);});
            t.waitForIdleSync();MapControlMenu menu=holder[0];
            tap(t,menu,false,false);t.waitForIdleSync();NativeInterfaceChecks.require(actions[0]==1,"A native touch triggers exactly one action");
            tap(t,menu,true,false);t.waitForIdleSync();NativeInterfaceChecks.require(actions[0]==1,"Cancelled touch triggers no action");
            tap(t,menu,false,true);t.waitForIdleSync();NativeInterfaceChecks.require(actions[0]==1,"Dragging outside triggers no action");
            NativeInterfaceChecks.ui(t,()->NativeInterfaceChecks.require(menu.zoomIn.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,null),"Native accessibility click handled"));
            t.waitForIdleSync();NativeInterfaceChecks.require(actions[0]==2,"Accessibility activation triggers exactly one action");
            NativeInterfaceChecks.ui(t,()->{menu.zoomIn.setFocusableInTouchMode(true);menu.zoomIn.requestFocus();menu.zoomIn.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_CENTER));menu.zoomIn.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_CENTER));});
            t.waitForIdleSync();NativeInterfaceChecks.require(actions[0]==3,"Keyboard activation triggers exactly one action");
            NativeInterfaceChecks.ui(t,()->touch(menu,MotionEvent.ACTION_DOWN,20,20));SystemClock.sleep(5300);
            NativeInterfaceChecks.ui(t,()->NativeInterfaceChecks.require(menu.isExpanded(),"Held touch suspends idle collapse"));
            NativeInterfaceChecks.ui(t,()->touch(menu,MotionEvent.ACTION_CANCEL,20,20));
            NativeInterfaceChecks.await(t,()->!menu.isExpanded(),6500,"Cancelled touch rearms idle collapse");
        }finally{NativeInterfaceChecks.ui(t,()->{if(holder[0]!=null)parent.removeView(holder[0]);activity.finish();});}
    }
    private static void tap(Instrumentation t,MapControlMenu menu,boolean cancel,boolean drag){
        NativeInterfaceChecks.ui(t,()->{int[] position=new int[2],base=new int[2];menu.zoomIn.getLocationInWindow(position);menu.getLocationInWindow(base);float x=position[0]-base[0]+menu.zoomIn.getWidth()/2f,y=position[1]-base[1]+menu.zoomIn.getHeight()/2f;
            touch(menu,MotionEvent.ACTION_DOWN,x,y);if(drag)touch(menu,MotionEvent.ACTION_MOVE,-100,-100);touch(menu,cancel?MotionEvent.ACTION_CANCEL:MotionEvent.ACTION_UP,drag?-100:x,drag?-100:y);});
    }
    private static void touch(MapControlMenu menu,int action,float x,float y){MotionEvent event=MotionEvent.obtain(SystemClock.uptimeMillis(),SystemClock.uptimeMillis(),action,x,y,0);try{menu.dispatchTouchEvent(event);}finally{event.recycle();}}
}
