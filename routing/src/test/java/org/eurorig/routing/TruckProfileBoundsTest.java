package org.eurorig.routing;

import org.junit.Test;
import static org.junit.Assert.*;

/** Representation limits are not native-routing or national vehicle approvals. */
public class TruckProfileBoundsTest {
    private Truck truck(double[] values,int axles,double speed,int load,int tunnel){
        return new Truck(values[0],values[1],values[2],values[3],values[4],false,
                         false,true,true,axles,speed,load,tunnel,200);
    }
    @Test public void scalarBoundariesRejectNonfiniteNonpositiveAndAboveMaximum(){
        double[] maxima={10,10,50,200,40};
        for(int field=0;field<maxima.length;field++){
            double[] values={4,2.55,16.5,200,11.5};values[field]=maxima[field];
            assertNotNull(truck(values,20,120,7,5));
            for(double invalid:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,Math.nextUp(maxima[field])}){
                final double[] changed=values.clone();changed[field]=invalid;
                assertThrows(IllegalArgumentException.class,()->truck(changed,20,120,7,5));
            }
        }
    }
    @Test public void axleGrossContradictionHasStrictEqualityBoundary(){
        double[] values={4,2.55,16.5,11.5,11.5};assertNotNull(truck(values,2,10,0,0));
        values[4]=Math.nextUp(11.5);assertThrows(IllegalArgumentException.class,()->truck(values,2,10,0,0));
    }
    @Test public void speedCountLoadAndTunnelDomainsAreExplicit(){
        double[] values={4,2.55,16.5,40,11.5};
        for(int count:new int[]{2,20})for(double speed:new double[]{10,120})
            for(int load=0;load<=7;load++)for(int tunnel:new int[]{0,2,3,4,5}){
                Truck t=truck(values,count,speed,load,tunnel);
                assertEquals(load!=0||tunnel!=0,t.hazmat);
            }
        for(int count:new int[]{1,21})assertThrows(IllegalArgumentException.class,()->truck(values,count,80,0,0));
        for(double speed:new double[]{Math.nextDown(10d),Math.nextUp(120d),Double.NaN,Double.POSITIVE_INFINITY})
            assertThrows(IllegalArgumentException.class,()->truck(values,5,speed,0,0));
        for(int load:new int[]{-1,8})assertThrows(IllegalArgumentException.class,()->truck(values,5,80,load,0));
        for(int tunnel:new int[]{-1,1,6})assertThrows(IllegalArgumentException.class,()->truck(values,5,80,0,tunnel));
    }
}
