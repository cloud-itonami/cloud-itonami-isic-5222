# Business Model: Community Port Authority and Harbor Operations

## Classification
- Repository: `cloud-itonami-5222`
- ISIC Rev.5: `5222` — service activities incidental to water
  transportation
- Social impact: navigation safety, supply-chain resilience,
  harbor-worker safety

## Customer
- independent/community port authorities and harbor masters needing
  an auditable navigation-safety and vessel-movement platform
- pilotage organizations needing verifiable fitness-check and
  assignment records
- vessel operators and terminal operators needing verifiable
  berthing/pilotage clearance records
- regulators needing verifiable navigation-safety and salvage-
  coordination records
- programs that cannot accept closed, unauditable port-authority
  platforms

## Offer
- navigation-safety scope and pilotage-fitness-check management
- robotics-assisted aids-to-navigation inspection and berth/anchorage
  monitoring
- vessel-movement booking, pilotage assignment and reconciliation
  records
- vessel operator/terminal billing and disclosure records
- role-based access and immutable audit ledger

## Revenue
- self-host setup fee
- managed hosting subscription per port/harbor
- support retainer with SLA
- aids-to-navigation/buoy-tending robot integration and maintenance

## Trust Controls
- a robot action the governor refuses is never dispatched
- safety-critical actions (vessel movement outside verified
  navigation-safety scope, a pilotage assignment without a completed
  fitness check, a salvage coordination record without verified
  evidence) require human sign-off
- vessel movements cannot proceed outside verified navigation-safety
  scope
- reconciliation records require verified evidence
- sensitive vessel and cargo-flow data stays outside Git
