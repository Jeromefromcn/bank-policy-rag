---
docId: eb-incident-mgmt-standard
title: Technology Incident Management Standard
version: "1.0"
classification: internal
sourceType: internal
fictional: true
---

*Fictional document for demonstration. Example Bank does not exist.*

# Technology Incident Management Standard

## 1 Purpose and scope

1.1 This standard sets how Example Bank detects, classifies, resolves and reviews technology
incidents. It applies to all production systems, including systems operated by third parties
on the bank's behalf.

1.2 It implements the bank's obligations on technology incident management under the
supervisory guidance on technology risk management.

## 2 Roles

2.1 The **Incident Manager** on duty owns every open incident from declaration to closure.
The role is staffed 24x7 by the Technology Operations Centre.

2.2 The **Major Incident Bridge** is a standing conference line opened by the Incident Manager
for any Severity 1 or Severity 2 incident.

2.3 The **Head of Technology Operations** is accountable for this standard and approves any
exception to it.

## 3 Severity levels

3.1 Every incident is assigned a severity within 15 minutes of detection.

3.2 Severity definitions:

| Severity | Definition |
|---|---|
| S1 | A critical operation is unavailable or materially degraded, or customer data may be compromised |
| S2 | A customer-facing service is degraded but a workaround exists |
| S3 | An internal service is degraded with no direct customer impact |
| S4 | A minor fault with no service impact |

3.3 An incident affecting a critical operation listed in the Critical Operations Register is
always S1, regardless of its apparent size.

## 4 Notification

4.1 For an S1 incident, the Incident Manager notifies the Chief Information Officer within
**30 minutes** of declaration.

4.2 Compliance is informed of every S1 incident within **1 hour** of declaration, so that any
regulatory notification can be made without delay.

4.3 Cyber security incidents are additionally escalated under the Cyber Incident Escalation
Playbook.

## 5 Resolution and closure

5.1 Status updates are issued every 30 minutes for S1 and every 2 hours for S2 until the
incident is resolved.

5.2 An incident is closed only after the service owner confirms normal operation.

## 6 Post-incident review

6.1 A post-incident review is completed within **10 business days** of resolution for every
S1 and S2 incident.

6.2 The review identifies the root cause, the actions to prevent recurrence, an owner and a
due date for each action.

6.3 Open review actions are reported monthly to the Technology Risk Committee.
