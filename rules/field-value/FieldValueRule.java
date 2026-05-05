/**
 * FieldValueRule.java
 *
 * SailPoint IIQ Field Value Rule
 * Dynamically computes provisioning field values during account create and modify
 * operations. Used when the value of a target system attribute must be derived
 * from identity data at provisioning time rather than stored statically.
 *
 * Common use cases:
 * - Generating sAMAccountName / UPN for Active Directory
 * - Deriving email addresses in a consistent corporate format
 * - Computing group membership based on department + job code combination
 *
 * Rule Type: FieldValue
 * Tested On: SailPoint IIQ 8.2, 8.3
 *
 * Author: Aryan Patil
 */

import sailpoint.object.*;
import sailpoint.api.*;
import sailpoint.tools.*;
import java.util.*;

/**
 * Main entry point called by IIQ to compute a field value during provisioning.
 *
 * @param context      SailPointContext - the current IIQ context
 * @param identity     Identity - the identity being provisioned
 * @param field        Field - the field whose value is being computed
 * @param application  Application - the target application being provisioned to
 * @param template     Template - the provisioning template in use
 * @param role         Bundle - the role driving the provisioning (may be null)
 * @return Object      the computed value for the field; type must match the
 *                     field's defined type (String, List, Boolean, etc.)
 */
public Object computeFieldValue(SailPointContext context, Identity identity,
                                 Field field, Application application,
                                 Template template, Bundle role)
        throws GeneralException {

    String fieldName = field.getName();
    log.debug("FieldValueRule: Computing value for field: '" + fieldName
              + "' on application: " + application.getName());

    if ("sAMAccountName".equals(fieldName)) {
        return computeSAMAccountName(context, identity);
    }

    if ("userPrincipalName".equals(fieldName)) {
        return computeUserPrincipalName(context, identity, application);
    }

    if ("mail".equals(fieldName) || "email".equals(fieldName)) {
        return computeEmailAddress(identity, application);
    }

    if ("displayName".equals(fieldName)) {
        return computeDisplayName(identity);
    }

    if ("description".equals(fieldName)) {
        return computeAccountDescription(identity);
    }

    log.warn("FieldValueRule: No computation logic defined for field: '" + fieldName + "'");
    return null;
}

/**
 * Computes the Active Directory sAMAccountName in the format:
 * first initial + last name, lowercase, max 20 characters (AD limit).
 * Handles collisions by appending a numeric suffix (e.g. jsmith, jsmith2, jsmith3).
 *
 * @param context   SailPointContext - used to check for existing accounts with the same name
 * @param identity  Identity - must have firstname and lastname attributes set
 * @return String   the computed sAMAccountName, guaranteed unique within AD up to suffix 99,
 *                  or null if firstname or lastname is missing
 */
private String computeSAMAccountName(SailPointContext context, Identity identity)
        throws GeneralException {

    String firstName = identity.getFirstname();
    String lastName  = identity.getLastname();

    if (firstName == null || lastName == null) {
        log.error("computeSAMAccountName: Missing firstname or lastname for identity: "
                  + identity.getName());
        return null;
    }

    // Format: first initial + full last name, lowercased, stripped of spaces/special chars
    String base = (firstName.substring(0, 1) + lastName)
                    .toLowerCase()
                    .replaceAll("[^a-z0-9]", "");

    // Enforce AD 20-character limit
    if (base.length() > 20) {
        base = base.substring(0, 20);
    }

    // Check for uniqueness and append suffix if needed
    String candidate = base;
    int suffix = 2;
    while (accountExists(context, "Active Directory", "sAMAccountName", candidate) && suffix < 100) {
        String suffixStr = String.valueOf(suffix);
        candidate = base.substring(0, Math.min(base.length(), 20 - suffixStr.length())) + suffixStr;
        suffix++;
    }

    log.debug("computeSAMAccountName: Computed sAMAccountName: '" + candidate
              + "' for identity: " + identity.getName());
    return candidate;
}

/**
 * Computes the Active Directory userPrincipalName (UPN) in the format:
 * sAMAccountName@domain, where the domain is read from the application configuration.
 *
 * @param context      SailPointContext - used to compute the sAMAccountName component
 * @param identity     Identity - passed through to computeSAMAccountName
 * @param application  Application - must have an "adDomain" extended attribute configured
 * @return String      the full UPN in format user@domain.com, or null if domain is not configured
 */
private String computeUserPrincipalName(SailPointContext context, Identity identity,
                                         Application application)
        throws GeneralException {

    String samAccountName = computeSAMAccountName(context, identity);
    if (samAccountName == null) return null;

    String domain = (String) application.getAttribute("adDomain");
    if (domain == null || domain.trim().isEmpty()) {
        log.error("computeUserPrincipalName: adDomain not configured on application: "
                  + application.getName());
        return null;
    }

    return samAccountName + "@" + domain.trim().toLowerCase();
}

/**
 * Computes the corporate email address for the identity.
 * Format is determined by the emailFormat attribute on the application:
 * - "firstlast"  → firstname.lastname@domain.com (default)
 * - "flast"      → firstinitiallastname@domain.com
 * - "firstl"     → firstnamelastinitial@domain.com
 *
 * @param identity     Identity - must have firstname, lastname attributes set
 * @param application  Application - must have "emailDomain" attribute configured;
 *                     optionally "emailFormat" to override the default format
 * @return String      the computed email address in lowercase, or null if required
 *                     attributes are missing
 */
private String computeEmailAddress(Identity identity, Application application) {

    String firstName = identity.getFirstname();
    String lastName  = identity.getLastname();

    if (firstName == null || lastName == null) return null;

    String domain  = (String) application.getAttribute("emailDomain");
    String format  = (String) application.getAttribute("emailFormat");

    if (domain == null) {
        log.error("computeEmailAddress: emailDomain not configured on application: "
                  + application.getName());
        return null;
    }

    firstName = firstName.toLowerCase().replaceAll("[^a-z]", "");
    lastName  = lastName.toLowerCase().replaceAll("[^a-z]", "");

    String localPart;
    if ("flast".equals(format)) {
        localPart = firstName.substring(0, 1) + lastName;
    } else if ("firstl".equals(format)) {
        localPart = firstName + lastName.substring(0, 1);
    } else {
        // Default: firstname.lastname
        localPart = firstName + "." + lastName;
    }

    return localPart + "@" + domain.toLowerCase();
}

/**
 * Computes the display name for the account in "Firstname Lastname" format.
 * Used for Active Directory displayName, Workday preferred name, and other
 * human-readable name fields across target systems.
 *
 * @param identity  Identity - must have firstname and lastname attributes set
 * @return String   the display name in "Firstname Lastname" format,
 *                  or the identity's cube name if firstname/lastname are unavailable
 */
private String computeDisplayName(Identity identity) {

    String firstName = identity.getFirstname();
    String lastName  = identity.getLastname();

    if (firstName != null && lastName != null) {
        return firstName.trim() + " " + lastName.trim();
    }

    // Fallback to identity cube name
    log.warn("computeDisplayName: Falling back to identity name for: " + identity.getName());
    return identity.getName();
}

/**
 * Computes a standardised account description for directory services.
 * Format: "Department | JobCode | Managed by IIQ"
 * Used to clearly mark IIQ-managed accounts in Active Directory and LDAP.
 *
 * @param identity  Identity - uses department and jobCode attributes if available
 * @return String   a descriptive string for the account, always non-null
 */
private String computeAccountDescription(Identity identity) {

    String department = (String) identity.getAttribute("department");
    String jobCode    = (String) identity.getAttribute("jobCode");

    StringBuilder desc = new StringBuilder("Managed by SailPoint IIQ");
    if (department != null) desc.append(" | ").append(department);
    if (jobCode != null)    desc.append(" | ").append(jobCode);

    return desc.toString();
}

/**
 * Checks whether an account with a given attribute value already exists
 * on the specified application. Used for uniqueness validation during
 * account name computation.
 *
 * @param context    SailPointContext - used to query the Link table
 * @param appName    String - the name of the application to search within
 * @param attrName   String - the account attribute to search by (e.g. "sAMAccountName")
 * @param attrValue  String - the value to check for existence
 * @return boolean   true if an account with this attribute value already exists,
 *                   false if the value is available
 */
private boolean accountExists(SailPointContext context, String appName,
                               String attrName, String attrValue)
        throws GeneralException {

    QueryOptions qo = new QueryOptions();
    qo.addFilter(Filter.eq("application.name", appName));
    qo.addFilter(Filter.ignoreCase(Filter.eq("attributes." + attrName, attrValue)));

    int count = context.countObjects(Link.class, qo);
    return count > 0;
}
