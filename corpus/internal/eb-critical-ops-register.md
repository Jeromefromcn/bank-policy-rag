---
docId: eb-critical-ops-register
title: Critical Operations Register and Tolerances for Disruption
version: "2026.1"
classification: restricted
sourceType: internal
fictional: true
---

*Fictional document for demonstration. Example Bank does not exist.*

# Critical Operations Register and Tolerances for Disruption

## 1 Purpose

1.1 This register lists Example Bank's critical operations and the tolerance for disruption set for
each, as part of the bank's operational resilience framework.

1.2 The register was approved by the **Board of Directors on 18 March 2026** and is reviewed
at least annually.

## 2 Critical operations and tolerances for disruption

| Critical operation | Supporting systems | Tolerance for disruption |
|---|---|---|
| Retail payments | PayHub | **4 hours** maximum disruption |
| Cash withdrawal (ATM network) | ATMLink | **12 hours** maximum disruption |
| Online and mobile banking access | Stratus-hosted digital channel | **6 hours** maximum disruption |
| Corporate payments and settlement | SettleCore | Completion **the same business day before the 17:30 cut-off** |

## 3 Severe but plausible scenarios

3.1 The bank tests its ability to remain within tolerance for disruption under these scenarios:
- loss of the primary data centre DC-West;
- a ransomware attack encrypting core servers;
- an outage of the primary cloud provider lasting 24 hours;
- loss of 40% of operations staff for two weeks.

3.2 The 2026 scenario test found that online banking access would exceed its tolerance under
the cloud-provider outage scenario. A remediation plan is due by **30 September 2026**.

## 4 Ownership

4.1 Each critical operation has an accountable executive named in the internal register
annex. The Chief Risk Officer owns this register.
