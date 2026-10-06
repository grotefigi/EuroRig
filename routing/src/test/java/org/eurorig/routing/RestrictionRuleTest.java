package org.eurorig.routing;

import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class RestrictionRuleTest {
    private RestrictionRule rule(double height,double width,double weight,int flags,Map<String,String> tags){return new RestrictionRule(height,width,0,weight,0,flags,tags);}
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
}
