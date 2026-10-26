package org.eurorig.app;

import android.content.*;
import java.util.*;
import org.eurorig.routing.*;

/** Country-keyed persistence shared by route options and the native route audit. */
final class CountryRouteOptions {
    static final String COUNTRIES="Albania|AL;Andorra|AD;Armenia|AM;Austria|AT;Azerbaijan|AZ;Belarus|BY;Belgium|BE;Bosnia and Herzegovina|BA;Bulgaria|BG;Croatia|HR;Cyprus|CY;Czechia|CZ;Denmark|DK;Estonia|EE;Faroe Islands|FO;Finland|FI;France|FR;Georgia|GE;Germany|DE;Gibraltar|GI;Greece|GR;Hungary|HU;Iceland|IS;Ireland|IE;Italy|IT;Kazakhstan|KZ;Kosovo|XK;Latvia|LV;Liechtenstein|LI;Lithuania|LT;Luxembourg|LU;Malta|MT;Moldova|MD;Monaco|MC;Montenegro|ME;Netherlands|NL;North Macedonia|MK;Norway|NO;Poland|PL;Portugal|PT;Romania|RO;San Marino|SM;Serbia|RS;Slovakia|SK;Slovenia|SI;Spain|ES;Sweden|SE;Switzerland|CH;Türkiye|TR;Ukraine|UA;United Kingdom|GB;Vatican City|VA";
    private final SharedPreferences saved;
    CountryRouteOptions(Context context){saved=context.getSharedPreferences("country_route_options",0);}
    boolean enabled(){return saved.getBoolean("enabled",false);}
    private static String key(String country,CountryPreferences.Option option){return country+"_"+option.key;}
    boolean get(String country,CountryPreferences.Option option,Truck truck){
        boolean fallback=option==CountryPreferences.Option.HIGHWAYS?NavigationSettings.current!=null&&NavigationSettings.current.avoidHighways:option==CountryPreferences.Option.TOLLS?truck.avoidTolls:option==CountryPreferences.Option.FERRIES?truck.avoidFerries:option==CountryPreferences.Option.UNPAVED&&truck.avoidUnpaved;
        return saved.getBoolean(key(country,option),fallback);
    }
    void set(String country,CountryPreferences.Option option,boolean value){
        if(!country.matches("[A-Z]{2}"))throw new IllegalArgumentException("Invalid country");
        saved.edit().putBoolean("enabled",true).putBoolean(key(country,option),value).apply();
    }
    CountryPreferences snapshot(Truck truck){
        Map<String,Set<CountryPreferences.Option>> countries=new HashMap<>();
        for(String entry:COUNTRIES.split(";")){
            String code=entry.split("\\|")[1];Set<CountryPreferences.Option> options=EnumSet.noneOf(CountryPreferences.Option.class);
            for(CountryPreferences.Option option:CountryPreferences.Option.values())if(get(code,option,truck))options.add(option);
            countries.put(code,options);
        }
        Set<CountryPreferences.Option> defaults=EnumSet.noneOf(CountryPreferences.Option.class);
        if(NavigationSettings.current!=null&&NavigationSettings.current.avoidHighways)defaults.add(CountryPreferences.Option.HIGHWAYS);
        if(truck.avoidTolls)defaults.add(CountryPreferences.Option.TOLLS);
        if(truck.avoidFerries)defaults.add(CountryPreferences.Option.FERRIES);
        if(truck.avoidUnpaved)defaults.add(CountryPreferences.Option.UNPAVED);
        return new CountryPreferences(countries,defaults);
    }
    static String name(String code){for(String entry:COUNTRIES.split(";")){String[] pair=entry.split("\\|");if(pair[1].equals(code))return pair[0];}return code;}
    List<String> shownCountries(){
        LinkedHashSet<String> countries=new LinkedHashSet<>();
        if(Store.route!=null)countries.addAll(Store.route.countries);
        if(Store.graph!=null){String mapName=Store.graph.name.toLowerCase(Locale.ROOT);List<String> installed=new ArrayList<>();for(String entry:COUNTRIES.split(";"))if(mapName.contains(entry.split("\\|")[0].toLowerCase(Locale.ROOT)))installed.add(entry);installed.sort(Comparator.comparingInt(entry->mapName.indexOf(entry.split("\\|")[0].toLowerCase(Locale.ROOT))));for(String entry:installed)countries.add(entry.split("\\|")[1]);}
        countries.addAll(saved.getStringSet("shown",Collections.emptySet()));
        if(countries.isEmpty())countries.add("RO");return new ArrayList<>(countries);
    }
    void showCountry(String code){Set<String> shown=new LinkedHashSet<>(saved.getStringSet("shown",Collections.emptySet()));shown.add(code);saved.edit().putStringSet("shown",shown).apply();}
}
