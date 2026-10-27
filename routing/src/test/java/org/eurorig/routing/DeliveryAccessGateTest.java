package org.eurorig.routing;

import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

/** Acceptance tests for the fail-safe delivery-access gate (AC1-AC9). */
public class DeliveryAccessGateTest {
    private RestrictionRule rule(Map<String,String> tags){return new RestrictionRule(0,0,0,0,0,Graph.BLOCKED,tags);}
    private Map<String,String> delivery(){Map<String,String> m=new HashMap<>();m.put("access","destination");return m;}

    // AC1
    @Test public void unknownDistanceCannotGrantDeliveryAccess(){
        assertNotNull("NaN distance must not grant access",
                rule(delivery()).violation(Truck.standard(),true,Double.NaN));
    }
    // AC2
    @Test public void infiniteDistanceCannotGrantDeliveryAccess(){
        assertNotNull("infinite distance must not grant access",
                rule(delivery()).violation(Truck.standard(),true,Double.POSITIVE_INFINITY));
    }
    // AC3
    @Test public void negativeDistanceCannotGrantDeliveryAccess(){
        assertNotNull("negative distance must not grant access",
                rule(delivery()).violation(Truck.standard(),true,-1));
    }
    // AC4
    @Test public void knownNearDistanceStillGrantsAccess(){
        assertNull("50 m with permission must stay allowed",
                rule(delivery()).violation(Truck.standard(),true,50));
    }
    // AC5
    @Test public void boundaryIsExactlyTwoThousandMetres(){
        assertNull(rule(delivery()).violation(Truck.standard(),true,2000));
        assertNotNull(rule(delivery()).violation(Truck.standard(),true,2000.001));
    }
    // AC6
    @Test public void originEgressWaiverSurvivesUnknownDistance(){
        Map<String,String> tags=new HashMap<>();tags.put("access","private");
        assertNull("leaving private origin must still be allowed",
                rule(tags).violation(Truck.standard(),false,Double.NaN,true));
    }
    // AC7
    @Test public void originEgressCannotWaiveProhibitionOrPhysicalLimit(){
        Map<String,String> hgv=new HashMap<>();hgv.put("hgv","no");
        assertNotNull(rule(hgv).violation(Truck.standard(),false,9000,true));
        assertNotNull(new RestrictionRule(3.5,0,0,0,0,Graph.BLOCKED,Map.of("access","private"))
                .violation(Truck.standard(),false,9000,true));
    }
    // AC8
    @Test public void nullTagsDoNotThrow(){
        RestrictionRule r=new RestrictionRule(0,0,0,0,0,Graph.BLOCKED,null);
        String reason=r.violation(Truck.standard(),true,50);
        assertNotNull("a rule with no evidence must not silently allow access",reason);
        assertNotNull(r.violation(Truck.standard(),false,Double.NaN,true));
    }
    // AC9
    @Test public void unknownAndTooFarProduceDistinctMessages(){
        String unknown=rule(delivery()).violation(Truck.standard(),true,Double.NaN);
        String far=rule(delivery()).violation(Truck.standard(),true,5000);
        assertNotNull(unknown);assertNotNull(far);
        assertNotEquals("unknown distance must be reported differently from out-of-window",unknown,far);
    }
}
