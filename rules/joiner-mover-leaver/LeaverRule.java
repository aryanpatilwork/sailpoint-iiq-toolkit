/**
 * LeaverRule.java
 *
 * SailPoint IIQ Leaver Lifecycle Event Rule
 * Triggered when an identity is terminated in the authoritative HR source.
 * Handles revocation of all access across connected applications, disabling
 * of accounts, and escalation of privileged access for immediate remediation.
 *
 * Rule Type: IdentityTrigger (Lifecycle Event)
 * Tested On: SailPoint IIQ 8.2, 8.3
 *
 * Author: Aryan Patil
 */

import sailpoint.object.*;
import sailpoint.api.*;
import sailpoint.tools.*;
import java.util.*;

/**
 * Main entry point for the Leaver lifecycle rule.
 * Called by IIQ when a termination event is detected in the authoritative source.
 *
 * @param context          SailPointContext - the current IIQ context
 * @param identity         Identity - the terminated identity
 * @param previousIdentity Identity - the identity state before termination was detected
 * @return void            Initiates access revocation and account disablement
 */
public void onLeaver(SailPointContext context, Identity identity,
                     Identity previousIdentity) throws GeneralException {

    log.info("LeaverRule: Processing leaver for identity: " + identity.getName());

    // Immediately escalate if identity holds privileged access
    boolean hasPrivilegedAccess = checkPrivilegedAccess(context, identity);
    if (hasPrivilegedAccess) {
        escalatePrivilegedAccessRevocation(context, identity);
    }

    // Revoke all assigned roles and entitlements
    revokeAllRoles(context, identity);

    // Disable accounts across all connected applications
    disableAllAccounts(context, identity);

    // Set termination metadata on the identity for audit purposes
    recordTerminationMetadata(context, identity);

    log.info("LeaverRule: Leaver processing complete for identity: " + identity.getName());
}

/**
 * Checks whether the identity currently holds any privileged access entitlements
 * that require immediate escalation and out-of-band revocation.
 * Privileged access includes CyberArk safe memberships, admin roles, and
 * service account ownership.
 *
 * @param context   SailPointContext - used to inspect current entitlements
 * @param identity  Identity - the identity to check for privileged access
 * @return boolean  true if the identity holds any privileged entitlements,
 *                  false otherwise
 */
private boolean checkPrivilegedAccess(SailPointContext context, Identity identity)
        throws GeneralException {

    List privilegedIndicators = Arrays.asList(
        "CyberArk.SafeMember",
        "AD.DomainAdmins",
        "AD.EnterpriseAdmins",
        "ServiceAccount.Owner",
        "PAM.PrivilegedUser"
    );

    List links = identity.getLinks();
    if (links == null) return false;

    for (Object linkObj : links) {
        Link link = (Link) linkObj;
        List entitlements = link.getEntitlements();
        if (entitlements == null) continue;

        for (Object entObj : entitlements) {
            String entitlement = entObj.toString();
            for (Object indicator : privilegedIndicators) {
                if (entitlement.contains((String) indicator)) {
                    log.warn("checkPrivilegedAccess: Privileged access detected: '"
                             + entitlement + "' for identity: " + identity.getName());
                    return true;
                }
            }
        }
    }
    return false;
}

/**
 * Creates an escalation work item for the security operations team when
 * privileged access is detected on a leaver identity. Privileged access
 * must be reviewed and revoked immediately — not queued with standard provisioning.
 *
 * @param context   SailPointContext - used to create the escalation work item
 * @param identity  Identity - the leaver with privileged access requiring escalation
 * @return void     Creates a WorkItem in IIQ assigned to the SOC team owner
 */
private void escalatePrivilegedAccessRevocation(SailPointContext context, Identity identity)
        throws GeneralException {

    log.warn("escalatePrivilegedAccessRevocation: Creating escalation for identity: "
             + identity.getName());

    WorkItem workItem = new WorkItem();
    workItem.setType(WorkItem.Type.Generic);
    workItem.setName("URGENT: Privileged Access Revocation Required — " + identity.getName());
    workItem.setDescription(
        "Identity " + identity.getName() + " has been terminated and holds privileged access. "
        + "Immediate revocation is required. Please review CyberArk safe memberships, "
        + "Active Directory admin groups, and any service account ownerships."
    );

    // Assign to the security operations owner configured in system config
    String socOwner = context.getConfiguration().getString("leaverEscalationOwner");
    if (socOwner != null) {
        workItem.setOwnerName(socOwner);
    }

    context.saveObject(workItem);
    context.commitTransaction();
}

/**
 * Revokes all roles currently assigned to the leaver identity.
 * Removes both detected and assigned roles to ensure no residual
 * entitlements remain post-termination.
 *
 * @param context   SailPointContext - used to save the identity after role removal
 * @param identity  Identity - the identity from which all roles will be removed
 * @return void     Modifies the identity's role assignments in-place and saves
 */
private void revokeAllRoles(SailPointContext context, Identity identity)
        throws GeneralException {

    List assignedRoles = identity.getAssignedRoles();
    if (assignedRoles != null && !assignedRoles.isEmpty()) {
        log.info("revokeAllRoles: Removing " + assignedRoles.size()
                 + " assigned roles from identity: " + identity.getName());
        assignedRoles.clear();
    }

    List detectedRoles = identity.getDetectedRoles();
    if (detectedRoles != null && !detectedRoles.isEmpty()) {
        log.info("revokeAllRoles: Clearing " + detectedRoles.size()
                 + " detected roles from identity: " + identity.getName());
        detectedRoles.clear();
    }

    context.saveObject(identity);
    context.commitTransaction();
}

/**
 * Disables all linked accounts across connected applications for the leaver identity.
 * Sets the account status to disabled rather than deleting — accounts are retained
 * for audit and legal hold purposes and deleted via a separate scheduled task
 * after the configured retention period.
 *
 * @param context   SailPointContext - used to query and update linked accounts
 * @param identity  Identity - the identity whose linked accounts will be disabled
 * @return void     Updates account status across all application links
 */
private void disableAllAccounts(SailPointContext context, Identity identity)
        throws GeneralException {

    List links = identity.getLinks();
    if (links == null || links.isEmpty()) {
        log.info("disableAllAccounts: No linked accounts found for identity: "
                 + identity.getName());
        return;
    }

    log.info("disableAllAccounts: Disabling " + links.size()
             + " accounts for identity: " + identity.getName());

    for (Object linkObj : links) {
        Link link = (Link) linkObj;
        link.setAttribute("IIQDisabled", "true");
        link.setAttribute("leaverDisabledDate", new Date());
        context.saveObject(link);
        log.debug("disableAllAccounts: Disabled account '" + link.getNativeIdentity()
                  + "' on application '" + link.getApplication().getName() + "'");
    }

    context.commitTransaction();
}

/**
 * Records termination metadata on the identity cube for audit trail purposes.
 * Sets the lifecycle state, termination date, and processing status attributes
 * required for compliance reporting and legal hold identification.
 *
 * @param context   SailPointContext - used to save the updated identity
 * @param identity  Identity - the identity on which to record termination metadata
 * @return void     Updates identity attributes and commits the transaction
 */
private void recordTerminationMetadata(SailPointContext context, Identity identity)
        throws GeneralException {

    identity.setAttribute("lifecycleState",      "terminated");
    identity.setAttribute("terminationDate",      new Date());
    identity.setAttribute("leaverProcessingStatus", "complete");
    identity.setAttribute("leaverProcessedBy",   "IIQ-LeaverRule");

    context.saveObject(identity);
    context.commitTransaction();

    log.info("recordTerminationMetadata: Termination metadata recorded for identity: "
             + identity.getName());
}
