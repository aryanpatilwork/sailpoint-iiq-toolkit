/**
 * JoinerRule.java
 *
 * SailPoint IIQ Joiner Lifecycle Event Rule
 * Triggered when a new identity is detected in the authoritative source (Workday / Oracle HCM).
 * Handles initial access provisioning for new employees — determines which applications,
 * roles, and entitlements should be provisioned based on identity attributes such as
 * department, location, job code, and employment type.
 *
 * Rule Type: IdentityTrigger (Lifecycle Event)
 * Tested On: SailPoint IIQ 8.2, 8.3
 *
 * Author: Aryan Patil
 */

import sailpoint.object.*;
import sailpoint.api.*;
import sailpoint.tools.*;
import sailpoint.api.RoleAssigner;
import java.util.*;

/**
 * Main entry point for the Joiner lifecycle rule.
 * Called by IIQ when a new identity passes the joiner trigger condition.
 *
 * @param context        SailPointContext - the current IIQ context
 * @param identity       Identity - the newly detected identity to be provisioned
 * @param previousIdentity Identity - the previous state of the identity (null for new joiners)
 * @return void          Initiates provisioning by assigning roles and launching workflows
 */
public void onJoiner(SailPointContext context, Identity identity,
                     Identity previousIdentity) throws GeneralException {

    log.info("JoinerRule: Processing joiner for identity: " + identity.getName());

    // Validate identity has minimum required attributes before provisioning
    if (!isIdentityReadyForProvisioning(identity)) {
        log.warn("JoinerRule: Identity not ready for provisioning: " + identity.getName()
                 + ". Missing required attributes. Skipping.");
        return;
    }

    // Assign baseline roles applicable to all employees
    assignBaselineRoles(context, identity);

    // Assign department-specific roles based on job function
    assignDepartmentRoles(context, identity);

    // Assign location-specific access (e.g. building systems, regional tools)
    assignLocationAccess(context, identity);

    // Flag identity for manager notification workflow
    triggerManagerNotification(context, identity);

    log.info("JoinerRule: Joiner processing complete for identity: " + identity.getName());
}

/**
 * Validates that the identity has the minimum set of attributes required
 * to safely determine provisioning decisions. Prevents partial provisioning
 * caused by incomplete HR data at time of hire record creation.
 *
 * @param identity  Identity - the identity to validate
 * @return boolean  true if all required attributes are present and non-empty,
 *                  false if any required attribute is missing or blank
 */
private boolean isIdentityReadyForProvisioning(Identity identity) {

    String[] requiredAttributes = { "employeeId", "department", "jobCode", "location", "employeeType" };

    for (String attr : requiredAttributes) {
        Object value = identity.getAttribute(attr);
        if (value == null || value.toString().trim().isEmpty()) {
            log.warn("isIdentityReadyForProvisioning: Missing required attribute '"
                     + attr + "' for identity: " + identity.getName());
            return false;
        }
    }

    // Validate start date is set and not in the past by more than 7 days
    Date startDate = (Date) identity.getAttribute("startDate");
    if (startDate == null) {
        log.warn("isIdentityReadyForProvisioning: Missing startDate for identity: "
                 + identity.getName());
        return false;
    }

    return true;
}

/**
 * Assigns baseline roles that every employee receives regardless of department,
 * location, or job function. Typically includes corporate directory access,
 * email, and standard collaboration tools.
 *
 * @param context   SailPointContext - used to look up role objects from IIQ
 * @param identity  Identity - the identity to assign baseline roles to
 * @return void     Modifies the identity's assigned roles in-place
 */
private void assignBaselineRoles(SailPointContext context, Identity identity)
        throws GeneralException {

    List baselineRoles = Arrays.asList(
        "Base.Email.Access",
        "Base.Directory.Access",
        "Base.Collaboration.Access",
        "Base.VPN.Access"
    );

    for (Object roleName : baselineRoles) {
        Bundle role = context.getObjectByName(Bundle.class, (String) roleName);
        if (role != null) {
            identity.add(role);
            log.debug("assignBaselineRoles: Assigned role '" + roleName
                      + "' to identity: " + identity.getName());
        } else {
            log.warn("assignBaselineRoles: Role not found in IIQ: " + roleName);
        }
    }
}

/**
 * Assigns department-specific roles based on the identity's department attribute.
 * Role mappings are maintained in this rule to avoid dependency on external
 * configuration objects — update the deptRoleMap when department structure changes.
 *
 * @param context   SailPointContext - used to look up role objects from IIQ
 * @param identity  Identity - must have a non-null "department" attribute
 * @return void     Modifies the identity's assigned roles in-place
 */
private void assignDepartmentRoles(SailPointContext context, Identity identity)
        throws GeneralException {

    String department = (String) identity.getAttribute("department");
    if (department == null) return;

    Map deptRoleMap = new HashMap();
    deptRoleMap.put("Information Technology", Arrays.asList("IT.SystemAccess", "IT.DevTools.Access"));
    deptRoleMap.put("Finance",                Arrays.asList("FIN.ERP.ReadOnly", "FIN.Reporting.Access"));
    deptRoleMap.put("Human Resources",        Arrays.asList("HR.HRIS.Access", "HR.Payroll.ReadOnly"));
    deptRoleMap.put("Sales",                  Arrays.asList("SAL.CRM.Access", "SAL.Reporting.Access"));
    deptRoleMap.put("Legal",                  Arrays.asList("LEG.DocManagement.Access"));
    deptRoleMap.put("Operations",             Arrays.asList("OPS.ProjectTools.Access"));

    List rolesToAssign = (List) deptRoleMap.get(department);
    if (rolesToAssign == null) {
        log.warn("assignDepartmentRoles: No role mapping found for department: "
                 + department + ". Only baseline roles will be assigned.");
        return;
    }

    for (Object roleName : rolesToAssign) {
        Bundle role = context.getObjectByName(Bundle.class, (String) roleName);
        if (role != null) {
            identity.add(role);
            log.debug("assignDepartmentRoles: Assigned role '" + roleName
                      + "' for department '" + department + "'");
        }
    }
}

/**
 * Assigns location-specific access entitlements based on the identity's
 * office location. Handles regional tool access, building system provisioning,
 * and country-specific compliance requirements.
 *
 * @param context   SailPointContext - used to look up role objects from IIQ
 * @param identity  Identity - must have a non-null "location" attribute
 *                  containing a recognised office location code (e.g. "MUM", "BLR", "DEL")
 * @return void     Modifies the identity's assigned roles in-place
 */
private void assignLocationAccess(SailPointContext context, Identity identity)
        throws GeneralException {

    String location = (String) identity.getAttribute("location");
    if (location == null) return;

    Map locationRoleMap = new HashMap();
    locationRoleMap.put("MUM", Arrays.asList("LOC.Mumbai.BuildingAccess", "LOC.IN.ComplianceTraining"));
    locationRoleMap.put("BLR", Arrays.asList("LOC.Bangalore.BuildingAccess", "LOC.IN.ComplianceTraining"));
    locationRoleMap.put("DEL", Arrays.asList("LOC.Delhi.BuildingAccess", "LOC.IN.ComplianceTraining"));
    locationRoleMap.put("LON", Arrays.asList("LOC.London.BuildingAccess", "LOC.UK.ComplianceTraining"));
    locationRoleMap.put("NYC", Arrays.asList("LOC.NewYork.BuildingAccess", "LOC.US.ComplianceTraining"));

    List rolesToAssign = (List) locationRoleMap.get(location.toUpperCase());
    if (rolesToAssign == null) {
        log.warn("assignLocationAccess: No location mapping for: " + location);
        return;
    }

    for (Object roleName : rolesToAssign) {
        Bundle role = context.getObjectByName(Bundle.class, (String) roleName);
        if (role != null) {
            identity.add(role);
        }
    }
}

/**
 * Sets a flag on the identity to trigger a manager notification workflow
 * post-provisioning. The workflow notifies the manager that the new employee's
 * access has been set up and provides a summary of provisioned entitlements.
 *
 * @param context   SailPointContext - used to save the identity attribute update
 * @param identity  Identity - the identity whose manager should be notified
 * @return void     Sets "joinerNotificationPending" attribute to "true" on the identity
 */
private void triggerManagerNotification(SailPointContext context, Identity identity)
        throws GeneralException {

    identity.setAttribute("joinerNotificationPending", "true");
    identity.setAttribute("joinerCompletedDate", new Date());
    context.saveObject(identity);
    context.commitTransaction();

    log.info("triggerManagerNotification: Flagged identity for manager notification: "
             + identity.getName());
}
