package org.eurorig.routing;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

/** Expected outcomes follow authored from/via/to membership, independently of Valhalla. */
public class TurnRestrictionTest {
    private static final Graph.Node A=p(0,0),B=p(0,.004),C=p(0,.008),D=p(0,.012),E=p(.004,.004),M=p(0,.006);
    private static Graph.Node p(double lat,double lon){return new Graph.Node(46+lat,26+lon,"");}
    private static TurnRestriction.Span s(long way,Graph.Node a,Graph.Node b){return new TurnRestriction.Span(way,List.of(a,b),0,1);}
    private static TurnRestriction rule(String value,long[] ways,Graph.Node... points){return new TurnRestriction(100,value,1,"",ways,List.of(points));}
    private static int blocked(TurnRestriction rule,TurnRestriction.Span... path){return TurnRestriction.audit(List.of(path),List.of(rule)).blockedEdge;}
    @Test public void nodeNoBlocksOnlySpecifiedDirectionAndJunction(){
        TurnRestriction no=rule("no_straight_on",new long[]{1,2},B);
        assertEquals(1,blocked(no,s(1,A,B),s(2,B,C)));
        assertEquals(-1,blocked(no,s(2,C,B),s(1,B,A)));
        assertEquals(-1,blocked(no,s(1,A,E),s(2,E,C)));
        assertEquals(-1,blocked(no,s(1,A,B),s(3,B,E)));
    }
    @Test public void nodeOnlyRequiresTheSpecifiedExit(){
        TurnRestriction only=rule("only_right_turn",new long[]{1,3},B);
        assertEquals(-1,blocked(only,s(1,A,B),s(3,B,E)));
        assertEquals(1,blocked(only,s(1,A,B),s(2,B,C)));
        assertEquals(-1,blocked(only,s(1,A,B)));
    }
    @Test public void viaNoRequiresFullHistoryAndPreservesReverseAndOtherEntry(){
        TurnRestriction no=rule("no_straight_on",new long[]{1,2,3},B,C);
        assertEquals(2,blocked(no,s(1,A,B),s(2,B,C),s(3,C,D)));
        assertEquals(-1,blocked(no,s(4,E,B),s(2,B,C),s(3,C,D)));
        assertEquals(-1,blocked(no,s(3,D,C),s(2,C,B),s(1,B,A)));
        assertEquals(-1,blocked(no,s(1,A,B),s(2,B,C),s(4,C,E)));
    }
    @Test public void onlyViaRejectsWrongEntryEarlyDepartureAndWrongExit(){
        TurnRestriction only=rule("only_straight_on",new long[]{1,2,3},B,C);
        assertEquals(1,blocked(only,s(1,A,B),s(4,B,E)));
        assertEquals(2,blocked(only,s(1,A,B),s(2,B,M),s(4,M,E)));
        assertEquals(2,blocked(only,s(1,A,B),s(2,B,C),s(4,C,E)));
        assertEquals(-1,blocked(only,s(1,A,B),s(2,B,M)));
        assertEquals(-1,blocked(only,s(4,E,B),s(2,B,C),s(4,C,E)));
    }
    @Test public void stopOrNativeSplitCannotResetHistory(){
        TurnRestriction no=rule("no_straight_on",new long[]{1,2,3},B,C);
        assertEquals(3,blocked(no,s(1,A,B),s(2,B,M),s(2,M,C),s(3,C,D)));
        TurnRestriction only=rule("only_straight_on",new long[]{1,2,3},B,C);
        assertEquals(-1,blocked(only,s(1,A,B),s(2,B,M),s(2,M,C),s(3,C,D)));
    }
    @Test public void multipleViasAreOrdered(){
        TurnRestriction no=rule("no_straight_on",new long[]{1,2,3,4},B,C,D);
        assertEquals(3,blocked(no,s(1,A,B),s(2,B,C),s(3,C,D),s(4,D,E)));
        assertEquals(-1,blocked(no,s(1,A,B),s(3,B,C),s(2,C,D),s(4,D,E)));
    }
    @Test public void viaReversalIsAnEarlyDepartureForOnly(){
        TurnRestriction only=rule("only_straight_on",new long[]{1,2,3},B,C);
        assertEquals(2,blocked(only,s(1,A,B),s(2,B,M),s(2,M,B)));
    }
    @Test public void hairpinOnViaDoesNotEraseHistory(){
        Graph.Node exit=p(.0001,.0041);
        TurnRestriction no=rule("no_straight_on",new long[]{1,2,3},B,exit);
        assertEquals(3,blocked(no,s(1,A,B),s(2,B,M),s(2,M,exit),s(3,exit,D)));
        TurnRestriction only=rule("only_straight_on",new long[]{1,2,3},B,exit);
        assertEquals(-1,blocked(only,s(1,A,B),s(2,B,M),s(2,M,exit),s(3,exit,D)));
    }
    @Test public void sameWayUTurnPreservesDirection(){
        TurnRestriction no=rule("no_u_turn",new long[]{1,1},B);
        assertEquals(1,blocked(no,s(1,A,B),s(1,B,A)));
        assertEquals(-1,blocked(no,s(1,A,B),s(1,B,C)));
        TurnRestriction only=rule("only_u_turn",new long[]{1,1},B);
        assertEquals(-1,blocked(only,s(1,A,B),s(1,B,A)));
        assertEquals(1,blocked(only,s(1,A,B),s(1,B,C)));
    }
    @Test public void duplicateBoundaryVerticesCannotHideUTurn(){
        TurnRestriction no=rule("no_u_turn",new long[]{1,1},B);
        TurnRestriction.Span incoming=new TurnRestriction.Span(1,List.of(A,B,B),0,2);
        TurnRestriction.Span outgoing=new TurnRestriction.Span(1,List.of(B,B,A),0,2);
        assertEquals(1,blocked(no,incoming,outgoing));
    }
    @Test public void uncertainIsLabelledAndCannotOverrideKnownBan(){
        TurnRestriction unknown=new TurnRestriction(101,"",2,"Conditional",new long[]{1,2},List.of());
        List<TurnRestriction.Span> path=List.of(s(1,A,B),s(2,B,C));
        TurnRestriction.Result result=TurnRestriction.audit(path,List.of(unknown,rule("no_straight_on",new long[]{1,2},B)));
        assertEquals(1,result.blockedEdge);assertEquals(Set.of(0,1),result.unverified);assertEquals(1,result.warnings.size());
    }
    @Test public void truckExceptionIsNotARestriction(){
        TurnRestriction exempt=new TurnRestriction(101,"no_straight_on",0,"Truck exception",new long[]{1,2},List.of(B));
        TurnRestriction.Result result=TurnRestriction.audit(List.of(s(1,A,B),s(2,B,C)),List.of(exempt));
        assertEquals(-1,result.blockedEdge);assertTrue(result.warnings.isEmpty());
    }
    @Test public void unknownMembershipIsNotLimitedToSupportedChainSize(){
        long[] members=new long[70];for(int i=0;i<members.length;i++)members[i]=i+1;
        TurnRestriction unknown=new TurnRestriction(101,"",2,"Oversized source relation",members,List.of());
        TurnRestriction.Result result=TurnRestriction.audit(List.of(s(70,A,B)),List.of(unknown));
        assertEquals(Set.of(0),result.unverified);assertEquals(-1,result.blockedEdge);
    }
    @Test(expected=IllegalArgumentException.class) public void disconnectedTraceCannotSupplyHistory(){
        blocked(rule("no_straight_on",new long[]{1,2},B),s(1,A,B),s(2,C,D));
    }
    @Test(expected=IllegalArgumentException.class) public void malformedSupportedRecordIsRefused(){
        rule("no_straight_on",new long[]{1,2},B,C);
    }
}
