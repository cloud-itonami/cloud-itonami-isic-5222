/**
 * open-ports kotoba — port + vessel + vessel-call registries + coverage.
 * AT PDS records (no RW). Calls reference an existing vessel + port.
 */

import type { Etzhayyim } from "@etzhayyim/sdk";
import {
  CALL_COLLECTION,
  PORT_COLLECTION,
  VESSEL_COLLECTION,
  callDid,
  callEventStatus,
  callRkey,
  isValidImo,
  isValidLocode,
  isValidMmsi,
  portDid,
  portRkey,
  vesselDid,
  vesselRkey,
  type CallEvent,
  type CallStatus,
  type CallTimes,
  type CoverageInput,
  type CoverageOutput,
  type DefinePortInput,
  type DefinePortOutput,
  type GetCallInput,
  type GetCallOutput,
  type GetPortInput,
  type GetPortOutput,
  type GetVesselInput,
  type GetVesselOutput,
  type ListCallsInput,
  type ListCallsOutput,
  type ListPortsInput,
  type ListPortsOutput,
  type ListVesselsInput,
  type ListVesselsOutput,
  type PortRecord,
  type PortView,
  type RecordCallEventInput,
  type RecordCallEventOutput,
  type RegisterVesselInput,
  type RegisterVesselOutput,
  type ScheduleVesselCallInput,
  type ScheduleVesselCallOutput,
  type VesselCallRecord,
  type VesselCallView,
  type VesselRecord,
  type VesselView,
} from "./types.js";

const PAGE_LIMIT = 100;
const DEFAULT_MAX_SCAN = 10_000;
const CALL_EVENTS: ReadonlySet<string> = new Set(["ata", "berthed", "unberthed", "departed", "cancelled"]);

async function exists(e: Etzhayyim, collection: string, rkey: string): Promise<boolean> {
  const resp = await e.read({ collection, rkey }).catch(() => ({ records: [] }));
  return Boolean(resp.records[0]?.value);
}

// ─── Port ───────────────────────────────────────────────────────────

export async function definePort(e: Etzhayyim, input: DefinePortInput): Promise<DefinePortOutput> {
  if (!input.locode || !input.name) return { status: "rejected", error: "missingRequiredFields" };
  const locode = input.locode.toUpperCase();
  if (!isValidLocode(locode)) return { status: "rejected", error: "invalidLocode" };
  const rkey = portRkey(locode);
  const existing = await e.read<PortRecord>({ collection: PORT_COLLECTION, rkey }).catch(() => ({ records: [] }));
  if (existing.records[0]?.value) {
    return { status: "alreadyExists", portUri: existing.records[0].uri, did: existing.records[0].value.did, locode };
  }
  const did = portDid(locode);
  const record: PortRecord = {
    did,
    locode,
    name: input.name,
    country: locode.slice(0, 2),
    berths: input.berths,
    createdAt: new Date().toISOString(),
  };
  const receipt = await e.write({ collection: PORT_COLLECTION, record: record as unknown as Record<string, unknown>, rkey });
  return { status: "defined", portUri: receipt.uri, did, locode };
}

export async function getPort(e: Etzhayyim, input: GetPortInput): Promise<GetPortOutput> {
  if (!input.locode || !isValidLocode(input.locode.toUpperCase())) return { error: "invalidLocode" };
  const resp = await e.read<PortRecord>({ collection: PORT_COLLECTION, rkey: portRkey(input.locode.toUpperCase()) }).catch(() => ({ records: [] }));
  const r = resp.records[0];
  if (!r) return { error: "notFound" };
  return { port: { ...r.value, portUri: r.uri } };
}

export async function listPorts(e: Etzhayyim, input: ListPortsInput = {}): Promise<ListPortsOutput> {
  const limit = Math.min(input.limit ?? 50, 200);
  const resp = await e.read<PortRecord>({ collection: PORT_COLLECTION, cursor: input.cursor, limit });
  const items: PortView[] = resp.records
    .filter((r) => (input.country ? r.value.country === input.country.toUpperCase() : true))
    .map((r) => ({ ...r.value, portUri: r.uri }));
  return { items, cursor: resp.cursor, total: items.length };
}

// ─── Vessel ─────────────────────────────────────────────────────────

export async function registerVessel(e: Etzhayyim, input: RegisterVesselInput): Promise<RegisterVesselOutput> {
  if (!input.imo || !input.name) return { status: "rejected", error: "missingRequiredFields" };
  if (!isValidImo(input.imo)) return { status: "rejected", error: "invalidImo" };
  if (input.mmsi && !isValidMmsi(input.mmsi)) return { status: "rejected", error: "invalidMmsi" };
  const rkey = vesselRkey(input.imo);
  const existing = await e.read<VesselRecord>({ collection: VESSEL_COLLECTION, rkey }).catch(() => ({ records: [] }));
  if (existing.records[0]?.value) {
    return { status: "alreadyExists", vesselUri: existing.records[0].uri, did: existing.records[0].value.did, imo: input.imo };
  }
  const did = vesselDid(input.imo);
  const record: VesselRecord = {
    did,
    imo: input.imo,
    name: input.name,
    mmsi: input.mmsi,
    flag: input.flag ? input.flag.toUpperCase() : undefined,
    vesselType: input.vesselType,
    createdAt: new Date().toISOString(),
  };
  const receipt = await e.write({ collection: VESSEL_COLLECTION, record: record as unknown as Record<string, unknown>, rkey });
  return { status: "registered", vesselUri: receipt.uri, did, imo: input.imo };
}

export async function getVessel(e: Etzhayyim, input: GetVesselInput): Promise<GetVesselOutput> {
  if (!input.imo || !isValidImo(input.imo)) return { error: "invalidImo" };
  const resp = await e.read<VesselRecord>({ collection: VESSEL_COLLECTION, rkey: vesselRkey(input.imo) }).catch(() => ({ records: [] }));
  const r = resp.records[0];
  if (!r) return { error: "notFound" };
  return { vessel: { ...r.value, vesselUri: r.uri } };
}

export async function listVessels(e: Etzhayyim, input: ListVesselsInput = {}): Promise<ListVesselsOutput> {
  const limit = Math.min(input.limit ?? 50, 200);
  const resp = await e.read<VesselRecord>({ collection: VESSEL_COLLECTION, cursor: input.cursor, limit });
  const items: VesselView[] = resp.records
    .filter((r) => {
      const v = r.value;
      if (input.flag && v.flag !== input.flag.toUpperCase()) return false;
      if (input.vesselType && v.vesselType !== input.vesselType) return false;
      return true;
    })
    .map((r) => ({ ...r.value, vesselUri: r.uri }));
  return { items, cursor: resp.cursor, total: items.length };
}

// ─── Vessel call ────────────────────────────────────────────────────

export async function scheduleVesselCall(e: Etzhayyim, input: ScheduleVesselCallInput): Promise<ScheduleVesselCallOutput> {
  if (!input.callId || !input.vesselImo || !input.portLocode) {
    return { status: "rejected", error: "missingRequiredFields" };
  }
  if (!(await exists(e, VESSEL_COLLECTION, vesselRkey(input.vesselImo)))) {
    return { status: "vesselNotFound", error: "vesselNotFound" };
  }
  if (!(await exists(e, PORT_COLLECTION, portRkey(input.portLocode.toUpperCase())))) {
    return { status: "portNotFound", error: "portNotFound" };
  }
  const rkey = callRkey(input.callId);
  const existing = await e.read<VesselCallRecord>({ collection: CALL_COLLECTION, rkey }).catch(() => ({ records: [] }));
  if (existing.records[0]?.value) {
    return { status: "alreadyExists", callUri: existing.records[0].uri, did: existing.records[0].value.did, callId: input.callId };
  }
  const did = callDid(input.callId);
  const record: VesselCallRecord = {
    did,
    callId: input.callId,
    vesselImo: input.vesselImo,
    portLocode: input.portLocode.toUpperCase(),
    berth: input.berth,
    eta: input.eta,
    etd: input.etd,
    status: "scheduled",
    times: {},
    createdAt: new Date().toISOString(),
  };
  const receipt = await e.write({ collection: CALL_COLLECTION, record: record as unknown as Record<string, unknown>, rkey });
  return { status: "scheduled", callUri: receipt.uri, did, callId: input.callId };
}

export async function recordCallEvent(e: Etzhayyim, input: RecordCallEventInput): Promise<RecordCallEventOutput> {
  if (!input.callId || !CALL_EVENTS.has(input.event)) return { status: "rejected", error: "invalidEvent" };
  const rkey = callRkey(input.callId);
  const resp = await e.read<VesselCallRecord>({ collection: CALL_COLLECTION, rkey }).catch(() => ({ records: [] }));
  const call = resp.records[0]?.value;
  if (!call) return { status: "notFound", error: "callNotFound" };
  if (call.status === "departed" || call.status === "cancelled") {
    return { status: "rejected", error: `callTerminal:${call.status}` };
  }
  const at = input.at ?? new Date().toISOString();
  const ev = input.event as CallEvent;
  const times: CallTimes = { ...call.times };
  if (ev !== "cancelled") times[ev] = at;
  const newStatus = callEventStatus(ev);
  await e.write({
    collection: CALL_COLLECTION,
    record: { ...call, status: newStatus, times } as unknown as Record<string, unknown>,
    rkey,
  });
  return { status: "updated", callId: input.callId, newStatus };
}

export async function getCall(e: Etzhayyim, input: GetCallInput): Promise<GetCallOutput> {
  if (!input.callId) return { error: "invalidCallId" };
  const resp = await e.read<VesselCallRecord>({ collection: CALL_COLLECTION, rkey: callRkey(input.callId) }).catch(() => ({ records: [] }));
  const r = resp.records[0];
  if (!r) return { error: "notFound" };
  return { call: { ...r.value, callUri: r.uri } };
}

export async function listVesselCalls(e: Etzhayyim, input: ListCallsInput = {}): Promise<ListCallsOutput> {
  const limit = Math.min(input.limit ?? 50, 200);
  const resp = await e.read<VesselCallRecord>({ collection: CALL_COLLECTION, cursor: input.cursor, limit });
  const items: VesselCallView[] = resp.records
    .filter((r) => {
      const v = r.value;
      if (input.portLocode && v.portLocode !== input.portLocode.toUpperCase()) return false;
      if (input.vesselImo && v.vesselImo !== input.vesselImo) return false;
      if (input.status && v.status !== input.status) return false;
      return true;
    })
    .map((r) => ({ ...r.value, callUri: r.uri }));
  return { items, cursor: resp.cursor, total: items.length };
}

// ─── Coverage ───────────────────────────────────────────────────────

async function countAll<T>(e: Etzhayyim, collection: string, maxScan: number, onRow: (v: T) => void): Promise<number> {
  let cursor: string | undefined;
  let scanned = 0;
  while (scanned < maxScan) {
    const page = await e.read<T>({ collection, cursor, limit: PAGE_LIMIT });
    for (const r of page.records) {
      if (scanned >= maxScan) break;
      onRow(r.value);
      scanned += 1;
    }
    if (scanned >= maxScan || !page.cursor || page.records.length < PAGE_LIMIT) break;
    cursor = page.cursor;
  }
  return scanned;
}

export async function coverage(e: Etzhayyim, input: CoverageInput = {}): Promise<CoverageOutput> {
  const maxScan = Math.min(input.maxScan ?? DEFAULT_MAX_SCAN, DEFAULT_MAX_SCAN);
  const portCount = await countAll<PortRecord>(e, PORT_COLLECTION, maxScan, () => {});
  const vesselCount = await countAll<VesselRecord>(e, VESSEL_COLLECTION, maxScan, () => {});
  const callsByStatus: Record<string, number> = {};
  const callCount = await countAll<VesselCallRecord>(e, CALL_COLLECTION, maxScan, (v) => {
    callsByStatus[v.status as CallStatus] = (callsByStatus[v.status as CallStatus] ?? 0) + 1;
  });
  return {
    portCount,
    vesselCount,
    callCount,
    callsByStatus,
    truncated: portCount >= maxScan || vesselCount >= maxScan || callCount >= maxScan,
  };
}
