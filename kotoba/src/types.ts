/**
 * open-ports kotoba — record types.
 *
 * Per ADR-2605203000 Option B. Maritime port operations: ports (UN/LOCODE) +
 * vessels (IMO/MMSI) + vessel calls (ATA/berthed/unberthed/departed). Registry
 * on AT PDS records (replaces D1). ADR-2605172000 kotoba.
 *
 * Identity hierarchy:
 *   did:web:open-ports.etzhayyim.com                      — controller
 *   did:web:open-ports.etzhayyim.com:port:{locode}        — a port
 *   did:web:open-ports.etzhayyim.com:vessel:{imo}         — a vessel
 *   did:web:open-ports.etzhayyim.com:call:{callId}        — a vessel call
 */

export const OPORTS_DID_PREFIX = "did:web:open-ports.etzhayyim.com:" as const;

export const PORT_COLLECTION = "com.etzhayyim.apps.openPorts.port";
export const VESSEL_COLLECTION = "com.etzhayyim.apps.openPorts.vessel";
export const CALL_COLLECTION = "com.etzhayyim.apps.openPorts.call";

// ─── Port ───────────────────────────────────────────────────────────

export interface PortRecord {
  did: string;
  /** UN/LOCODE, 5 chars (2 country + 3 location), e.g. "JPTYO". */
  locode: string;
  name: string;
  /** ISO 3166-1 alpha-2 (= first 2 of locode). */
  country: string;
  berths?: number;
  createdAt: string;
}

export interface PortView extends PortRecord {
  portUri: string;
}

export interface DefinePortInput {
  locode: string;
  name: string;
  berths?: number;
}

export interface DefinePortOutput {
  status: "defined" | "alreadyExists" | "rejected";
  portUri?: string;
  did?: string;
  locode?: string;
  error?: string;
}

export interface GetPortInput {
  locode: string;
}

export interface GetPortOutput {
  port?: PortView;
  error?: string;
}

export interface ListPortsInput {
  country?: string;
  limit?: number;
  cursor?: string;
}

export interface ListPortsOutput {
  items: PortView[];
  cursor?: string;
  total: number;
}

// ─── Vessel ─────────────────────────────────────────────────────────

export interface VesselRecord {
  did: string;
  /** IMO number, 7 digits (canonical key). */
  imo: string;
  name: string;
  /** Maritime Mobile Service Identity, 9 digits. */
  mmsi?: string;
  /** ISO 3166-1 alpha-2 flag state. */
  flag?: string;
  vesselType?: string;
  createdAt: string;
}

export interface VesselView extends VesselRecord {
  vesselUri: string;
}

export interface RegisterVesselInput {
  imo: string;
  name: string;
  mmsi?: string;
  flag?: string;
  vesselType?: string;
}

export interface RegisterVesselOutput {
  status: "registered" | "alreadyExists" | "rejected";
  vesselUri?: string;
  did?: string;
  imo?: string;
  error?: string;
}

export interface GetVesselInput {
  imo: string;
}

export interface GetVesselOutput {
  vessel?: VesselView;
  error?: string;
}

export interface ListVesselsInput {
  flag?: string;
  vesselType?: string;
  limit?: number;
  cursor?: string;
}

export interface ListVesselsOutput {
  items: VesselView[];
  cursor?: string;
  total: number;
}

// ─── Vessel call (lifecycle) ────────────────────────────────────────

/** scheduled → arrived(ATA) → berthed → unberthed → departed (or cancelled). */
export type CallStatus =
  | "scheduled"
  | "arrived"
  | "berthed"
  | "unberthed"
  | "departed"
  | "cancelled";

/** Recordable events that advance the call. */
export type CallEvent = "ata" | "berthed" | "unberthed" | "departed" | "cancelled";

export interface CallTimes {
  ata?: string;
  berthed?: string;
  unberthed?: string;
  departed?: string;
}

export interface VesselCallRecord {
  did: string;
  callId: string;
  vesselImo: string;
  portLocode: string;
  berth?: string;
  eta?: string;
  etd?: string;
  status: CallStatus;
  times: CallTimes;
  createdAt: string;
}

export interface VesselCallView extends VesselCallRecord {
  callUri: string;
}

export interface ScheduleVesselCallInput {
  callId: string;
  vesselImo: string;
  portLocode: string;
  berth?: string;
  eta?: string;
  etd?: string;
}

export interface ScheduleVesselCallOutput {
  status: "scheduled" | "alreadyExists" | "rejected" | "vesselNotFound" | "portNotFound";
  callUri?: string;
  did?: string;
  callId?: string;
  error?: string;
}

export interface RecordCallEventInput {
  callId: string;
  event: CallEvent;
  at?: string;
}

export interface RecordCallEventOutput {
  status: "updated" | "notFound" | "rejected";
  callId?: string;
  newStatus?: CallStatus;
  error?: string;
}

export interface GetCallInput {
  callId: string;
}

export interface GetCallOutput {
  call?: VesselCallView;
  error?: string;
}

export interface ListCallsInput {
  portLocode?: string;
  vesselImo?: string;
  status?: CallStatus;
  limit?: number;
  cursor?: string;
}

export interface ListCallsOutput {
  items: VesselCallView[];
  cursor?: string;
  total: number;
}

export interface CoverageInput {
  maxScan?: number;
}

export interface CoverageOutput {
  portCount?: number;
  vesselCount?: number;
  callCount?: number;
  callsByStatus?: Record<string, number>;
  truncated?: boolean;
  error?: string;
}

// ─── Validation + helpers ───────────────────────────────────────────

const EVENT_TO_STATUS: Record<CallEvent, CallStatus> = {
  ata: "arrived",
  berthed: "berthed",
  unberthed: "unberthed",
  departed: "departed",
  cancelled: "cancelled",
};

export function isValidLocode(s: string): boolean {
  return /^[A-Z]{2}[A-Z0-9]{3}$/.test(s);
}

export function isValidImo(s: string): boolean {
  return /^\d{7}$/.test(s);
}

export function isValidMmsi(s: string): boolean {
  return /^\d{9}$/.test(s);
}

export function callEventStatus(ev: CallEvent): CallStatus {
  return EVENT_TO_STATUS[ev];
}

export function portDid(locode: string): string {
  return `${OPORTS_DID_PREFIX}port:${locode.toLowerCase()}`;
}
export function portRkey(locode: string): string {
  return `port-${locode.toLowerCase()}`;
}
export function vesselDid(imo: string): string {
  return `${OPORTS_DID_PREFIX}vessel:${imo}`;
}
export function vesselRkey(imo: string): string {
  return `vessel-${imo}`;
}
export function callDid(callId: string): string {
  return `${OPORTS_DID_PREFIX}call:${callId.toLowerCase()}`;
}
export function callRkey(callId: string): string {
  return `call-${callId.toLowerCase()}`;
}
