package org.eurorig.routing;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic choice of an alternative access point on a road this truck may lawfully use.
 *
 * <p>Precedence, in order: a candidate with no applicable hard restriction (the only value a caller
 * should pass is a hard verdict computed by the same rule evaluation the router uses), then the
 * shortest distance from the requested endpoint, then the recorded road class, then the way id. The
 * last two exist so that two candidates at the same distance always resolve the same way; neither
 * can lift a prohibited candidate above a lawful one, because eligibility is decided first.
 */
public final class AccessPointChoice {
    private AccessPointChoice() {}

    /** One candidate road: its way id, distance in metres, recorded level and hard verdict. */
    public static final class Candidate {
        public final long way;
        public final double metres;
        public final int level;
        public final boolean hard;

        public Candidate(long way, double metres, int level, boolean hard) {
            this.way = way;
            this.metres = metres;
            this.level = level;
            this.hard = hard;
        }
    }

    /** The chosen candidate, or null when every candidate carries a hard restriction. */
    public static Candidate choose(List<Candidate> candidates) {
        if (candidates == null) return null;
        Candidate best = null;
        for (Candidate candidate : new ArrayList<>(candidates)) {
            if (candidate == null || candidate.hard) continue;
            if (best == null || better(candidate, best)) best = candidate;
        }
        return best;
    }

    private static boolean better(Candidate candidate, Candidate best) {
        if (candidate.metres < best.metres - 0.001) return true;
        if (candidate.metres > best.metres + 0.001) return false;
        if (candidate.level != best.level) return candidate.level < best.level;
        return candidate.way < best.way;
    }
}
