package org.eurorig.routing;

import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class RestrictionRuleTest {
    private RestrictionRule rule(double height,double width,double weight,int flags,Map<String,String> tags){return new RestrictionRule(height,width,0,weight,0,flags,tags);}
    @Test public void oneWayLimitOnlyAppliesInItsDirection(){
        // maxweight:backward=3.5 on a 41 t truck: prohibited against the way, allowed with it.
        RestrictionRule rule=new RestrictionRule(0,0,0,3.5,0,0,Map.of("maxweight:backward","3.5"),RestrictionRule.HARD|RestrictionRule.BACKWARD,"maxweight:backward=3.5");
        assertTrue(rule.directional());
        assertNotNull(rule.violation(Truck.standard(),false,5000,false,RestrictionRule.DIR_BACKWARD));
        assertNull(rule.violation(Truck.standard(),false,5000,false,RestrictionRule.DIR_FORWARD));
        assertNotNull("unknown direction stays conservative",rule.violation(Truck.standard(),false,5000,false,RestrictionRule.DIR_UNKNOWN));
    }
    @Test public void twoWayRecordAppliesBothWays(){
        RestrictionRule rule=new RestrictionRule(0,0,0,3.5,0,0,Map.of("maxweight","3.5"),RestrictionRule.HARD,"maxweight=3.5");
        assertFalse(rule.directional());
        assertNotNull(rule.violation(Truck.standard(),false,5000,false,RestrictionRule.DIR_FORWARD));
        assertNotNull(rule.violation(Truck.standard(),false,5000,false,RestrictionRule.DIR_BACKWARD));
    }
    @Test public void directionNeverWaivesAHardLimitThatFacesTheDriver(){
        RestrictionRule rule=new RestrictionRule(3.0,0,0,0,0,0,Map.of("maxheight:forward","3.0"),RestrictionRule.HARD|RestrictionRule.FORWARD,"maxheight:forward=3.0");
        assertTrue("exceeding height is hard regardless of direction bits",rule.hard(Truck.standard()));
        assertNotNull(rule.violation(Truck.standard(),true,10,false,RestrictionRule.DIR_FORWARD));
    }
    @Test public void deliveryPermissionNeverOverridesDimensionsOrWeight(){
        Truck truck=Truck.standard();
        assertNotNull(rule(3.5,0,0,Graph.BLOCKED,Map.of("hgv","no")).violation(truck,true,50));
        assertNotNull(rule(0,2.4,0,Graph.BLOCKED,Map.of("hgv","no")).violation(truck,true,50));
        assertNotNull(rule(0,0,20,Graph.BLOCKED,Map.of("hgv","no")).violation(truck,true,50));
        assertNotNull(new RestrictionRule(0,0,12,0,10,Graph.BLOCKED,Map.of("hgv","no")).violation(truck,true,50));
    }
    @Test public void accessExceptionRequiresPermissionAndDestinationProximity(){
        RestrictionRule rule=rule(0,0,0,Graph.BLOCKED,Map.of("hgv","no"));
        assertNotNull(rule.violation(Truck.standard(),false,50));
        assertNotNull(rule.violation(Truck.standard(),true,2001));
        assertNull(rule.violation(Truck.standard(),true,1999));
    }
    @Test public void unknownBarriersAndBridgesStayBlocked(){
        for(Map<String,String> tags:new Map[]{Map.of("hgv","no","bridge","yes"),Map.of("hgv","no","barrier","gate")})
            assertNotNull(rule(0,0,0,Graph.BLOCKED,tags).violation(Truck.standard(),true,50));
        assertNotNull(rule(0,0,0,Graph.UNCERTAIN,Map.of("hgv:conditional","no @ (Mo-Fr)")).violation(Truck.standard(),true,50));
    }
    @Test public void tunnelCodesApplyInOrderAndUnknownCategoryFails(){
        Truck c=new Truck(4,2.55,16.5,40,11.5,true,false,true,true,5,80,1,3);
        assertNull(rule(0,0,0,0,Map.of("tunnel","yes","tunnel:category","B")).violation(c,false,0));
        for(String category:new String[]{"C","D","E","unknown"})assertNotNull(rule(0,0,0,0,Map.of("tunnel","yes","tunnel:category",category)).violation(c,false,0));
        assertNotNull(rule(0,0,0,0,Map.of("tunnel","yes")).violation(c,false,0));
    }
    @Test public void hazardousLoadTypesRemainDistinct(){
        Truck water=new Truck(4,2.55,16.5,40,11.5,true,false,true,true,5,80,2,0);
        assertNotNull(rule(0,0,0,0,Map.of("hazmat:water","no")).violation(water,false,0));
        assertNull(rule(0,0,0,0,Map.of("hazmat:explosives","no")).violation(water,false,0));
        assertNotNull(rule(0,0,0,0,Map.of("hazmat","no")).violation(water,true,0));
    }
    @Test public void signMetadataDoesNotDeclareClearanceButMalformedLimitsFail(){
        assertNull(rule(0,0,0,Graph.UNCERTAIN,Map.of("maxheight","default","maxheight:signed","no")).violation(Truck.standard(),false,0));
        assertNull(rule(0,0,0,Graph.UNCERTAIN,Map.of("maxweight:signed","no")).violation(Truck.standard(),false,0));
        assertNotNull(rule(0,0,0,Graph.UNCERTAIN,Map.of("maxheight","unknown")).violation(Truck.standard(),false,0));
        assertNotNull(rule(0,0,0,Graph.UNCERTAIN,Map.of("maxheight","3 m;4 m")).violation(Truck.standard(),false,0));
        assertNotNull(rule(0,0,0,Graph.UNCERTAIN,Map.of("maxheight","default")).violation(new Truck(4.2,2.55,16.5,40,11.5,false,false,true,true),false,0));
        assertNotNull(rule(3.5,0,0,Graph.UNCERTAIN,Map.of("maxheight","3.5","maxheight:signed","no")).violation(Truck.standard(),false,0));
    }
    @Test public void privateOriginEgressCannotWaivePhysicalOrTruckProhibitions(){
        assertNull(rule(0,0,0,Graph.BLOCKED,Map.of("access","private")).violation(Truck.standard(),false,9000,true));
        assertNotNull(rule(0,0,0,Graph.BLOCKED,Map.of("hgv","no")).violation(Truck.standard(),false,9000,true));
        assertNotNull(rule(3.5,0,0,Graph.BLOCKED,Map.of("access","private")).violation(Truck.standard(),false,9000,true));
    }
    /**
     * Regression for the Bucharest -> Chisinau false "no path": the truck starts on a
     * maxweight=3.5 segment. Excluding an edge inside the endpoint radius removes the only
     * reachable origin, so the restriction must be recorded instead of excluded there.
     */
    @Test public void endpointRadiusDecidesAvoidability(){
        assertTrue(RestrictionRule.atEndpoint(0,0));
        assertTrue(RestrictionRule.atEndpoint(120,5000));
        assertTrue(RestrictionRule.atEndpoint(5000,80));
        assertTrue("radius is inclusive",RestrictionRule.atEndpoint(RestrictionRule.ENDPOINT_METRES,99999));
        assertFalse("a restriction 300 m out is routable around",RestrictionRule.atEndpoint(300,5000));
        assertFalse(RestrictionRule.atEndpoint(5000,300));
    }
    /** Uncertainty is surfaced, never routed as a prohibition (RR-04). */
    @Test public void uncertaintyIsReportedButNotProhibited(){
        RestrictionRule guessed=new RestrictionRule(0,0,0,0,0,Graph.UNCERTAIN,
            Map.of("maxweight:conditional","3.5 @ (22:00-06:00)"),RestrictionRule.UNCERTAIN,"maxweight:conditional");
        assertTrue(guessed.uncertainOnly());
        assertFalse(guessed.certain());
        RestrictionRule measured=new RestrictionRule(0,0,0,3.5,0,Graph.BLOCKED,
            Map.of("maxweight","3.5"),RestrictionRule.HARD,"maxweight=3.5");
        assertFalse("a stated limit is not uncertainty",measured.uncertainOnly());
        assertTrue(measured.certain());
        RestrictionRule legacy=new RestrictionRule(0,0,0,3.5,0,Graph.BLOCKED,Map.of("maxweight","3.5"));
        assertEquals("packs without classification keep the old behaviour",0,legacy.kind);
        assertFalse(legacy.uncertainOnly());
    }
    /**
     * A stated limit is never waived by where the vehicle is standing. Proximity to the origin
     * proves neither legality nor clearance, so the router must relocate the endpoint instead.
     */
    @Test public void hardLimitsAreNeverWaivedByEndpointProximity(){
        Truck loaded = new Truck(3.89,2.5,17,41,10,false,false,true,true,5,80,0,0);
        RestrictionRule bridge = rule(0,0,3.5,0,Map.of("maxweight","3.5"));
        Truck weightLimited = new Truck(3.89,2.5,17,41,10,false,false,true,true,5,80,0,0);
        Truck light = new Truck(3.89,2.5,17,3,1.5,false,false,true,true,5,80,0,0);
        assertNotNull("41 t on a 3.5 t road is a violation even at the origin",bridge.violation(loaded,false,0,true));
        assertTrue(bridge.hard(loaded));
        assertNull("a 3 t vehicle may use it",bridge.violation(light,false,0,true));
        assertFalse(bridge.hard(light));
        assertTrue("a height limit under a bridge is hard at any distance",rule(3.0,0,0,0,Map.of("maxheight","3.0")).hard(loaded));
    }

    @Test public void deliveryWaivesAccessOnlyNeverMass(){
        Truck loaded = new Truck(3.89,2.5,17,41,10,false,false,true,true,5,80,0,0);
        RestrictionRule yard = rule(0,0,0,Graph.BLOCKED,Map.of("access","destination"));
        assertNull("a proven destination delivery may enter",yard.violation(loaded,true,120,false));
        assertNotNull("without the permission it may not",yard.violation(loaded,false,120,false));
        assertFalse(yard.hard(loaded));
        RestrictionRule limitedYard = rule(0,0,3.5,Graph.BLOCKED,Map.of("access","destination","maxweight","3.5"));
        assertNotNull("delivery permission never waives a 3.5 t limit",limitedYard.violation(loaded,true,120,false));
        assertTrue(limitedYard.hard(loaded));
    }

    /** The radius decides whether to relocate an endpoint; it never creates a right to drive. */
    @Test public void endpointRadiusDoesNotChangeLegality(){
        Truck loaded = new Truck(3.89,2.5,17,41,10,false,false,true,true,5,80,0,0);
        RestrictionRule bridge = rule(0,0,3.5,0,Map.of("maxweight","3.5"));
        for(double distance : new double[]{0,249,250,251,5000}){
            assertTrue("hard at " + distance + " m from the destination",bridge.hard(loaded));
            assertNotNull(bridge.violation(loaded,true,distance,true));
        }
    }

    @Test public void weightLimitUnderTheStartingVehicleIsStillAViolation(){
        RestrictionRule rule=rule(0,0,3.5,Graph.BLOCKED,Map.of("maxweight","3.5","oneway","yes"));
        Truck truck=new Truck(3.89,2.5,17,41,10,false,false,true,true,5,80,0,0);
        assertNotNull("the limit still applies to the vehicle",rule.violation(truck,false,400000));
        assertTrue("but it is not avoidable from the endpoint",RestrictionRule.atEndpoint(10,400000));
    }
}
