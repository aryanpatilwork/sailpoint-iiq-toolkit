/**
 * AccountCorrelationRule.java
 *
 * SailPoint IIQ Account Correlation Rule
 * Determines which IIQ Identity a discovered account belongs to.
 * Used during aggregation when default correlation (email/employeeId match)
 * is insufficient — handles complex enterprise environments with legacy
 * account naming conventions, merged entity data, and data quality issues.
 *
 * Rule Type: Correlation
 * Tested On: SailPoint IIQ 8.2, 8.3
 *
 * Author: Aryan Patil
 */

import sailpoint.object.*;
import sailpoint.api.*;
import sailpoint.tools.*;
import java.util.*;

/**
 * Main correlation entry point called by IIQ during account aggregation.
 * Returns an identity name if a match is found, or null if no match.
 *
 * @param context      SailPointContext - the current IIQ context
 * @param environment  Map - additional correlation context provided by IIQ
 * @param application  Application - the application being aggregated
 * @param account      ResourceObject - the account being correlated
 * @return Object      String identity name if correlated, null if no match found
 */
public Object correlate(SailPointContext context, Map environment,
                         Application application, ResourceObject account)
        throws GeneralException {

    log.debug("AccountCorrelationRule: Correlating account: " + account.getIdentity()
              + " from application: " + application.getName());

    // Strategy 1: Correlate by employeeId (most reliable — HR-sourced)
    String identity = correlateByEmployeeId(context, account);
    if (identity != null) {
        log.debug("AccountCorrelationRule: Matched by employeeId -> " + identity);
        return identity;
    }

    // Strategy 2: Correlate by email address
    identity = correlateByEmail(context, account);
    if (identity != null) {
        log.debug("AccountCorrelationRule: Matched by email -> " + identity);
        return identity;
    }

    // Strategy 3: Correlate by normalised display name (fuzzy fallback)
    identity = correlateByDisplayName(context, account);
    if (identity != null) {
        log.debug("AccountCorrelationRule: Matched by displayName -> " + identity);
        return identity;
    }

    log.warn("AccountCorrelationRule: No match found for account: " + account.getIdentity());
    return null;
}

/**
 * Attempts to correlate the account to an IIQ identity using the employeeId attribute.
 * EmployeeId is the most reliable correlation key as it is sourced directly from the
 * authoritative HR system (Workday / Oracle HCM).
 *
 * @param context  SailPointContext - used to search the identity cube
 * @param account  ResourceObject - the account being correlated; must contain
 *                 an "employeeId" attribute to match
 * @return String  the IIQ identity name if a unique match is found, null otherwise
 */
private String correlateByEmployeeId(SailPointContext context, ResourceObject account)
        throws GeneralException {

    String employeeId = (String) account.getAttribute("employeeId");
    if (employeeId == null || employeeId.trim().isEmpty()) {
        return null;
    }

    employeeId = employeeId.trim();

    Filter filter = Filter.eq("employeeId", employeeId);
    List identities = context.getObjects(Identity.class,
        new QueryOptions().addFilter(filter));

    if (identities != null && identities.size() == 1) {
        return ((Identity) identities.get(0)).getName();
    }

    if (identities != null && identities.size() > 1) {
        log.warn("correlateByEmployeeId: Multiple identities found for employeeId: "
                 + employeeId + ". Skipping to avoid incorrect correlation.");
    }

    return null;
}

/**
 * Attempts to correlate the account to an IIQ identity using the email address.
 * Normalises both the account email and identity email to lowercase before comparison
 * to handle case inconsistencies across systems.
 *
 * @param context  SailPointContext - used to search the identity cube
 * @param account  ResourceObject - the account being correlated; checks both
 *                 "mail" and "email" attributes
 * @return String  the IIQ identity name if a unique match is found, null otherwise
 */
private String correlateByEmail(SailPointContext context, ResourceObject account)
        throws GeneralException {

    // Check both common email attribute names
    String email = (String) account.getAttribute("mail");
    if (email == null) {
        email = (String) account.getAttribute("email");
    }
    if (email == null || email.trim().isEmpty()) {
        return null;
    }

    email = email.trim().toLowerCase();

    Filter filter = Filter.ignoreCase(Filter.eq("email", email));
    List identities = context.getObjects(Identity.class,
        new QueryOptions().addFilter(filter));

    if (identities != null && identities.size() == 1) {
        return ((Identity) identities.get(0)).getName();
    }

    return null;
}

/**
 * Attempts to correlate the account to an IIQ identity using the display name.
 * This is a fallback strategy for legacy accounts that lack structured identifiers.
 * Normalises the display name by trimming whitespace and collapsing internal spaces
 * before performing a case-insensitive search.
 *
 * @param context  SailPointContext - used to search the identity cube
 * @param account  ResourceObject - the account being correlated; uses the
 *                 "displayName" or "cn" attribute for matching
 * @return String  the IIQ identity name if a unique, unambiguous match is found,
 *                 null if no match or multiple matches are found
 */
private String correlateByDisplayName(SailPointContext context, ResourceObject account)
        throws GeneralException {

    String displayName = (String) account.getAttribute("displayName");
    if (displayName == null) {
        displayName = (String) account.getAttribute("cn");
    }
    if (displayName == null || displayName.trim().isEmpty()) {
        return null;
    }

    // Normalise: trim and collapse multiple spaces
    displayName = displayName.trim().replaceAll("\\s+", " ");

    // Build first/last name from display name for identity search
    String[] parts = displayName.split(" ", 2);
    if (parts.length < 2) {
        return null;
    }

    String firstName = parts[0];
    String lastName  = parts[1];

    Filter filter = Filter.and(
        Filter.ignoreCase(Filter.eq("firstname", firstName)),
        Filter.ignoreCase(Filter.eq("lastname",  lastName))
    );

    List identities = context.getObjects(Identity.class,
        new QueryOptions().addFilter(filter));

    // Only return a match if exactly one identity found — name matches are ambiguous
    if (identities != null && identities.size() == 1) {
        return ((Identity) identities.get(0)).getName();
    }

    if (identities != null && identities.size() > 1) {
        log.warn("correlateByDisplayName: Multiple identities found for displayName: '"
                 + displayName + "'. Cannot safely correlate.");
    }

    return null;
}
