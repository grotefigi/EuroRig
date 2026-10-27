package org.eurorig.routing;

import java.util.*;

/** Route preferences apply after the same hard truck/access checks in every country. */
public final class CountryPreferences {
    public enum Option {
        HIGHWAYS("highways","Avoid highways"),TOLLS("tolls","Avoid toll roads"),
        CONGESTION("congestion","Avoid congestion charge zones"),FERRIES("ferries","Avoid ferries"),
        UNPAVED("unpaved","Avoid unpaved roads"),COUNTRY("country","Avoid country");
        public final String key,title;
        Option(String key,String title){this.key=key;this.title=title;}
    }
    private final Map<String,Set<Option>> countries;
    private final Set<Option> defaults;
    public CountryPreferences(Map<String,Set<Option>> countries,Set<Option> defaults){
        Map<String,Set<Option>> copy=new HashMap<>();
        for(Map.Entry<String,Set<Option>> entry:countries.entrySet())copy.put(entry.getKey(),Set.copyOf(entry.getValue()));
        this.countries=Map.copyOf(copy);this.defaults=Set.copyOf(defaults);
    }
    public boolean avoids(String country,Option option){return countries.getOrDefault(country,defaults).contains(option);}
    public boolean active(){return !defaults.isEmpty()||countries.values().stream().anyMatch(options->!options.isEmpty());}
    public boolean everywhere(Option option){return defaults.contains(option)&&countries.values().stream().allMatch(options->options.contains(option));}
    public String violation(String country,String roadClass,boolean toll,boolean ferry,boolean unpaved){
        if(country==null||country.isEmpty())return active()?"Country metadata is missing; these route options cannot be verified":null;
        if(avoids(country,Option.COUNTRY))return "Country avoided: "+country;
        if(avoids(country,Option.HIGHWAYS)&&"motorway".equals(roadClass))return "Highway avoided in "+country;
        if(avoids(country,Option.TOLLS)&&toll)return "Toll road avoided in "+country;
        if(avoids(country,Option.FERRIES)&&ferry)return "Ferry avoided in "+country;
        if(avoids(country,Option.UNPAVED)&&unpaved)return "Unpaved road avoided in "+country;
        return null;
    }
}
