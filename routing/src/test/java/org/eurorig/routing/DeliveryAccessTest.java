package org.eurorig.routing;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Independent acceptance matrix for delivery access, expressed against the pure policy so the
 * expected outcome of each case is derived from the requirement, not from the implementation
 * being tested. Every case states which requirement it covers.
 */
public class DeliveryAccessTest {
    private final Truck truck=Truck.standard(); // 4.00 m, 2.55 m, 16.5 m, 40 t, 11.5 t

    private RestrictionRule access(String value,int flags){
        int kind=RestrictionRule.ACCESS;
        return new RestrictionRule(0,0,0,0,0,flags,Map.of("access",value),kind,"access="+value);
    }

    /** 1. A destination inside a delivery-restricted section may use that section, and only it. */
    @Test public void destinationAccessIsAdmittedOnlyInsideTheWindow(){
        AccessPolicy.Decision inside=AccessPolicy.decide(access("destination",Graph.BLOCKED),truck,120,false,true,RestrictionRule.DIR_UNKNOWN);
        assertTrue(inside.admitted());
        assertEquals(AccessPolicy.ADMITTED_ACCESS,inside.verdict);
        AccessPolicy.Decision outside=AccessPolicy.decide(access("destination",Graph.BLOCKED),truck,2001,false,true,RestrictionRule.DIR_UNKNOWN);
        assertTrue("the same road is not admitted 2 km from the destination",outside.blocked());
    }

    /** 2. Through traffic: the same restriction never becomes a shortcut. */
    @Test public void theSameRestrictionIsNotAThroughShortcut(){
        RestrictionRule destination=access("destination",Graph.BLOCKED);
        assertTrue(AccessPolicy.decide(destination,truck,5000,false,true,RestrictionRule.DIR_UNKNOWN).blocked());
        assertTrue("without the delivery permission nothing is admitted",
                AccessPolicy.decide(destination,truck,120,false,false,RestrictionRule.DIR_UNKNOWN).blocked());
    }

    /** 3. Prefer the shortest lawful restricted approach, not the first valid entry. */
    @Test public void theShorterLawfulApproachWins(){
        String longApproach="202 m entry",shortApproach="31 m entry";
        assertEquals(shortApproach,AccessPolicy.prefer(longApproach,202,shortApproach,31));
        assertEquals(shortApproach,AccessPolicy.prefer(shortApproach,31,longApproach,202));
        assertEquals("ties keep the caller's order",shortApproach,AccessPolicy.prefer(shortApproach,31,longApproach,31));
        assertEquals(longApproach,AccessPolicy.prefer(longApproach,10,null,500));
    }

    /** 4. Hard prohibitions survive every exception, including at the destination itself. */
    @Test public void hardLimitsAreNeverAdmittedAsAccess(){
        RestrictionRule bridge=new RestrictionRule(0,0,0,3.5,0,Graph.BLOCKED,
                Map.of("access","destination","maxweight","3.5"),RestrictionRule.HARD|RestrictionRule.ACCESS,"access=destination,maxweight=3.5");
        AccessPolicy.Decision decision=AccessPolicy.decide(bridge,truck,10,false,true,RestrictionRule.DIR_UNKNOWN);
        assertTrue(decision.blocked());
        assertTrue(decision.reason.contains("Gross weight limit"));
        RestrictionRule lowBridge=new RestrictionRule(3.5,0,0,0,0,Graph.BLOCKED,
                Map.of("access","private","maxheight","3.5"),RestrictionRule.HARD|RestrictionRule.ACCESS,"access=private,maxheight=3.5");
        assertTrue(AccessPolicy.decide(lowBridge,truck,10,false,true,RestrictionRule.DIR_UNKNOWN).blocked());
        RestrictionRule heavyAxle=new RestrictionRule(0,0,0,0,8,Graph.BLOCKED,
                Map.of("access","delivery"),RestrictionRule.HARD|RestrictionRule.ACCESS,"access=delivery");
        assertTrue(AccessPolicy.decide(heavyAxle,truck,10,false,true,RestrictionRule.DIR_UNKNOWN).blocked());
    }

    /** 5a. An access exception never travels against a one-way record. */
    @Test public void directionIsNotOverriddenByPermission(){
        RestrictionRule oneWay=new RestrictionRule(0,0,0,3.5,0,0,
                Map.of("maxweight:backward","3.5"),RestrictionRule.HARD|RestrictionRule.BACKWARD,"maxweight:backward=3.5");
        assertTrue("facing the limit is still blocked at the destination",
                AccessPolicy.decide(oneWay,truck,50,false,true,RestrictionRule.DIR_BACKWARD).blocked());
        assertTrue("the other direction carries no limit",
                AccessPolicy.decide(oneWay,truck,50,false,true,RestrictionRule.DIR_FORWARD).ok());
    }

    /** 5b. Turn restrictions stay in force: they are not an access question. */
    @Test public void prohibitedTurnsAreNotAccessDecisions(){
        Graph.Node[] nodes={new Graph.Node(44.0,26.0,""),new Graph.Node(44.001,26.0,""),new Graph.Node(44.002,26.0,"")};
        Graph.Edge in=new Graph.Edge(0,1,10,"A","secondary",0,0,0,0,0,0,50);
        Graph.Edge banned=new Graph.Edge(1,2,11,"B","secondary",0,0,0,0,0,0,50);
        Graph.Edge allowed=new Graph.Edge(1,2,12,"C","secondary",0,0,0,0,0,0,50);
        Graph graph=new Graph("t","t","t",false,nodes,new Graph.Edge[]{in,banned,allowed},
                List.of(new Graph.Turn(1,10,11,false)));
        assertFalse(graph.turnAllowed(in,banned));
        assertTrue(graph.turnAllowed(in,allowed));
    }

    /** 6. No lawful approach must fail explicitly, never produce a misleading accepted route. */
    @Test public void noLawfulApproachIsRefusedNotAdmitted(){
        AccessPolicy.Decision denied=AccessPolicy.decide(access("no",Graph.BLOCKED),truck,5,false,true,RestrictionRule.DIR_UNKNOWN);
        assertTrue(denied.blocked());
        assertTrue(denied.reason.contains("prohibited"));
        AccessPolicy.Decision customers=AccessPolicy.decide(access("customers",Graph.BLOCKED),truck,5,false,true,RestrictionRule.DIR_UNKNOWN);
        assertTrue("a customers-only tag is not proof of permission for this vehicle",customers.blocked());
        assertTrue(customers.reason.contains("Unsupported access exception"));
    }

    /** 7. Uncertain evidence is reported, never treated as permission. */
    @Test public void uncertainEvidenceIsNeverPermission(){
        RestrictionRule signposted=new RestrictionRule(0,0,0,0,0,Graph.UNCERTAIN,
                Map.of("maxheight","signposted"),RestrictionRule.UNCERTAIN,"maxheight=signposted");
        AccessPolicy.Decision decision=AccessPolicy.decide(signposted,truck,10,false,true,RestrictionRule.DIR_UNKNOWN);
        assertEquals(AccessPolicy.UNVERIFIED,decision.verdict);
        assertFalse(decision.admitted());
        assertFalse(decision.blocked());
    }

    /** 7b. A conditional speed limit is not the same warning as an unknown clearance. */
    @Test public void speedConditionsDoNotBecomeClearanceUncertainty(){
        RestrictionRule conditionalSpeed=new RestrictionRule(0,0,0,0,0,Graph.UNCERTAIN,
                Map.of("maxspeed:conditional","30 @ (22:00-06:00)"),RestrictionRule.UNCERTAIN,"maxspeed:conditional=30 @ (22:00-06:00)");
        assertEquals("speed metadata is not a prohibition",AccessPolicy.OK,
                AccessPolicy.decide(conditionalSpeed,truck,10,false,true,RestrictionRule.DIR_UNKNOWN).verdict);
        RestrictionRule conditionalHeight=new RestrictionRule(0,0,0,0,0,Graph.UNCERTAIN,
                Map.of("maxheight:conditional","3.5 @ (Mo-Fr)"),RestrictionRule.UNCERTAIN,"maxheight:conditional=3.5 @ (Mo-Fr)");
        assertEquals(AccessPolicy.UNVERIFIED,
                AccessPolicy.decide(conditionalHeight,truck,10,false,true,RestrictionRule.DIR_UNKNOWN).verdict);
    }

    /** 8. The painted/evaluated route and the decision come from one evaluation. */
    @Test public void routeSectionsAgreeWithThePolicyDecision(){
        Graph.Edge admitted=new Graph.Edge(0,1,1,"A","residential",0,0,0,0,0,Graph.BLOCKED,30,
                RestrictionRule.ACCESS,"access=destination");
        assertEquals(AccessPolicy.ADMITTED_ACCESS,
                AccessPolicy.decide(new RestrictionRule(0,0,0,0,0,Graph.BLOCKED,Map.of("access","destination"),
                        RestrictionRule.ACCESS,"access=destination"),truck,100,false,true,RestrictionRule.DIR_UNKNOWN).verdict);
        assertEquals(RouteSection.AUTHORISED_ACCESS,RouteSection.classify(admitted,truck));

        Graph.Edge unverified=new Graph.Edge(0,1,2,"B","secondary",0,0,0,0,0,Graph.UNCERTAIN,50,
                RestrictionRule.UNCERTAIN,"maxheight=signposted");
        assertEquals(RouteSection.UNVERIFIED,RouteSection.classify(unverified,truck));

        Graph.Edge blocked=new Graph.Edge(0,1,3,"C","secondary",3.5,0,0,0,0,0,50,
                RestrictionRule.HARD,"maxheight=3.5");
        assertEquals(RouteSection.PROHIBITED,RouteSection.classify(blocked,truck));
    }

    /** An access tag that grants nothing must not be admitted by proximity alone. */
    @Test public void hgvNoIsNotAGrant(){
        RestrictionRule noHgv=new RestrictionRule(0,0,0,0,0,Graph.BLOCKED,
                Map.of("hgv","no"),RestrictionRule.ACCESS,"hgv=no");
        AccessPolicy.Decision decision=AccessPolicy.decide(noHgv,truck,10,false,true,RestrictionRule.DIR_UNKNOWN);
        assertTrue("hgv=no does not except HGVs; proximity and permission cannot invent one",decision.blocked());
    }

    /** Leaving a road the vehicle is already on is the one non-destination exception. */
    @Test public void originEgressIsAdmittedOnlyForGrantingTags(){
        RestrictionRule privateRoad=access("private",Graph.BLOCKED);
        assertTrue(AccessPolicy.decide(privateRoad,truck,9000,true,false,RestrictionRule.DIR_UNKNOWN).admitted());
        RestrictionRule noHgv=new RestrictionRule(0,0,0,0,0,Graph.BLOCKED,Map.of("hgv","no"),RestrictionRule.ACCESS,"hgv=no");
        assertTrue(AccessPolicy.decide(noHgv,truck,9000,true,false,RestrictionRule.DIR_UNKNOWN).blocked());
    }
}
