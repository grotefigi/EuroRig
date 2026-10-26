package org.eurorig.routing;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class CountryPreferencesTest {
    @Test public void countriesKeepIndependentSwitches(){
        CountryPreferences p=new CountryPreferences(Map.of("RO",Set.of(CountryPreferences.Option.TOLLS),"MD",Set.of()),Set.of());
        assertNotNull(p.violation("RO","primary",true,false,false));
        assertNull(p.violation("MD","primary",true,false,false));
        assertNull(p.violation("RO","primary",false,false,false));
    }
    @Test public void eachRecordedAvoidanceAppliesOnlyToItsFeature(){
        CountryPreferences p=new CountryPreferences(Map.of("RO",Set.of(CountryPreferences.Option.HIGHWAYS,CountryPreferences.Option.FERRIES,CountryPreferences.Option.UNPAVED)),Set.of());
        assertNotNull(p.violation("RO","motorway",false,false,false));
        assertNotNull(p.violation("RO","primary",false,true,false));
        assertNotNull(p.violation("RO","service_other",false,false,true));
        assertNull(p.violation("RO","residential",false,false,false));
    }
    @Test public void avoidedCountriesAndMissingMetadataNeverSilentlyPass(){
        CountryPreferences p=new CountryPreferences(Map.of("HU",Set.of(CountryPreferences.Option.COUNTRY)),Set.of());
        assertNotNull(p.violation("HU","primary",false,false,false));
        assertNotNull(p.violation("","primary",false,false,false));
        assertNull(p.violation("RO","primary",false,false,false));
        assertNull(new CountryPreferences(Map.of(),Set.of()).violation("","",false,false,false));
    }
}
