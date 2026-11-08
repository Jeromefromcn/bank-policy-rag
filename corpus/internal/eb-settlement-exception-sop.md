---
docId: eb-settlement-exception-sop
title: Settlement Exception Handling Procedure
version: "1.2"
classification: internal
sourceType: internal
fictional: true
---

*Fictional document for demonstration. Example Bank does not exist.*

# Settlement Exception Handling Procedure

## 1 Scope

1.1 This procedure covers exceptions in corporate payments and securities settlement
processed through **SettleCore**.

## 2 Exception types

2.1 **Unmatched instruction**: the counterparty's instruction does not match ours.
2.2 **Failed settlement**: the settlement did not complete on the intended date.
2.3 **Missed cut-off**: a payment was not released before the 17:30 cut-off.

## 3 Escalation

3.1 An exception not resolved within **2 hours** is escalated to the Settlement Supervisor.

3.2 An exception aged more than **2 business days** is escalated to the Head of Operations and
reported in the daily operations risk report.

3.3 A missed cut-off on a corporate payment is treated as an incident and logged under the
Technology Incident Management Standard if a system fault caused it.

## 4 Compensation claims

4.1 Interest compensation claims arising from a failed settlement are approved by the Head of
Operations for amounts up to HKD 50,000, and by the Chief Operating Officer above that.
