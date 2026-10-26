package org.eurorig.app;

import org.json.*;

/** Two paint palettes over the same offline geometry, filters and detail levels. */
final class MapAppearance {
    private static final String[][] NIGHT={
        {"bg","background-color","#000000"},
        {"grass","fill-color","#1C2A1D"},{"forest","fill-color","#13281B"},
        {"sand","fill-color","#30291E"},{"wetland","fill-color","#152A25"},
        {"residential","fill-color","#191715"},{"commercial","fill-color","#261C19"},
        {"industrial","fill-color","#252326"},{"parks","fill-color","#193020"},
        {"cemetery","fill-color","#233022"},{"field-boundaries","line-color","#53603D"},
        {"water","fill-color","#0C2230"},{"waterway","line-color","#284B61"},
        {"boundary","line-color","#66595F"},
        {"airport","fill-color","#252421"},{"airport","fill-outline-color","#4B4439"},
        {"runway","line-color","#6A6358"},
        {"road-track-casing","line-color","#28251C"},{"road-track","line-color","#696143"},
        {"road-minor-casing","line-color","#25211D"},{"road-minor","line-color","#817A70"},
        {"road-secondary-casing","line-color","#322B23"},{"road-secondary","line-color","#B5A691"},
        {"road-primary-casing","line-color","#4C3623"},{"road-primary","line-color","#C7A672"},
        {"road-motorway-casing","line-color","#54301E"},{"road-motorway","line-color","#D28751"},
        {"rail","line-color","#756C61"},
        {"buildings-flat","fill-color","#3F352D"},{"buildings-flat","fill-outline-color","#6B5847"},
        {"buildings-3d","fill-extrusion-color","#695744"}
    };
    static String style(String daylight,boolean night)throws JSONException{
        if(!night)return daylight;
        JSONObject style=new JSONObject(daylight);style.put("name","EuroRig OLED night cartography");
        JSONArray layers=style.getJSONArray("layers");
        for(int i=0;i<layers.length();i++){
            JSONObject layer=layers.getJSONObject(i),paint=layer.getJSONObject("paint");String id=layer.getString("id");
            for(String[] color:NIGHT)if(id.equals(color[0]))paint.put(color[1],color[2]);
            if(id.equals("fields"))paint.put("fill-color",new JSONArray("[\"match\",[\"get\",\"subclass\"],\"orchard\",\"#29301D\",\"vineyard\",\"#302920\",\"#22291B\"]"));
        }
        style.getJSONObject("light").put("color","#E5D4BB").put("intensity",0.38);
        return style.toString();
    }
    private MapAppearance(){}
}
