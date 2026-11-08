---
docId: eb-cyber-escalation-playbook
title: Cyber Incident Escalation Playbook
version: "1.4"
classification: restricted
sourceType: internal
fictional: true
---

*Fictional document for demonstration. Example Bank does not exist.*

# Cyber Incident Escalation Playbook

## 1 Detection

1.1 Security alerts are raised by the **SENTRY** security monitoring platform and the
**HALO** endpoint agent, and triaged by the Security Operations Centre (SOC).

## 2 Priority levels

2.1 **P1**: confirmed compromise of a production system, or ransomware activity.
**P2**: suspected compromise. **P3**: policy violation without compromise.

## 3 Escalation chain

3.1 For a P1 incident, escalation follows this chain and time limits from confirmation:

| Step | Escalate to | Within |
|---|---|---|
| 1 | SOC Lead | 15 minutes |
| 2 | Chief Information Security Officer (CISO) | 30 minutes |
| 3 | Chief Executive and the Crisis Management Committee | 1 hour |

3.2 The CISO decides whether to isolate affected network segments. Isolation of PayHub also
requires the Head of Technology Operations.

## 4 External support

4.1 The bank holds a forensic retainer with **Northgate Forensics**, engaged by the CISO for
any P1 incident.

4.2 Law enforcement is contacted only by the CISO after consulting Legal.

## 5 Records

5.1 All evidence is preserved in the isolated evidence store for at least **7 years**.
