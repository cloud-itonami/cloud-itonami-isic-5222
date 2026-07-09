# Operator Guide

## First Deployment
1. Register operator, port/harbor, navigation-safety scope, pilots,
   staff and robots.
2. Import existing vessel-movement booking and billing history.
3. Run read-only navigation-safety-scope and aids-to-navigation robot
   mission dry-runs.
4. Configure safety-class allowed sets and human sign-off paths.
5. Publish a dry-run reconciliation record and audit export.

## Minimum Production Controls
- navigation-safety-scope validation before any vessel-movement
  dispatch
- governor gate on every robot action before dispatch
- human sign-off for :high/:safety-critical actions (out-of-scope
  vessel movement, an unfit pilotage assignment, an unverified
  salvage coordination record)
- evidence-backed reconciliation records
- audit export for every dispatch, sign-off and reconciliation record
- backup manual port-authority process

## Certification
Certified operators must prove robot-safety integrity, navigation-
safety-scope discipline, evidence-backed reconciliation records and
human review for vessel-movement-affecting actions.
