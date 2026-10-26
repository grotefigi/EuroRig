package org.eurorig.routing;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class TruckMapTest {
    @Test public void essentialRoadClassesRemainVisibleEvenWithRestrictions(){
        for(String kind:new String[]{"motorway","motorway_link","trunk","primary","secondary","tertiary","unclassified","residential","living_street","service"}){
            assertTrue(kind,TruckMap.visible(kind,Map.of("hgv","no","maxwidth","2","surface","dirt")));
        }
    }
    @Test public void pedestrianAndTrackDetailRequiresVehicleEvidence(){
        for(String kind:new String[]{"footway","path","pedestrian","cycleway","steps","bridleway","track"}){
            assertFalse(kind,TruckMap.visible(kind,Collections.emptyMap()));
            assertFalse(kind,TruckMap.visible(kind,Map.of("motor_vehicle","yes","hgv","no")));
            assertTrue(kind,TruckMap.visible(kind,Map.of("hgv","delivery")));
            assertTrue(kind,TruckMap.visible(kind,Map.of("hgv","yes")));
            assertTrue(kind,TruckMap.visible(kind,Map.of("motor_vehicle","destination")));
        }
    }
    @Test public void conditionalTruckApproachRemainsVisibleForVerification(){
        assertTrue(TruckMap.visible("pedestrian",Map.of("hgv:conditional","delivery @ (06:00-10:00)")));
        assertFalse(TruckMap.visible("path",Map.of("foot:conditional","yes @ (sunrise-sunset)")));
    }
}
