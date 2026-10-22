package org.eurorig.routing;

import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class LegacyOneWayTest {
    private RestrictionRule rule(Map<String,String> tags,double height,int flags){return new RestrictionRule(height,0,0,0,0,flags,tags);}
    private AccessPolicy.Decision decide(RestrictionRule r,int dir){return AccessPolicy.decide(r,Truck.standard(),1000,false,false,false,dir);}
    @Test public void publishedRomaniaOneWayAllowsOnlyRecordedDirection(){
        RestrictionRule r=rule(Map.of("oneway","yes","maxspeed:conditional","80 @ wet"),0,Graph.UNCERTAIN);
        assertTrue(decide(r,RestrictionRule.DIR_FORWARD).ok());
        assertTrue(decide(r,RestrictionRule.DIR_BACKWARD).blocked());
        assertTrue(decide(r,RestrictionRule.DIR_UNKNOWN).blocked());
    }
    @Test public void reverseAndBidirectionalTagsAreReadLiterally(){
        RestrictionRule reverse=rule(Map.of("oneway","-1"),0,0);
        assertTrue(decide(reverse,RestrictionRule.DIR_BACKWARD).ok());assertTrue(decide(reverse,RestrictionRule.DIR_FORWARD).blocked());
        assertTrue(decide(rule(Map.of("oneway","no"),0,0),RestrictionRule.DIR_BACKWARD).ok());
    }
    @Test public void legalDirectionNeverWaivesHeightOrPrivateAccess(){
        assertTrue(decide(rule(Map.of("oneway","yes","maxheight","3.5"),3.5,0),RestrictionRule.DIR_FORWARD).blocked());
        assertTrue(decide(rule(Map.of("oneway","yes","access","private"),0,Graph.BLOCKED),RestrictionRule.DIR_FORWARD).blocked());
    }
    @Test public void truckExemptionAndUnrelatedBicycleTagsAreHandled(){
        assertTrue(decide(rule(Map.of("oneway","yes","oneway:hgv","no"),0,0),RestrictionRule.DIR_BACKWARD).ok());
        assertTrue(decide(rule(Map.of("oneway","yes","oneway:bicycle","no"),0,0),RestrictionRule.DIR_BACKWARD).blocked());
    }
    @Test public void unresolvableDirectionsAreDisclosed(){
        assertEquals(AccessPolicy.UNVERIFIED,decide(rule(Map.of("oneway","reversible"),0,0),RestrictionRule.DIR_FORWARD).verdict);
        assertEquals(AccessPolicy.UNVERIFIED,decide(rule(Map.of("oneway:conditional","yes @ wet"),0,0),RestrictionRule.DIR_FORWARD).verdict);
    }
    @Test public void declaredEntranceNeverAuthorizesWrongWayTravel(){
        RestrictionRule r=rule(Map.of("oneway","yes","access","private"),0,Graph.BLOCKED);
        DestinationAuthorisation declared=new DestinationAuthorisation(44,26,10,DestinationAuthorisation.PRIVATE,1000);
        assertTrue(AccessPolicy.decideWithApproach(r,Truck.standard(),10,false,true,true,RestrictionRule.DIR_BACKWARD,declared,10,true,44,26,1001).blocked());
        assertTrue(AccessPolicy.decideWithApproach(r,Truck.standard(),10,false,true,true,RestrictionRule.DIR_FORWARD,declared,10,true,44,26,1001).admitted());
    }
}
