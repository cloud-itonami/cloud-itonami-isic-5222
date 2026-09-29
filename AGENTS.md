# open-ports.etzhayyim.com — Maritime Port Operations & Network Design (OSS)

**Status**: MVP scaffold (2026-04-20). Reference implementation for
DID-addressed maritime port operations — port / berth / vessel call /
container manifest / incident. Apache-2.0.

## Scope (MVP)

| NSID | Type | Description |
|---|---|---|
| `com.etzhayyim.apps.openPorts.definePort` | procedure | port (UN/LOCODE + berths) |
| `com.etzhayyim.apps.openPorts.listPorts` | query | port directory |
| `com.etzhayyim.apps.openPorts.registerVessel` | procedure | vessel registration (IMO + MMSI + flag) |
| `com.etzhayyim.apps.openPorts.scheduleVesselCall` | procedure | vessel call (ETA / ETD + berth assignment) |
| `com.etzhayyim.apps.openPorts.recordCallEvent` | procedure | ATA / berthed / unberthed / departed events |
| `com.etzhayyim.apps.openPorts.listVesselCalls` | query | calls by port / vessel / status |
| `com.etzhayyim.apps.openPorts.recordContainerManifest` | procedure | container TEU manifest (ISO 6346 ID + dangerous goods) |
| `com.etzhayyim.apps.openPorts.listContainers` | query | containers by call / status |
| `com.etzhayyim.apps.openPorts.reportIncident` | procedure | port-state-control / spill / collision incident |
| `com.etzhayyim.apps.openPorts.listIncidents` | query | incidents by port / vessel / since |

## Architecture

- **Runtime**: Single CF Worker (`src/app.ts`)
- **Storage**: D1. Tables: `ports`, `vessels`, `vessel_calls`, `call_events`, `containers`, `incidents`
- **Identity**: port / vessel / call / container / incident = path-based DIDs
- **Call lifecycle**: `scheduled → arrived (ATA) → berthed → unberthed →
  departed → completed | cancelled`
- **Incident severity** by DMN (`openPorts.incidentSeverity`):
  pollution-volume + injuries + dangerous-goods involvement →
  `{severity, requireCoastGuardReport}`
- **Audit**: severity ≥ "major" → `app.bsky.feed.post` (port-state-control visible)

## Not in MVP

- AIS realtime track ingest, vessel routing
- Pilot booking / VTS integration
- Customs / single-window (UN/CEFACT eDC)
- Yard planning, RTG/STS allocation

## Local Dev / Deploy

```bash
cd 60-apps/etzhayyim-project-open-ports/worker
wrangler d1 create etzhayyim-open-ports
e7m actor deploy .
```
