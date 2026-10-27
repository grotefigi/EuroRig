package org.eurorig.app;

import android.content.Context;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Localizes known audit notes while retaining dynamic map evidence verbatim. */
final class RouteWarningPresentation {
    private static final Pattern RELOCATION = Pattern.compile("^(Start|Destination) moved ([0-9.]+) m: the requested point is on a road this truck may not use$");
    private static final Pattern TURN_RELATION = Pattern.compile("^Unverified turn relation (\\d+): (.*)$");

    private RouteWarningPresentation() {}

    static String text(Context context, String warning) {
        if (warning == null || warning.isEmpty()) return "";
        String turnEvidence = "Turn restrictions checked by the native engine only; independent relation evidence is unavailable in this map";
        String turnEvidenceToken = "\u0000turn-evidence\u0000";
        String normalized = warning.replace(turnEvidence, turnEvidenceToken);
        StringBuilder result = new StringBuilder();
        for (String note : normalized.split("; ")) {
            if (result.length() > 0) result.append("; ");
            if (note.equals(turnEvidenceToken)) result.append(context.getString(R.string.route_warning_turn_evidence));
            else if (note.equals("Mapped conditional or unsupported restriction needs verification"))
                result.append(context.getString(R.string.route_warning_conditional_restriction));
            else if (note.equals("Delivery road has no restriction evidence"))
                result.append(context.getString(R.string.route_warning_delivery_evidence));
            else {
                Matcher relocation = RELOCATION.matcher(note);
                Matcher turn = TURN_RELATION.matcher(note);
                if (relocation.matches()) {
                    boolean origin = relocation.group(1).equals("Start");
                    float metres = Float.parseFloat(relocation.group(2));
                    result.append(context.getString(origin ? R.string.route_warning_start_moved : R.string.route_warning_destination_moved, metres));
                } else if (turn.matches()) {
                    result.append(context.getString(R.string.route_warning_unverified_turn, turn.group(1), turn.group(2)));
                } else result.append(context.getString(R.string.route_warning_source_note, note));
            }
        }
        return result.toString();
    }
}
