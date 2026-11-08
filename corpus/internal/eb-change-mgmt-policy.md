---
docId: eb-change-mgmt-policy
title: Change Management and Release Policy
version: "2.1"
classification: internal
sourceType: internal
fictional: true
---

*Fictional document for demonstration. Example Bank does not exist.*

# Change Management and Release Policy

## 1 Scope

1.1 This policy applies to every change to production systems, infrastructure and
configuration, including changes made by third-party providers.

## 2 Change types

2.1 **Standard changes** are pre-approved, low-risk and repeatable. They follow a published
procedure and need no Change Advisory Board (CAB) review.

2.2 **Normal changes** are reviewed by the CAB. They must be submitted at least **5 business
days** before the planned implementation date.

2.3 **Emergency changes** restore service or close an urgent security exposure. They are
approved by the Head of Technology Operations, and reviewed retrospectively by the CAB within
**2 business days**.

## 3 Change Advisory Board

3.1 The CAB meets every Tuesday and Thursday.

3.2 Each normal change records a risk rating, a test summary, an implementation plan and a
back-out plan. A change without a tested back-out plan is rejected.

## 4 Segregation of duties

4.1 The developer of a change may not deploy that change to production.

4.2 Production deployment is performed by the Release Engineering team using the approved
pipeline. Manual changes to production require an emergency change record.

## 5 Change freeze periods

5.1 No normal changes are implemented during the following freeze periods:
- the last **3 business days** of each calendar quarter;
- **15 December to 2 January** inclusive;
- the **2 business days before and after Lunar New Year**.

5.2 Emergency changes remain permitted during a freeze, with the additional approval of the
Chief Information Officer.

## 6 Post-implementation review

6.1 Every change that causes an incident is subject to a post-implementation review, linked to
the post-incident review under the Technology Incident Management Standard.
