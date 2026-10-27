package org.eurorig.app;

import android.content.Context;
import org.eurorig.routing.*;

/** Presents known blocking truck-profile issues in the selected app language. */
final class ProfileIssuePresentation {
    private ProfileIssuePresentation() {}

    static String text(Context context, Truck truck, String issue) {
        if (issue == null || issue.isEmpty()) return "";
        if (Store.TRUCK_REVIEW.equals(issue)) return context.getString(R.string.profile_invalid_saved);
        String unsupportedWeight = NativeRouter.profileSupportIssue(truck);
        if (!unsupportedWeight.isEmpty() && unsupportedWeight.equals(issue)) return context.getString(R.string.profile_weight_unsupported);
        return issue;
    }
}
