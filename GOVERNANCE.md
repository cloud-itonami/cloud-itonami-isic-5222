# Governance

`cloud-itonami-5222` is an OSS open-business blueprint for community
port authority and harbor operations, robotics-premised.

## Maintainers
Maintainers may merge changes that preserve these invariants:
- a robot action the governor refuses is never dispatched to hardware.
- the Port Authority Governor remains independent of the advisor.
- hard policy violations (out-of-scope vessel movement, an unfit
  pilotage assignment, an unverified salvage coordination record)
  cannot be overridden by human approval.
- every dispatch, sign-off, clearance and reconciliation path is
  auditable.
- sensitive vessel and cargo-flow data stays outside Git.

## Decision Records
Architecture decisions live in `docs/adr/`. Changes to the trust model, storage contract, public business model, operator certification or license should add or update an ADR.

## Operator Governance
Anyone may fork and operate independently. itonami.cloud certification is a separate trust mark and should require security, robot-safety, audit and data-flow review.

Certified operators can lose certification for:
- bypassing robot-safety or navigation-safety-scope checks
- mishandling vessel or cargo-flow data
- misrepresenting certification status
- failing to respond to safety incidents
