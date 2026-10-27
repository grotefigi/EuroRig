package org.eurorig.routing;

import org.junit.Test;
import static org.junit.Assert.*;

/** Model representation and warning contracts; native routing limits are tested separately. */
public class TruckMassTest {
    private Truck truck(double loaded,double permitted){return new Truck(4,2.55,16.5,loaded,11.5,false,false,true,true,5,80,0,0,permitted);}
    @Test public void exactPermittedMassDoesNotWarn(){assertFalse(truck(40,40).exceedsPermittedMass());}
    @Test public void loadedMassRemainsIndependentOfPermittedMass(){Truck t=truck(41,40);assertEquals(41,t.weight,0);assertEquals(40,t.permittedWeight,0);assertTrue(t.exceedsPermittedMass());}
    @Test public void modelRepresentationBoundDoesNotCertifyNativeSupport(){assertFalse(truck(200,200).exceedsPermittedMass());assertThrows(IllegalArgumentException.class,()->truck(200.0001,200));}
    @Test public void invalidLoadedMassIsRejected(){for(double value:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY})assertThrows(IllegalArgumentException.class,()->truck(value,40));}
    @Test public void invalidPermittedMassIsRejected(){for(double value:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY,200.0001})assertThrows(IllegalArgumentException.class,()->truck(40,value));}
    @Test public void warningToleranceHasAnExplicitFloatingPointBoundary(){double limit=40+0.001;assertFalse(truck(limit,40).exceedsPermittedMass());assertTrue(truck(Math.nextUp(limit),40).exceedsPermittedMass());}
}
