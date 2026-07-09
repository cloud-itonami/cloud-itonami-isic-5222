# cloud-itonami-5222

Open Business Blueprint for **ISIC Rev.5 5222**: service activities
incidental to water transportation (port authority and harbor
operations, pilotage, vessel traffic services, aids to navigation and
salvage coordination).

This repository designs a forkable OSS business for community port
authority operations: navigation-safety-scope management, robotics-
assisted aids-to-navigation inspection and berth/anchorage
monitoring, and vessel-movement booking/reconciliation records — run
by a qualified operator so a port authority or harbor master keeps
its own safety-certification and navigation-clearance history instead
of renting a closed port-operations platform.

## Scope note: port authority, not carriage or cargo handling

`cloud-itonami-isic-5011` (passenger ferry) and `cloud-itonami-isic-
5020` (marine cargo/tanker) are CARRIERS — businesses that move goods
or people aboard their own vessel. `cloud-itonami-isic-5224` (cargo
handling) is a terminal SERVICE that loads and unloads cargo on
behalf of multiple carriers. This repository is deliberately scoped
to the SEPARATE business of port authority and harbor operations: the
infrastructure and navigation-safety authority that governs vessel
movement, berthing and pilotage within a port, independent of any
single vessel operator or cargo terminal. This is a distinctly
licensed activity in every jurisdiction: IMO SOLAS Chapter V (Safety
of Navigation) governs vessel traffic services; the International
Convention on Salvage 1989 governs salvage operations; Japan's
港湾法 (Port and Harbor Law) licenses port authorities separately
from shipping lines and terminal operators; the UK's Harbours Act
1964 and Port Marine Safety Code place statutory navigation-safety
duties on harbor authorities distinct from vessel owners; the US
Ports and Waterways Safety Act gives the Coast Guard Captain of the
Port authority over navigation safety independent of any carrier; and
pilotage is frequently licensed as its own profession (e.g. the UK's
Pilotage Act 1987).

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here robots (aids-to-navigation
inspection, berth/anchorage monitoring, buoy tending) operate under
an actor that proposes actions and an independent **Port Authority
Governor** that gates them. The governor never dispatches a vessel
movement or pilotage assignment itself; `:high`/`:safety-critical`
actions (any vessel movement outside verified navigation-safety scope,
a pilotage assignment without a completed fitness check, a salvage
coordination record without verified evidence) require human sign-off.

## Core Contract

```text
intake + identity + navigation-safety scope + booking
        |
        v
Port Authority Advisor -> Port Authority Governor -> clearance record, dispatch, reconciliation record, or human approval
        |
        v
robot actions (gated) + navigation record + reconciliation record + audit ledger
```

No automated advice can dispatch a vessel movement the governor
refuses, issue a pilotage/berthing clearance outside its verified
scope, or publish a reconciliation record without governor approval
and audit evidence.

## Capability layer

Resolves via [`kotoba-lang/industry`](https://github.com/kotoba-lang/industry)
(ISIC `5222`). Implemented by:

- [`kotoba-lang/robotics`](https://github.com/kotoba-lang/robotics) — missions, actions, safety-stops, telemetry proofs
- [`kotoba-lang/logistics`](https://github.com/kotoba-lang/logistics) — booking, transit, delivery/reconciliation contracts

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## License

AGPL-3.0-or-later.
