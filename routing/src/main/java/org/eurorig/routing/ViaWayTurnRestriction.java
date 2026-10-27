package org.eurorig.routing;

import java.util.*;

/**
 * Via-way turn restrictions support.
 * 
 * OSM via-way turn relations specify that a turn is prohibited/restricted
 * when the route passes through a specific way (edge) as the "via" edge.
 * 
 * Example: A left turn at intersection N is prohibited when coming from
 * way W and going to way E, where the route must pass through way V (via).
 */
public final class ViaWayTurnRestriction {
    public final long viaWay;      // The via way ID
    public final long fromWay;     // From way ID
    public final long toWay;       // To way ID
    public final boolean only;     // Only restriction (vs allowed)
    public final String restrictionType; // "left", "right", "straight", "no_turn"
    
    public ViaWayTurnRestriction(long viaWay, long fromWay, long toWay, boolean only, String restrictionType) {
        this.viaWay = viaWay;
        this.fromWay = fromWay;
        this.toWay = toWay;
        this.only = only;
        this.restrictionType = restrictionType;
    }
    
    /**
     * Check if a turn is restricted by this via-way rule.
     * 
     * @param incomingWay The way ID of the incoming edge
     * @param outgoingWay The way ID of the outgoing edge
     * @param viaWayPassed Whether the route passed through the via way
     * @return true if the turn is restricted/prohibited
     */
    public boolean isRestricted(long incomingWay, long outgoingWay, boolean viaWayPassed) {
        if (viaWayPassed && fromWay == incomingWay && toWay == outgoingWay) {
            return only; // Only restriction means the turn is prohibited
        }
        // For "allowed" restrictions (only=false), the turn is allowed only
        // when the via way is part of the route
        if (!only && viaWayPassed && fromWay == incomingWay && toWay == outgoingWay) {
            return false; // Allowed
        }
        return false; // Not applicable
    }
    
    /**
     * Check if a truck violates this via-way turn restriction.
     * 
     * @param truck The truck profile
     * @param permittedDelivery Whether delivery access is permitted
     * @param metresFromDestination Distance from destination
     * @return null if no violation, or a reason string if violated
     */
    public String violation(Truck truck, boolean permittedDelivery, double metresFromDestination) {
        // Via-way turn restrictions can be directional (left/right/straight/no_turn)
        // For now, we flag any via-way turn restriction as needing verification
        // since we don't have the actual turn geometry here
        return "Via-way turn restriction: " + restrictionType + " (via way " + viaWay + ")";
    }
}
