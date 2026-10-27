package org.eurorig.routing;

import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

/** Tests for conditional access restrictions. */
public class RestrictionRuleConditionalTest {
    private RestrictionRule rule(Map<String,String> tags){
        return new RestrictionRule(0,0,0,0,0,Graph.BLOCKED,tags);
    }
    
    // access:conditional should return UNCERTAIN
    @Test public void accessConditionalReturnsUncertain(){
        Map<String,String> tags=new HashMap<>();
        tags.put("access:conditional","yes@Mo-Fr 07:00-19:00");
        String violation=rule(tags).violation(Truck.standard(),true,500);
        assertNotNull("access:conditional should return UNCERTAIN",violation);
        assertTrue(violation.contains("Conditional access restriction"));
    }
    
    // hgv:conditional should return UNCERTAIN
    @Test public void hgvConditionalReturnsUncertain(){
        Map<String,String> tags=new HashMap<>();
        tags.put("hgv:conditional","yes@weight<=3.5t");
        String violation=rule(tags).violation(Truck.standard(),true,500);
        assertNotNull("hgv:conditional should return UNCERTAIN",violation);
        assertTrue(violation.contains("Conditional access restriction"));
    }
    
    // Generic access=yes with no conditional should allow
    @Test public void genericAccessYesAllows(){
        Map<String,String> tags=new HashMap<>();
        tags.put("access","yes");
        String violation=rule(tags).violation(Truck.standard(),true,500);
        assertNull("generic access=yes should allow",violation);
    }
}
