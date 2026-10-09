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
    /**
     * The Vienna Convention signs the compiler reads are mapped to the documented OSM keys
     * `hazmat:water=no` and `hazmat:explosive=no` (OpenStreetMap Wiki, Key:hazmat - hazard-specific
     * key suffixes). A way carrying only the hazmat prohibition is flagged HAZMAT by the compiler, so
     * the audit must refuse it for the matching load and leave every other load alone.
     */
    @Test public void documentedExplosiveHazmatKeyIsEnforced(){
        Truck explosives=new Truck(4,2.55,16.5,40,11.5,true,false,false,false,5,80,4,0);
        Truck water=new Truck(4,2.55,16.5,40,11.5,true,false,false,false,5,80,2,0);
        RestrictionRule documented=rule(0,0,0,Graph.HAZMAT,Map.of("hazmat:explosive","no"));
        assertNotNull(documented.violation(explosives,false,0));
        assertNull(documented.violation(water,false,0));
        assertNull(documented.violation(Truck.standard(),false,0));
        assertNotNull(rule(0,0,0,Graph.HAZMAT,Map.of("hazmat:explosives","no")).violation(explosives,false,0));
        assertNotNull(rule(0,0,0,Graph.HAZMAT,Map.of("hazmat:water","no")).violation(water,false,0));
        assertNotNull(rule(0,0,0,Graph.HAZMAT,Map.of("hazmat:explosive","no")).violation(explosives,true,50));
    }
    /**
     * A tunnel's ADR category is mapped with the tunnel restriction code as the key suffix
     * (OpenStreetMap Wiki, Key:hazmat): category D is hazmat:B..D=no with hazmat:E=yes, category B is
     * hazmat:B=no with hazmat:C..E=yes. The load's own code decides, and a code mapping that omits it
     * cannot clear the load - the same fail-closed rule the unmapped-category guard already applies.
     */
    @Test public void documentedTunnelCodeKeysAreEnforced(){
        Truck codeB=new Truck(4,2.55,16.5,40,11.5,true,false,false,false,5,80,1,2);
        Truck codeC=new Truck(4,2.55,16.5,40,11.5,true,false,false,false,5,80,1,3);
        Truck codeD=new Truck(4,2.55,16.5,40,11.5,true,false,false,false,5,80,1,4);
        Truck codeE=new Truck(4,2.55,16.5,40,11.5,true,false,false,false,5,80,1,5);
        Map<String,String> categoryD=Map.of("hazmat:B","no","hazmat:C","no","hazmat:D","no","hazmat:E","yes");
        Map<String,String> categoryB=Map.of("hazmat:B","no","hazmat:C","yes","hazmat:D","yes","hazmat:E","yes");
        assertNotNull(rule(0,0,0,Graph.HAZMAT,categoryD).violation(codeD,false,0));
        assertNotNull(rule(0,0,0,Graph.HAZMAT,categoryD).violation(codeC,false,0));
        assertNotNull(rule(0,0,0,Graph.HAZMAT,categoryD).violation(codeB,false,0));
        assertNull(rule(0,0,0,Graph.HAZMAT,categoryD).violation(codeE,false,0));
        assertNotNull(rule(0,0,0,Graph.HAZMAT,categoryB).violation(codeB,false,0));
        assertNull(rule(0,0,0,Graph.HAZMAT,categoryB).violation(codeC,false,0));
        assertNotNull(rule(0,0,0,Graph.HAZMAT,categoryB).violation(codeB,true,100));
        // Listing one code only declares nothing about this load's code: it must not clear it.
        assertNotNull(rule(0,0,0,Graph.HAZMAT,Map.of("hazmat:E","no")).violation(codeC,false,0));
        // A tunnel without a category stays unmapped and excluded, whatever its code keys say.
        assertNotNull(rule(0,0,0,Graph.HAZMAT,Map.of("tunnel","yes","hazmat:E","yes")).violation(codeC,false,0));
        // An unspecific load and a way with no code keys keep their current verdicts.
        assertNull(rule(0,0,0,Graph.HAZMAT,categoryD).violation(Truck.standard(),false,0));
        assertNull(rule(0,0,0,Graph.HAZMAT,Map.of("maxaxles","9")).violation(codeD,false,0));
    }
    /**
     * A recognised hazard key clears a load only for a general-access value (OSM access values:
     * yes/designated/permissive). A scope-limited, malformed or unknown value is evidence this build
     * cannot evaluate, and the unmapped-category guard and unsupported access exception already refuse
     * such evidence; the hazard keys must not clear a hazardous load on it either.
     */
    @Test public void unsupportedHazardValuesDoNotClearLoads(){
        Truck water=new Truck(4,2.55,16.5,40,11.5,true,false,false,false,5,80,2,0);
        Truck explosives=new Truck(4,2.55,16.5,40,11.5,true,false,false,false,5,80,4,0);
        Truck codeC=new Truck(4,2.55,16.5,40,11.5,true,false,false,false,5,80,1,3);
        for(String unsupported:new String[]{"destination","delivery","private","customers","official","unknown","","YES"})
            assertNotNull(rule(0,0,0,Graph.HAZMAT,Map.of("hazmat",unsupported)).violation(explosives,false,0));
        assertNotNull(rule(0,0,0,Graph.HAZMAT,Map.of("hazmat:water","destination")).violation(water,false,0));
        assertNotNull(rule(0,0,0,Graph.HAZMAT,Map.of("hazmat:explosive","private")).violation(explosives,false,0));
        assertNotNull(rule(0,0,0,Graph.HAZMAT,Map.of("hazmat:C","unknown")).violation(codeC,false,0));
        assertNotNull(rule(0,0,0,Graph.HAZMAT,Map.of("hazmat:C","")).violation(codeC,false,0));
        assertNotNull(rule(0,0,0,Graph.HAZMAT,Map.of("hazmat:C","unknown")).violation(codeC,true,100));
        // A general-access value still clears exactly the load it names.
        assertNull(rule(0,0,0,Graph.HAZMAT,Map.of("hazmat","designated")).violation(explosives,false,0));
        assertNull(rule(0,0,0,Graph.HAZMAT,Map.of("hazmat:water","permissive")).violation(water,false,0));
        assertNull(rule(0,0,0,Graph.HAZMAT,Map.of("hazmat:explosive","yes")).violation(explosives,false,0));
        assertNull(rule(0,0,0,Graph.HAZMAT,Map.of("hazmat:C","yes")).violation(codeC,false,0));
        // Ordinary trucks, other loads and code keys with no tunnel code keep their verdicts.
        assertNull(rule(0,0,0,Graph.HAZMAT,Map.of("hazmat","destination")).violation(Truck.standard(),false,0));
        assertNull(rule(0,0,0,Graph.HAZMAT,Map.of("hazmat:water","destination")).violation(explosives,false,0));
        assertNull(rule(0,0,0,Graph.HAZMAT,Map.of("hazmat:C","unknown")).violation(water,false,0));
    }
    /**
     * The compiler flags HAZMAT for any `hazmat*` key, but the audit only evaluates hazmat:water,
     * hazmat:explosive and the ADR code letters B..E. An unmodelled group - the ADR class suffixes
     * (hazmat:1=no, hazmat:6.1=no), a group alias (hazmat:flammable=no), or any other suffix - cannot be
     * matched to this load, so it must not clear a hazardous load either.
     */
    @Test public void unmodelledHazmatGroupsDoNotClearLoads(){
        Truck water=new Truck(4,2.55,16.5,40,11.5,true,false,false,false,5,80,2,0);
        Truck explosives=new Truck(4,2.55,16.5,40,11.5,true,false,false,false,5,80,4,0);
        Truck codeC=new Truck(4,2.55,16.5,40,11.5,true,false,false,false,5,80,1,3);
        assertNotNull(rule(0,0,0,Graph.HAZMAT,Map.of("hazmat:1","no")).violation(explosives,false,0));
        assertNotNull(rule(0,0,0,Graph.HAZMAT,Map.of("hazmat:6.1","no")).violation(explosives,false,0));
        assertNotNull(rule(0,0,0,Graph.HAZMAT,Map.of("hazmat:flammable","no")).violation(explosives,false,0));
        assertNotNull(rule(0,0,0,Graph.HAZMAT,Map.of("hazmat:F","no")).violation(water,false,0));
        assertNotNull(rule(0,0,0,Graph.HAZMAT,Map.of("hazmat:A","no")).violation(explosives,false,0));
        assertNotNull(rule(0,0,0,Graph.HAZMAT,Map.of("hazmat:9","destination")).violation(water,true,100));
        // Suffix collisions must not be hidden behind prefix matching: only the exact keys evaluated
        // above are known groups, so these fail closed like any other unmodelled group.
        assertNotNull(rule(0,0,0,Graph.HAZMAT,Map.of("hazmat:explosive_custom","no")).violation(explosives,false,0));
        assertNotNull(rule(0,0,0,Graph.HAZMAT,Map.of("hazmat:explosives2","no")).violation(explosives,false,0));
        assertNotNull(rule(0,0,0,Graph.HAZMAT,Map.of("hazmat:water_custom","no")).violation(water,false,0));
        assertNotNull(rule(0,0,0,Graph.HAZMAT,Map.of("hazmat:c","no")).violation(explosives,false,0));
        // A group that grants general access does not restrict, and ordinary trucks stay unaffected.
        assertNull(rule(0,0,0,Graph.HAZMAT,Map.of("hazmat:X","yes")).violation(explosives,false,0));
        assertNull(rule(0,0,0,Graph.HAZMAT,Map.of("hazmat:1","no")).violation(Truck.standard(),false,0));
        // The evaluated groups keep their own decisions for loads they do not name.
        assertNull(rule(0,0,0,Graph.HAZMAT,Map.of("hazmat:water","no")).violation(explosives,false,0));
        assertNull(rule(0,0,0,Graph.HAZMAT,Map.of("hazmat:explosive","yes")).violation(water,false,0));
        assertNull(rule(0,0,0,Graph.HAZMAT,Map.of("hazmat:C","yes")).violation(codeC,false,0));
    }
    /**
     * `none`, `unsigned` and `no_sign` are documented equivalents of a national default height reference
     * for maxheight (OSM Wiki, Key:maxheight - non-numerical values: `none` is "intended to be equivalent
     * to default", `unsigned` is used with the same meaning), so the existing 4 m default-height policy
     * applies to them exactly as to `default` instead of clearing every truck height. The other limit
     * keys are deliberately left as they were: the cited source does not establish their behaviour for
     * these values, so scoping must not be extrapolated from it (see handoff).
     */
    @Test public void nonNumericDefaultHeightReferencesFollowTheDefaultPolicy(){
        Truck tall=new Truck(4.2,2.55,16.5,40,11.5,false,false,false,false);
        for(String value:new String[]{"default","none","unsigned","no_sign"}){
            assertNotNull(rule(0,0,0,Graph.UNCERTAIN,Map.of("maxheight",value)).violation(tall,false,0));
            assertNull(rule(0,0,0,Graph.UNCERTAIN,Map.of("maxheight",value)).violation(Truck.standard(),false,0));
        }
        assertNotNull(rule(0,0,0,Graph.UNCERTAIN,Map.of("maxheight","below_default")).violation(Truck.standard(),false,0));
        assertNotNull(rule(0,0,0,Graph.UNCERTAIN,Map.of("maxheight","no_indications")).violation(Truck.standard(),false,0));
        // Held out of this change: unchanged, and not to be widened without primary per-key documentation.
        assertNull(rule(0,0,0,Graph.UNCERTAIN,Map.of("maxwidth","none")).violation(Truck.standard(),false,0));
        assertNull(rule(0,0,0,Graph.UNCERTAIN,Map.of("maxweight","unsigned")).violation(Truck.standard(),false,0));
        assertNotNull(rule(0,0,0,Graph.UNCERTAIN,Map.of("maxlength","no_sign")).violation(Truck.standard(),false,0));
        // A numeric clearance keeps its ordinary decision and a truck-free way keeps clearing.
        assertNull(rule(0,0,0,Graph.UNCERTAIN,Map.of("maxheight","4.5")).violation(Truck.standard(),false,0));
        assertNull(rule(0,0,0,Graph.UNCERTAIN,Map.of("oneway","yes")).violation(Truck.standard(),false,0));
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
