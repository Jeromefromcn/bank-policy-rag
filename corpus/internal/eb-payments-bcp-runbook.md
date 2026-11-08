---
docId: eb-payments-bcp-runbook
title: Payments Service Business Continuity Runbook
version: "3.0"
classification: internal
sourceType: internal
fictional: true
---

*Fictional document for demonstration. Example Bank does not exist.*

# Payments Service Business Continuity Runbook

## 1 Service covered

1.1 This runbook covers **PayHub**, Example Bank's retail payments platform for faster
payments and inter-bank transfers.

1.2 PayHub runs active–standby: primary site **DC-West**, recovery site **DC-East**.

## 2 Recovery objectives

2.1 Recovery Time Objective (**RTO**): **2 hours** from the decision to invoke.

2.2 Recovery Point Objective (**RPO**): **5 minutes** of committed transactions.

2.3 These objectives sit within the tolerance for disruption set for retail payments in the Critical
Operations Register.

## 3 Invocation

3.1 The BCP Coordinator invokes this runbook on instruction from the Incident Manager during
an S1 incident affecting PayHub, or when DC-West is lost.

3.2 The decision to fail over is taken by the Head of Technology Operations. If they cannot be
reached within 15 minutes, the Chief Information Officer decides.

## 4 Failover steps

4.1 Freeze inbound payment acceptance at the channel gateway.

4.2 Confirm database replication lag to DC-East is within the RPO.

4.3 Promote the DC-East database to primary.

4.4 Switch DNS and the payment network connection to DC-East.

4.5 Run the reconciliation check against the payment network's settlement report.

4.6 Re-open payment acceptance and notify the Major Incident Bridge.

## 5 Testing

5.1 A full failover test to DC-East is performed **every six months**.

5.2 A desktop walkthrough of this runbook is performed every quarter.

5.3 Test results, including any missed RTO, are reported to the Technology Risk Committee.
