/**
 * BeforeProvisioningRule.java
 *
 * SailPoint IIQ Before Provisioning Rule
 * Fires before any provisioning operation is executed against a target application.
 * Used to validate, transform, or enrich provisioning plan data before it reaches
 * the connector layer.
 *
 * Rule Type: BeforeProvisioning
 * Tested On: SailPoint IIQ 8.2, 8.3
 *
 * Author: Aryan Patil
 */

import sailpoint.object.*;
import sailpoint.api.*;
import sailpoint.tools.*;
import java.util.*;

/**
 * Main rule entry point called by IIQ before provisioning.
 *
 * @param context       SailPointContext - the current IIQ context
 * @param plan          ProvisioningPlan - the full provisioning plan to be executed
 * @param application   Application - the target application being provisioned to
 * @param identity      Identity - the identity being provisioned
 * @return void         Modifies the plan in-place; no return value required
 */
public void execute(SailPointContext context, ProvisioningPlan plan,
                    Application application, Identity identity) throws GeneralException {

    log.debug("BeforeProvisioningRule: Starting for identity: " + identity.getName()
              + " on application: " + application.getName());

    // Validate the plan before allowing provisioning to proceed
    boolean isValid = validateProvisioningPlan(plan, identity);
    if (!isValid) {
        log.warn("BeforeProvisioningRule: Plan validation failed for identity: "
                 + identity.getName() + ". Aborting provisioning.");
        plan.setStatus(ProvisioningPlan.Status.Failed);
        return;
    }

    // Enrich account requests with derived attributes
    enrichAccountRequests(context, plan, identity);

    // Normalise attribute values to match target system format
    normaliseAttributes(plan, application);

    log.debug("BeforeProvisioningRule: Completed successfully for identity: "
              + identity.getName());
}

/**
 * Validates that the provisioning plan contains required fields and
 * that the identity is in an eligible state to be provisioned.
 *
 * @param plan      ProvisioningPlan - the plan to validate
 * @param identity  Identity - the identity being provisioned
 * @return boolean  true if the plan is valid and provisioning should proceed,
 *                  false if provisioning should be blocked
 */
private boolean validateProvisioningPlan(ProvisioningPlan plan, Identity identity) {

    if (plan == null) {
        log.error("validateProvisioningPlan: Plan is null. Cannot proceed.");
        return false;
    }

    // Block provisioning for inactive identities unless this is a leaver operation
    String lifecycleState = (String) identity.getAttribute("lifecycleState");
    if ("inactive".equalsIgnoreCase(lifecycleState)) {
        List accountRequests = plan.getAccountRequests();
        if (accountRequests != null) {
            for (Object reqObj : accountRequests) {
                ProvisioningPlan.AccountRequest req = (ProvisioningPlan.AccountRequest) reqObj;
                // Allow disable/delete operations for inactive identities
                if (req.getOperation() != ProvisioningPlan.AccountRequest.Operation.Disable &&
                    req.getOperation() != ProvisioningPlan.AccountRequest.Operation.Delete) {
                    log.warn("validateProvisioningPlan: Blocking provisioning for inactive identity: "
                             + identity.getName());
                    return false;
                }
            }
        }
    }

    // Validate required identity attributes are present
    String employeeId = (String) identity.getAttribute("employeeId");
    if (employeeId == null || employeeId.trim().isEmpty()) {
        log.error("validateProvisioningPlan: Missing employeeId for identity: "
                  + identity.getName());
        return false;
    }

    return true;
}

/**
 * Enriches account requests with derived attributes that are calculated
 * at provisioning time rather than stored on the identity cube.
 * Examples: computed display names, derived email formats, department codes.
 *
 * @param context   SailPointContext - used to query IIQ objects if needed
 * @param plan      ProvisioningPlan - the plan whose account requests will be enriched
 * @param identity  Identity - the identity whose attributes drive the enrichment
 * @return void     Modifies account requests in-place
 */
private void enrichAccountRequests(SailPointContext context, ProvisioningPlan plan,
                                   Identity identity) throws GeneralException {

    List accountRequests = plan.getAccountRequests();
    if (accountRequests == null || accountRequests.isEmpty()) {
        return;
    }

    String firstName  = identity.getFirstname();
    String lastName   = identity.getLastname();
    String department = (String) identity.getAttribute("department");
    String location   = (String) identity.getAttribute("location");

    for (Object reqObj : accountRequests) {
        ProvisioningPlan.AccountRequest req = (ProvisioningPlan.AccountRequest) reqObj;

        // Only enrich Create and Modify operations
        if (req.getOperation() == ProvisioningPlan.AccountRequest.Operation.Create ||
            req.getOperation() == ProvisioningPlan.AccountRequest.Operation.Modify) {

            // Set display name if not already present in the plan
            if (req.getAttributeRequest("displayName") == null && firstName != null && lastName != null) {
                req.add(new ProvisioningPlan.AttributeRequest(
                    "displayName",
                    ProvisioningPlan.Operation.Set,
                    firstName + " " + lastName
                ));
            }

            // Derive department code from full department name
            if (department != null) {
                String deptCode = deriveDepartmentCode(department);
                req.add(new ProvisioningPlan.AttributeRequest(
                    "departmentCode",
                    ProvisioningPlan.Operation.Set,
                    deptCode
                ));
            }

            log.debug("enrichAccountRequests: Enriched request for application: "
                      + req.getApplication());
        }
    }
}

/**
 * Derives a short department code from a full department name string.
 * Used to populate target system fields that require abbreviated codes.
 *
 * @param department  String - the full department name (e.g. "Information Technology")
 * @return String     the derived department code (e.g. "IT"), or the original
 *                    string uppercased if no mapping is found
 */
private String deriveDepartmentCode(String department) {
    if (department == null) return null;

    Map deptMap = new HashMap();
    deptMap.put("Information Technology", "IT");
    deptMap.put("Human Resources",        "HR");
    deptMap.put("Finance",                "FIN");
    deptMap.put("Operations",             "OPS");
    deptMap.put("Legal",                  "LEG");
    deptMap.put("Sales",                  "SAL");
    deptMap.put("Marketing",              "MKT");

    String code = (String) deptMap.get(department);
    return (code != null) ? code : department.toUpperCase().replaceAll("\\s+", "_");
}

/**
 * Normalises attribute values to match the format expected by the target
 * application. Handles case normalisation, whitespace trimming, and
 * application-specific formatting rules.
 *
 * @param plan         ProvisioningPlan - the plan containing attribute requests to normalise
 * @param application  Application - used to determine application-specific formatting rules
 * @return void        Modifies attribute requests in-place
 */
private void normaliseAttributes(ProvisioningPlan plan, Application application) {

    String appName = application.getName();
    List accountRequests = plan.getAccountRequests();
    if (accountRequests == null) return;

    for (Object reqObj : accountRequests) {
        ProvisioningPlan.AccountRequest req = (ProvisioningPlan.AccountRequest) reqObj;
        List attrRequests = req.getAttributeRequests();
        if (attrRequests == null) continue;

        for (Object attrObj : attrRequests) {
            ProvisioningPlan.AttributeRequest attr = (ProvisioningPlan.AttributeRequest) attrObj;
            Object value = attr.getValue();

            if (value instanceof String) {
                String strValue = ((String) value).trim();

                // Active Directory requires sAMAccountName in lowercase
                if ("Active Directory".equals(appName) && "sAMAccountName".equals(attr.getName())) {
                    strValue = strValue.toLowerCase();
                }

                // Email attributes should always be lowercase
                if (attr.getName().toLowerCase().contains("email") ||
                    attr.getName().toLowerCase().contains("mail")) {
                    strValue = strValue.toLowerCase();
                }

                attr.setValue(strValue);
            }
        }
    }
}
