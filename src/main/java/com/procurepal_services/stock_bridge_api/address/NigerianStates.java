package com.procurepal_services.stock_bridge_api.address;

import java.util.List;
import java.util.Set;

/**
 * The 36 states plus the FCT, mirroring
 * {@code stock-bridge-ui/src/constants/nigerianStates.ts} exactly - including
 * "FCT - Abuja", which is the string the seeded addresses and the frontend select
 * both use.
 *
 * Validated server-side even though the frontend renders a select, because a select
 * is a convenience and not a constraint: an API client (or a future mobile app) can
 * post anything, and a mistyped state is invisible until a rider cannot find the
 * address.
 *
 * Comparison is case-insensitive but the stored value is the buyer's, not
 * canonicalised - normalising "lagos" to "Lagos" would be nicer, but silently
 * rewriting an address field is worse than accepting the buyer's own casing.
 */
public final class NigerianStates {

    public static final List<String> ALL = List.of(
            "Abia", "Adamawa", "Akwa Ibom", "Anambra", "Bauchi", "Bayelsa", "Benue", "Borno",
            "Cross River", "Delta", "Ebonyi", "Edo", "Ekiti", "Enugu", "FCT - Abuja", "Gombe",
            "Imo", "Jigawa", "Kaduna", "Kano", "Katsina", "Kebbi", "Kogi", "Kwara", "Lagos",
            "Nasarawa", "Niger", "Ogun", "Ondo", "Osun", "Oyo", "Plateau", "Rivers", "Sokoto",
            "Taraba", "Yobe", "Zamfara");

    private static final Set<String> LOOKUP =
            ALL.stream().map(state -> state.toLowerCase()).collect(java.util.stream.Collectors.toUnmodifiableSet());

    private NigerianStates() {
    }

    public static boolean isValid(String state) {
        return state != null && LOOKUP.contains(state.trim().toLowerCase());
    }
}
