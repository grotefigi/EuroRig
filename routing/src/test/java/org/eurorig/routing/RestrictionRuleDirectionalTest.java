package org.eurorig.routing;

import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

/** Tests for directional access restrictions. */
public class RestrictionRuleDirectionalTest {
    private RestrictionRule rule(Map<String,String> tags){
        return new RestrictionRule(0,0,0,0,0,Graph.BLOCKED,tags);
    }
    
    // Directional access: forward=no should block
    @Test public void directionalForwardNoBlocks(){
        Map<String,String> tags=new HashMap<>();
        tags.put("access:forward","no");
        String violation=rule(tags).violation(Truck.standard(),true,500);
        assertNotNull("forward=no should block",violation);
        assertTrue(violation.contains("Directional access restricted"));
    }
    
    // Directional access: forward=yes should allow (if generic access allows)
    @Test public void directionalForwardYesAllows(){
        Map<String,String> tags=new HashMap<>();
        tags.put("access","yes");
        tags.put("access:forward","yes");
        String violation=rule(tags).violation(Truck.standard(),true,500);
        assertNull("forward=yes with access=yes should allow",violation);
    }
    
    // Directional access: backward=destination should allow delivery
    @Test public void directionalBackwardDestinationAllowsDelivery(){
        Map<String,String> tags=new HashMap<>();
        tags.put("access:backward","destination");
        String violation=rule(tags).violation(Truck.standard(),true,500);
        assertNull("backward=destination should allow delivery",violation);
    }
    
    // Mixed: generic access=yes but forward=no - should block for forward direction
    @Test public void mixedAccessGenericYesForwardNo(){
        Map<String,String> tags=new HashMap<>();
        tags.put("access","yes");
        tags.put("access:forward","no");
        String violation=rule(tags).violation(Truck.standard(),true,500);
        assertNotNull("forward=no should block even with generic access=yes",violation);
    }
}
