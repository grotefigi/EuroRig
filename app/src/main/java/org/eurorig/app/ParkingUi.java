package org.eurorig.app;

import android.app.AlertDialog;
import org.eurorig.routing.parking.Parking;
import java.text.DateFormat;
import java.util.*;

/**
 * Parking details use the app's existing native dialogs. The panel states the provider's own
 * information status, so a test feed or an unverifiable count can never read as live availability.
 */
final class ParkingUi {
    /**
     * The list and the provider status must both be visible, so this dialog owns its layout instead
     * of stacking a message and a list in the same panel - which leaves the list with no height.
     */
    static void list(MainActivity host){
        ParkingRepository repository=Store.parking;
        if(repository==null){host.error("Parking data is not available in this build.");return;}
        repository.refreshOnce();
        boolean dark=host.getSharedPreferences("settings",0).getBoolean("dark_mode",false);
        int textColour=dark?0xfff6efe5:0xff2c2118,mutedColour=dark?0xffc0af9b:0xff6b5948;
        android.widget.LinearLayout content=new android.widget.LinearLayout(host);
        content.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad=Math.round(16*host.getResources().getDisplayMetrics().density);
        android.widget.TextView status=new android.widget.TextView(host);
        status.setTextSize(13);status.setTextColor(mutedColour);status.setPadding(pad,pad,pad,pad);
        android.widget.ListView list=new android.widget.ListView(host);
        final ArrayList<String> rows=new ArrayList<>();
        final ArrayList<Parking.Site> shown=new ArrayList<>();
        android.widget.ArrayAdapter<String> adapter=new android.widget.ArrayAdapter<>(host,android.R.layout.simple_list_item_1,rows){
            public android.view.View getView(int position,android.view.View convert,android.view.ViewGroup parent){
                android.view.View view=super.getView(position,convert,parent);
                android.widget.TextView row=view.findViewById(android.R.id.text1);
                row.setTextColor(textColour);row.setTextSize(16);row.setMinHeight(pad*4);return view;
            }
        };
        list.setAdapter(adapter);
        content.addView(status,new android.widget.LinearLayout.LayoutParams(-1,-2));
        content.addView(list,new android.widget.LinearLayout.LayoutParams(-1,0,1));
        AlertDialog dialog=new AlertDialog.Builder(host)
            .setTitle("Truck parking · NDW")
            .setView(content)
            .setPositiveButton("Close",null)
            .setNeutralButton("Refresh",(d,w)->repository.refreshOnce()).create();
        Runnable fill=()->{
            shown.clear();shown.addAll(repository.sites());
            rows.clear();
            for(Parking.Site site:shown)rows.add(site.name+"\n"+line(repository,site));
            adapter.notifyDataSetChanged();
            status.setText(banner(repository));
        };
        fill.run();
        list.setOnItemClickListener((parent,view,position,id)->{if(position<shown.size())details(host,shown.get(position));});
        ParkingRepository.Listener live=()->{if(dialog.isShowing())fill.run();};
        dialog.setOnShowListener(shownDialog->repository.observe(live));
        dialog.setOnDismissListener(dismissed->repository.unobserve(live));
        dialog.show();
    }
    static void details(MainActivity host,Parking.Site site){
        ParkingRepository repository=Store.parking;
        if(repository==null)return;
        Parking.Availability availability=repository.availability(site);
        StringBuilder message=new StringBuilder();
        message.append(line(repository,site)).append("\n\n");
        if(availability.exact())message.append("Reported ").append(availability.spaces).append(" truck spaces available, ")
            .append(Parking.age(System.currentTimeMillis(),availability.measured)).append(".\nSpaces are not guaranteed on arrival.\n\n");
        else message.append("Availability is not shown: ").append(availability.reason.toLowerCase(Locale.ROOT)).append(".\n\n");
        message.append("Declared vehicle entrances: ").append(site.entrances.size()).append("\n");
        if(!site.entrances.isEmpty()){
            Parking.Entrance entrance=site.entrances.get(0);
            message.append("Entrance ").append(entrance.id);
            if(!entrance.road.isEmpty())message.append(" · ").append(entrance.road);
            if(!entrance.destination.isEmpty())message.append(" · ").append(entrance.destination);
            message.append(String.format(Locale.ROOT,"\n%.6f, %.6f\n",entrance.lat,entrance.lon));
        }
        if(!site.reservation.isEmpty())message.append("\nReservation: ").append(site.reservation);
        message.append("\n\n").append(banner(repository));
        new AlertDialog.Builder(host).setTitle(site.name).setMessage(message.toString())
            .setPositiveButton("Close",null).show();
    }
    private static String line(ParkingRepository repository,Parking.Site site){
        Parking.Availability availability=repository.availability(site);
        if(availability.exact())return availability.spaces+" truck spaces reported · "+Parking.age(System.currentTimeMillis(),availability.measured);
        return availability.reason;
    }
    /** The provider's own status and our last contact, both stated rather than implied. */
    private static String banner(ParkingRepository repository){
        Parking.Table table=repository.table();
        StringBuilder text=new StringBuilder();
        if(table!=null&&!table.operational())
            text.append("NDW marks this location table informationStatus=").append(table.informationStatus)
                .append(", so these counts are not verified live availability.\n\n");
        else if(table==null)text.append("The NDW table is not loaded.\n\n");
        text.append(repository.connected()?"Connected · ":"Offline · ")
            .append(repository.checkedAt()==0?"not checked yet":("last checked "+DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(repository.checkedAt()))))
            .append("\n").append(repository.attribution()).append(" · public open data");
        return text.toString();
    }
    private ParkingUi(){}
}
