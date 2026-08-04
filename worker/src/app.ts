// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 etzhayyim Japan株式会社 / etzhayyim. All rights reserved.
// Licensed under the Apache License, Version 2.0 — see LICENSE at repo root.

// etzhayyim-project-open-ports — maritime port operations + network design
//
// 10 XRPC under com.etzhayyim.apps.openPorts.*:
//   definePort                  (proc)  port (UN/LOCODE + berths)
//   listPorts                   (query) port directory
//   registerVessel              (proc)  vessel (IMO + MMSI + flag)
//   scheduleVesselCall          (proc)  vessel call (ETA + ETD + berth)
//   recordCallEvent             (proc)  ATA / berthed / unberthed / departed
//   listVesselCalls             (query) calls by port / vessel / status
//   recordContainerManifest     (proc)  container TEU + dangerous-goods
//   listContainers              (query) containers by call / status
//   reportIncident              (proc)  spill / collision / DG with DMN
//   listIncidents               (query) incidents by port / vessel / since

import AV1 from "../../dodaf/AV-1.json";
import OV1 from "../../dodaf/OV-1.json";
import OV5b from "../../dodaf/OV-5b.json";
import OV6a from "../../dodaf/OV-6a.json";
import CV2 from "../../dodaf/CV-2.json";
import SV1 from "../../dodaf/SV-1.json";
import scheduleCallForm from "../../forms/scheduleVesselCall.form.json";
import reportIncidentForm from "../../forms/reportIncident.form.json";
import { bootstrapDodaf } from "./dodaf-bootstrap";

const DODAF_VIEWS: Record<string, any> = {
  "open-ports.AV-1": AV1, "open-ports.OV-1": OV1, "open-ports.OV-5b": OV5b,
  "open-ports.OV-6a": OV6a, "open-ports.CV-2": CV2, "open-ports.SV-1": SV1,
};
const FORMS: Record<string, any> = {
  "openPorts.scheduleVesselCall.v1": scheduleCallForm,
  "openPorts.reportIncident.v1": reportIncidentForm,
};

export interface Env {
  PORTS_DB: D1Database;
  PDS?: Fetcher;
  AUTH_SERVICE?: Fetcher;
  APP_HANDLE: string;
  PRIMARY_DID: string;
}

const SCHEMA = [
  `CREATE TABLE IF NOT EXISTS ports (
    port_did TEXT PRIMARY KEY,
    un_locode TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    country TEXT NOT NULL,
    lat REAL,
    lon REAL,
    berths_json TEXT NOT NULL,
    defined_at TEXT NOT NULL
  )`,
  `CREATE TABLE IF NOT EXISTS vessels (
    vessel_did TEXT PRIMARY KEY,
    imo_number TEXT NOT NULL UNIQUE,
    mmsi TEXT,
    name TEXT NOT NULL,
    vessel_type TEXT NOT NULL,
    flag TEXT NOT NULL,
    operator_did TEXT NOT NULL,
    registered_at TEXT NOT NULL
  )`,
  `CREATE INDEX IF NOT EXISTS idx_vessels_op ON vessels(operator_did)`,
  `CREATE TABLE IF NOT EXISTS vessel_calls (
    call_did TEXT PRIMARY KEY,
    port_did TEXT NOT NULL,
    vessel_did TEXT NOT NULL,
    operator_did TEXT NOT NULL,
    voyage_number TEXT NOT NULL,
    berth_code TEXT NOT NULL,
    eta TEXT NOT NULL,
    etd TEXT NOT NULL,
    purpose TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'scheduled',
    scheduled_at TEXT NOT NULL,
    FOREIGN KEY (port_did) REFERENCES ports(port_did),
    FOREIGN KEY (vessel_did) REFERENCES vessels(vessel_did)
  )`,
  `CREATE INDEX IF NOT EXISTS idx_calls_port_eta ON vessel_calls(port_did, eta DESC)`,
  `CREATE INDEX IF NOT EXISTS idx_calls_vessel_eta ON vessel_calls(vessel_did, eta DESC)`,
  `CREATE TABLE IF NOT EXISTS call_events (
    event_id TEXT PRIMARY KEY,
    call_did TEXT NOT NULL,
    event TEXT NOT NULL CHECK (event IN ('arrived','berthed','unberthed','departed','cancelled')),
    event_at TEXT NOT NULL,
    note TEXT,
    FOREIGN KEY (call_did) REFERENCES vessel_calls(call_did)
  )`,
  `CREATE INDEX IF NOT EXISTS idx_call_events_call ON call_events(call_did, event_at)`,
  `CREATE TABLE IF NOT EXISTS containers (
    container_did TEXT PRIMARY KEY,
    container_id TEXT NOT NULL,
    call_did TEXT NOT NULL,
    iso_size_type TEXT NOT NULL,
    teu REAL NOT NULL,
    movement TEXT NOT NULL CHECK (movement IN ('load','discharge','transship')),
    weight_kg INTEGER NOT NULL,
    dangerous_goods_un TEXT,
    dangerous_goods_class TEXT,
    consignee TEXT,
    recorded_at TEXT NOT NULL,
    UNIQUE(container_id, call_did),
    FOREIGN KEY (call_did) REFERENCES vessel_calls(call_did)
  )`,
  `CREATE INDEX IF NOT EXISTS idx_containers_call ON containers(call_did)`,
  `CREATE TABLE IF NOT EXISTS incidents (
    incident_did TEXT PRIMARY KEY,
    port_did TEXT NOT NULL,
    vessel_did TEXT,
    occurred_at TEXT NOT NULL,
    category TEXT NOT NULL,
    pollution_tonnes REAL NOT NULL,
    injuries INTEGER NOT NULL,
    dangerous_goods_involved INTEGER NOT NULL,
    collision INTEGER NOT NULL,
    severity TEXT NOT NULL,
    require_coast_guard_report INTEGER NOT NULL,
    description TEXT,
    reported_at TEXT NOT NULL
  )`,
  `CREATE INDEX IF NOT EXISTS idx_incidents_port_time ON incidents(port_did, occurred_at DESC)`,
];

let schemaReady = false;
async function ensureSchema(db: D1Database) {
  if (schemaReady) return;
  for (const s of SCHEMA) await db.exec(s.replace(/\s+/g, " "));
  schemaReady = true;
}

function nanoid(len = 12): string {
  const a = "abcdefghijklmnopqrstuvwxyz0123456789";
  const b = crypto.getRandomValues(new Uint8Array(len));
  let o = ""; for (let i = 0; i < len; i++) o += a[b[i] % a.length]; return o;
}
const now = () => new Date().toISOString();

type XrpcError = "InvalidRequest" | "PortNotFound" | "VesselNotFound" | "CallNotFound"
              | "Conflict" | "Unauthorized" | "InternalError";
const json = (b: unknown, s = 200) => new Response(JSON.stringify(b), { status: s, headers: { "content-type": "application/json" } });
const err = (e: XrpcError, m: string, s = 400) => json({ error: e, message: m }, s);

function classifyPortIncident(input: {
  pollutionTonnes: number; injuries: number; dangerousGoodsInvolved: boolean; collision: boolean;
}): { severity: "minor" | "moderate" | "major" | "critical"; requireCoastGuardReport: boolean } {
  if (input.pollutionTonnes >= 100) return { severity: "critical", requireCoastGuardReport: true };
  if (input.injuries >= 1)           return { severity: "major",    requireCoastGuardReport: true };
  if (input.dangerousGoodsInvolved)  return { severity: "major",    requireCoastGuardReport: true };
  if (input.collision)               return { severity: "major",    requireCoastGuardReport: true };
  if (input.pollutionTonnes >= 1)    return { severity: "moderate", requireCoastGuardReport: false };
  return { severity: "minor", requireCoastGuardReport: false };
}

const CALL_TRANSITIONS: Record<string, string[]> = {
  "scheduled": ["arrived", "cancelled"],
  "arrived":   ["berthed", "departed", "cancelled"],
  "berthed":   ["unberthed"],
  "unberthed": ["departed"],
  "departed":  [],
  "cancelled": [],
};

async function definePort(env: Env, input: any): Promise<Response> {
  const { unLocode, name, country, lat, lon, berths } = input ?? {};
  if (typeof unLocode !== "string" || !/^[A-Z]{2}[A-Z0-9]{3}$/.test(unLocode))
    return err("InvalidRequest", "unLocode must match UN/LOCODE format");
  if (typeof name !== "string" || !name.length) return err("InvalidRequest", "name required");
  if (typeof country !== "string" || !/^[A-Z]{2}$/.test(country))
    return err("InvalidRequest", "country must be ISO-3166 alpha-2");
  if (!Array.isArray(berths) || berths.length < 1)
    return err("InvalidRequest", "berths[] requires ≥ 1 entry");
  const id = nanoid(10);
  const portDid = `did:web:${env.APP_HANDLE}:port:${id}`;
  const definedAt = now();
  try {
    await env.PORTS_DB.prepare(
      `INSERT INTO ports (port_did, un_locode, name, country, lat, lon, berths_json, defined_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?)`
    ).bind(portDid, unLocode, name, country,
           Number.isFinite(lat) ? lat : null, Number.isFinite(lon) ? lon : null,
           JSON.stringify(berths), definedAt).run();
  } catch (e: any) {
    if (String(e?.message ?? e).includes("UNIQUE"))
      return err("InvalidRequest", "unLocode already registered", 409);
    throw e;
  }
  return json({ portDid, unLocode, name, country, lat: lat ?? null, lon: lon ?? null, berths, definedAt });
}

async function listPorts(env: Env, params: URLSearchParams): Promise<Response> {
  const country = params.get("country");
  const limit = Math.min(500, Math.max(1, Number(params.get("limit") ?? 100)));
  const offset = Math.max(0, Number(params.get("offset") ?? 0));
  const where = country ? `WHERE country = ?` : "";
  const binds = country ? [country] : [];
  const total = await env.PORTS_DB.prepare(`SELECT COUNT(*) AS c FROM ports ${where}`)
    .bind(...binds).first<{ c: number }>();
  const rows = await env.PORTS_DB.prepare(
    `SELECT * FROM ports ${where} ORDER BY un_locode ASC LIMIT ? OFFSET ?`
  ).bind(...binds, limit, offset).all<any>();
  return json({
    ports: (rows.results ?? []).map((r) => ({
      portDid: r.port_did, unLocode: r.un_locode, name: r.name, country: r.country,
      lat: r.lat ?? undefined, lon: r.lon ?? undefined,
      berths: JSON.parse(r.berths_json), definedAt: r.defined_at,
    })),
    total: Number(total?.c ?? 0), offset, limit,
  });
}

async function registerVessel(env: Env, input: any): Promise<Response> {
  const { imoNumber, mmsi, name, vesselType, flag, operatorDid } = input ?? {};
  if (typeof imoNumber !== "string" || !/^IMO\d{7}$/.test(imoNumber))
    return err("InvalidRequest", "imoNumber must match ^IMO\\d{7}$");
  if (mmsi != null && (typeof mmsi !== "string" || !/^\d{9}$/.test(mmsi)))
    return err("InvalidRequest", "mmsi must be 9 digits");
  if (typeof name !== "string" || !name.length) return err("InvalidRequest", "name required");
  if (typeof vesselType !== "string" || !vesselType.length)
    return err("InvalidRequest", "vesselType required");
  if (typeof flag !== "string" || !/^[A-Z]{2}$/.test(flag))
    return err("InvalidRequest", "flag must be ISO-3166 alpha-2");
  if (typeof operatorDid !== "string" || !operatorDid.startsWith("did:"))
    return err("InvalidRequest", "operatorDid must be a DID");
  const id = nanoid(10);
  const vesselDid = `did:web:${env.APP_HANDLE}:vessel:${id}`;
  const registeredAt = now();
  try {
    await env.PORTS_DB.prepare(
      `INSERT INTO vessels (vessel_did, imo_number, mmsi, name, vessel_type, flag, operator_did, registered_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?)`
    ).bind(vesselDid, imoNumber, mmsi ?? null, name, vesselType, flag, operatorDid, registeredAt).run();
  } catch (e: any) {
    if (String(e?.message ?? e).includes("UNIQUE"))
      return err("InvalidRequest", "imoNumber already registered", 409);
    throw e;
  }
  return json({ vesselDid, imoNumber, mmsi: mmsi ?? null, name, vesselType, flag, operatorDid, registeredAt });
}

async function scheduleVesselCall(env: Env, input: any): Promise<Response> {
  const { portDid, vesselDid, operatorDid, voyageNumber, berthCode, eta, etd, purpose } = input ?? {};
  if (typeof portDid !== "string") return err("InvalidRequest", "portDid required");
  if (typeof vesselDid !== "string") return err("InvalidRequest", "vesselDid required");
  if (typeof operatorDid !== "string" || !operatorDid.startsWith("did:"))
    return err("InvalidRequest", "operatorDid must be a DID");
  if (typeof voyageNumber !== "string") return err("InvalidRequest", "voyageNumber required");
  if (typeof berthCode !== "string") return err("InvalidRequest", "berthCode required");
  if (typeof eta !== "string" || typeof etd !== "string")
    return err("InvalidRequest", "eta + etd required");
  if (Date.parse(eta) >= Date.parse(etd))
    return err("InvalidRequest", "eta must precede etd");
  if (!["load", "discharge", "both", "bunker", "repair"].includes(purpose))
    return err("InvalidRequest", "purpose invalid");
  const [p, v] = await Promise.all([
    env.PORTS_DB.prepare(`SELECT berths_json FROM ports WHERE port_did = ?`).bind(portDid).first<any>(),
    env.PORTS_DB.prepare(`SELECT vessel_did FROM vessels WHERE vessel_did = ?`).bind(vesselDid).first<any>(),
  ]);
  if (!p) return err("PortNotFound", "port not found", 404);
  if (!v) return err("VesselNotFound", "vessel not found", 404);
  const berths: any[] = JSON.parse(p.berths_json);
  if (!berths.some((b: any) => b.code === berthCode))
    return err("InvalidRequest", `berth ${berthCode} not in port`);
  const id = nanoid(12);
  const callDid = `did:web:${env.APP_HANDLE}:call:${id}`;
  const scheduledAt = now();
  await env.PORTS_DB.prepare(
    `INSERT INTO vessel_calls (call_did, port_did, vessel_did, operator_did, voyage_number, berth_code, eta, etd, purpose, status, scheduled_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'scheduled', ?)`
  ).bind(callDid, portDid, vesselDid, operatorDid, voyageNumber, berthCode, eta, etd, purpose, scheduledAt).run();
  return json({ callDid, portDid, vesselDid, voyageNumber, berthCode, eta, etd, purpose, status: "scheduled", scheduledAt });
}

async function recordCallEvent(env: Env, input: any): Promise<Response> {
  const { callDid, event, eventAt, note } = input ?? {};
  if (typeof callDid !== "string") return err("InvalidRequest", "callDid required");
  if (!["arrived", "berthed", "unberthed", "departed", "cancelled"].includes(event))
    return err("InvalidRequest", "event invalid");
  if (typeof eventAt !== "string") return err("InvalidRequest", "eventAt required");
  const c = await env.PORTS_DB.prepare(`SELECT * FROM vessel_calls WHERE call_did = ?`).bind(callDid).first<any>();
  if (!c) return err("CallNotFound", "no such call", 404);
  const allowed = CALL_TRANSITIONS[c.status] ?? [];
  if (!allowed.includes(event))
    return err("Conflict", `event ${event} not allowed from status ${c.status}`, 409);
  const id = `e_${nanoid(14)}`;
  await env.PORTS_DB.batch([
    env.PORTS_DB.prepare(
      `INSERT INTO call_events (event_id, call_did, event, event_at, note) VALUES (?, ?, ?, ?, ?)`
    ).bind(id, callDid, event, eventAt, note ?? null),
    env.PORTS_DB.prepare(`UPDATE vessel_calls SET status = ? WHERE call_did = ?`).bind(event, callDid),
  ]);
  return json({ eventId: id, callDid, event, status: event, eventAt });
}

async function listVesselCalls(env: Env, params: URLSearchParams): Promise<Response> {
  const portDid = params.get("portDid");
  const vesselDid = params.get("vesselDid");
  const status = params.get("status");
  const since = params.get("since");
  const limit = Math.min(500, Math.max(1, Number(params.get("limit") ?? 100)));
  const offset = Math.max(0, Number(params.get("offset") ?? 0));
  const clauses: string[] = []; const binds: any[] = [];
  if (portDid)   { clauses.push(`port_did = ?`);   binds.push(portDid); }
  if (vesselDid) { clauses.push(`vessel_did = ?`); binds.push(vesselDid); }
  if (status)    { clauses.push(`status = ?`);     binds.push(status); }
  if (since)     { clauses.push(`eta >= ?`);       binds.push(since); }
  const where = clauses.length ? `WHERE ${clauses.join(" AND ")}` : "";
  const total = await env.PORTS_DB.prepare(`SELECT COUNT(*) AS c FROM vessel_calls ${where}`)
    .bind(...binds).first<{ c: number }>();
  const rows = await env.PORTS_DB.prepare(
    `SELECT * FROM vessel_calls ${where} ORDER BY eta DESC LIMIT ? OFFSET ?`
  ).bind(...binds, limit, offset).all<any>();
  return json({
    calls: (rows.results ?? []).map((r) => ({
      callDid: r.call_did, portDid: r.port_did, vesselDid: r.vessel_did,
      operatorDid: r.operator_did, voyageNumber: r.voyage_number, berthCode: r.berth_code,
      eta: r.eta, etd: r.etd, purpose: r.purpose, status: r.status, scheduledAt: r.scheduled_at,
    })),
    total: Number(total?.c ?? 0), offset, limit,
  });
}

async function recordContainerManifest(env: Env, input: any): Promise<Response> {
  const { callDid, containerId, isoSizeType, teu, movement, weightKg, dangerousGoods, consignee } = input ?? {};
  if (typeof callDid !== "string") return err("InvalidRequest", "callDid required");
  if (typeof containerId !== "string" || !/^[A-Z]{4}\d{7}$/.test(containerId))
    return err("InvalidRequest", "containerId must be ISO 6346 (4 letters + 7 digits)");
  if (typeof isoSizeType !== "string" || !isoSizeType.length)
    return err("InvalidRequest", "isoSizeType required");
  if (!Number.isFinite(teu) || teu <= 0) return err("InvalidRequest", "teu > 0");
  if (!["load", "discharge", "transship"].includes(movement))
    return err("InvalidRequest", "movement invalid");
  if (!Number.isInteger(weightKg) || weightKg < 0)
    return err("InvalidRequest", "weightKg ≥ 0");
  let dgUn: string | null = null, dgClass: string | null = null;
  if (dangerousGoods) {
    if (typeof dangerousGoods !== "object")
      return err("InvalidRequest", "dangerousGoods must be {unNumber,class}");
    if (typeof dangerousGoods.unNumber !== "string" || !/^UN\d{4}$/.test(dangerousGoods.unNumber))
      return err("InvalidRequest", "dangerousGoods.unNumber must match ^UN\\d{4}$");
    dgUn = dangerousGoods.unNumber;
    dgClass = String(dangerousGoods.class ?? "");
  }
  const c = await env.PORTS_DB.prepare(`SELECT call_did FROM vessel_calls WHERE call_did = ?`).bind(callDid).first<any>();
  if (!c) return err("CallNotFound", "no such call", 404);
  const id = nanoid(12);
  const containerDid = `did:web:${env.APP_HANDLE}:container:${id}`;
  const recordedAt = now();
  try {
    await env.PORTS_DB.prepare(
      `INSERT INTO containers (container_did, container_id, call_did, iso_size_type, teu, movement, weight_kg, dangerous_goods_un, dangerous_goods_class, consignee, recorded_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
    ).bind(containerDid, containerId, callDid, isoSizeType, teu, movement, weightKg,
           dgUn, dgClass, consignee ?? null, recordedAt).run();
  } catch (e: any) {
    if (String(e?.message ?? e).includes("UNIQUE"))
      return err("InvalidRequest", "containerId already on this call", 409);
    throw e;
  }
  return json({ containerDid, containerId, callDid, teu, movement, dangerousGoods: dgUn ? { unNumber: dgUn, class: dgClass } : undefined, recordedAt });
}

async function listContainers(env: Env, params: URLSearchParams): Promise<Response> {
  const callDid = params.get("callDid");
  if (!callDid) return err("InvalidRequest", "callDid required");
  const movement = params.get("movement");
  const dangerousOnly = params.get("dangerousOnly") === "true";
  const limit = Math.min(2000, Math.max(1, Number(params.get("limit") ?? 500)));
  const offset = Math.max(0, Number(params.get("offset") ?? 0));
  const clauses = [`call_did = ?`]; const binds: any[] = [callDid];
  if (movement) { clauses.push(`movement = ?`); binds.push(movement); }
  if (dangerousOnly) clauses.push(`dangerous_goods_un IS NOT NULL`);
  const where = `WHERE ${clauses.join(" AND ")}`;
  const total = await env.PORTS_DB.prepare(`SELECT COUNT(*) AS c FROM containers ${where}`)
    .bind(...binds).first<{ c: number }>();
  const rows = await env.PORTS_DB.prepare(
    `SELECT * FROM containers ${where} ORDER BY recorded_at DESC LIMIT ? OFFSET ?`
  ).bind(...binds, limit, offset).all<any>();
  return json({
    containers: (rows.results ?? []).map((r) => ({
      containerDid: r.container_did, containerId: r.container_id, callDid: r.call_did,
      isoSizeType: r.iso_size_type, teu: r.teu, movement: r.movement, weightKg: r.weight_kg,
      dangerousGoods: r.dangerous_goods_un
        ? { unNumber: r.dangerous_goods_un, class: r.dangerous_goods_class ?? undefined }
        : undefined,
      consignee: r.consignee ?? undefined, recordedAt: r.recorded_at,
    })),
    total: Number(total?.c ?? 0), offset, limit,
  });
}

async function reportIncident(env: Env, input: any): Promise<Response> {
  const { portDid, vesselDid, occurredAt, category, pollutionTonnes, injuries,
          dangerousGoodsInvolved, collision, description } = input ?? {};
  if (typeof portDid !== "string") return err("InvalidRequest", "portDid required");
  if (typeof occurredAt !== "string") return err("InvalidRequest", "occurredAt required");
  if (typeof category !== "string") return err("InvalidRequest", "category required");
  if (!Number.isFinite(pollutionTonnes) || pollutionTonnes < 0)
    return err("InvalidRequest", "pollutionTonnes ≥ 0");
  if (!Number.isFinite(injuries) || injuries < 0)
    return err("InvalidRequest", "injuries ≥ 0");
  const p = await env.PORTS_DB.prepare(`SELECT port_did FROM ports WHERE port_did = ?`).bind(portDid).first<any>();
  if (!p) return err("PortNotFound", "port not found", 404);
  const { severity, requireCoastGuardReport } = classifyPortIncident({
    pollutionTonnes: Number(pollutionTonnes), injuries: Number(injuries),
    dangerousGoodsInvolved: !!dangerousGoodsInvolved, collision: !!collision,
  });
  const id = nanoid(12);
  const incidentDid = `did:web:${env.APP_HANDLE}:incident:${id}`;
  const reportedAt = now();
  await env.PORTS_DB.prepare(
    `INSERT INTO incidents (incident_did, port_did, vessel_did, occurred_at, category, pollution_tonnes,
                            injuries, dangerous_goods_involved, collision, severity, require_coast_guard_report, description, reported_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
  ).bind(incidentDid, portDid, vesselDid ?? null, occurredAt, category, pollutionTonnes,
         injuries, dangerousGoodsInvolved ? 1 : 0, collision ? 1 : 0, severity,
         requireCoastGuardReport ? 1 : 0, description ?? null, reportedAt).run();
  if (requireCoastGuardReport && env.PDS) {
    try {
      await env.PDS.fetch(`https://atproto.etzhayyim.com/xrpc/com.atproto.repo.createRecord`, {
        method: "POST", headers: { "content-type": "application/json" },
        body: JSON.stringify({
          repo: env.PRIMARY_DID, collection: "app.bsky.feed.post",
          record: { $type: "app.bsky.feed.post",
            text: `[${severity.toUpperCase()}] port incident at ${portDid} (${category}, pollution ${pollutionTonnes}t, injuries ${injuries})`,
            createdAt: reportedAt },
        }),
      });
    } catch {}
  }
  return json({ incidentDid, severity, requireCoastGuardReport, reportedAt });
}

async function listIncidents(env: Env, params: URLSearchParams): Promise<Response> {
  const portDid = params.get("portDid");
  const vesselDid = params.get("vesselDid");
  const since = params.get("since");
  const minSeverity = params.get("minSeverity");
  const limit = Math.min(500, Math.max(1, Number(params.get("limit") ?? 100)));
  const offset = Math.max(0, Number(params.get("offset") ?? 0));
  const sevRank = (s: string) => ({ minor: 0, moderate: 1, major: 2, critical: 3 } as any)[s] ?? -1;
  const clauses: string[] = []; const binds: any[] = [];
  if (portDid)   { clauses.push(`port_did = ?`);   binds.push(portDid); }
  if (vesselDid) { clauses.push(`vessel_did = ?`); binds.push(vesselDid); }
  if (since)     { clauses.push(`occurred_at >= ?`); binds.push(since); }
  const where = clauses.length ? `WHERE ${clauses.join(" AND ")}` : "";
  const total = await env.PORTS_DB.prepare(`SELECT COUNT(*) AS c FROM incidents ${where}`)
    .bind(...binds).first<{ c: number }>();
  const rows = await env.PORTS_DB.prepare(
    `SELECT * FROM incidents ${where} ORDER BY occurred_at DESC LIMIT ? OFFSET ?`
  ).bind(...binds, limit, offset).all<any>();
  let incidents = (rows.results ?? []).map((r) => ({
    incidentDid: r.incident_did, portDid: r.port_did,
    vesselDid: r.vessel_did ?? undefined, occurredAt: r.occurred_at, category: r.category,
    pollutionTonnes: r.pollution_tonnes, injuries: r.injuries,
    dangerousGoodsInvolved: !!r.dangerous_goods_involved, collision: !!r.collision,
    severity: r.severity, requireCoastGuardReport: !!r.require_coast_guard_report,
    description: r.description ?? undefined, reportedAt: r.reported_at,
  }));
  if (minSeverity) incidents = incidents.filter((i) => sevRank(i.severity) >= sevRank(minSeverity));
  return json({ incidents, total: Number(total?.c ?? 0), offset, limit });
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    try {
      await ensureSchema(env.PORTS_DB);
      const url = new URL(req.url);
      if (url.pathname === "/health" || url.pathname === "/_worker/health")
        return json({ ok: true, did: env.PRIMARY_DID, ts: now() });
      if (url.pathname === "/_app/meta") {
        if (env.PDS) { try { await bootstrapDodaf(env as any); } catch {} }
        return json({
          did: env.PRIMARY_DID, handle: env.APP_HANDLE,
          xrpc: [
            "com.etzhayyim.apps.openPorts.definePort",
            "com.etzhayyim.apps.openPorts.listPorts",
            "com.etzhayyim.apps.openPorts.registerVessel",
            "com.etzhayyim.apps.openPorts.scheduleVesselCall",
            "com.etzhayyim.apps.openPorts.recordCallEvent",
            "com.etzhayyim.apps.openPorts.listVesselCalls",
            "com.etzhayyim.apps.openPorts.recordContainerManifest",
            "com.etzhayyim.apps.openPorts.listContainers",
            "com.etzhayyim.apps.openPorts.reportIncident",
            "com.etzhayyim.apps.openPorts.listIncidents",
          ],
          dodaf: Object.keys(DODAF_VIEWS), forms: Object.keys(FORMS),
          bpmn: ["scheduleVesselCall", "reportPortIncident"],
          dmn:  ["openPorts.incidentSeverity"],
        });
      }
      if (url.pathname === "/dodaf")
        return json({ views: Object.entries(DODAF_VIEWS).map(([id, v]: any) => ({
          viewId: id, viewType: v.viewType, title: v.title, version: v.version })) });
      if (url.pathname.startsWith("/dodaf/")) {
        const id = decodeURIComponent(url.pathname.slice("/dodaf/".length));
        const v = DODAF_VIEWS[id];
        return v ? json(v) : err("InvalidRequest", `no such view: ${id}`, 404);
      }
      if (url.pathname === "/forms")
        return json({ forms: Object.values(FORMS).map((f: any) => ({ formKey: f.formKey, name: f.name, version: f.version })) });
      if (url.pathname.startsWith("/forms/")) {
        const k = decodeURIComponent(url.pathname.slice("/forms/".length));
        const f = FORMS[k];
        return f ? json(f) : err("InvalidRequest", `no such form: ${k}`, 404);
      }
      if (!url.pathname.startsWith("/xrpc/"))
        return err("InvalidRequest", "only /xrpc/* is served", 404);
      const nsid = url.pathname.slice("/xrpc/".length);
      if (req.method === "GET") {
        switch (nsid) {
          case "com.etzhayyim.apps.openPorts.listPorts":       return await listPorts(env, url.searchParams);
          case "com.etzhayyim.apps.openPorts.listVesselCalls": return await listVesselCalls(env, url.searchParams);
          case "com.etzhayyim.apps.openPorts.listContainers":  return await listContainers(env, url.searchParams);
          case "com.etzhayyim.apps.openPorts.listIncidents":   return await listIncidents(env, url.searchParams);
          default: return err("InvalidRequest", `unknown query NSID: ${nsid}`, 404);
        }
      }
      if (req.method === "POST") {
        const body = await req.json().catch(() => ({}));
        switch (nsid) {
          case "com.etzhayyim.apps.openPorts.definePort":               return await definePort(env, body);
          case "com.etzhayyim.apps.openPorts.registerVessel":           return await registerVessel(env, body);
          case "com.etzhayyim.apps.openPorts.scheduleVesselCall":       return await scheduleVesselCall(env, body);
          case "com.etzhayyim.apps.openPorts.recordCallEvent":          return await recordCallEvent(env, body);
          case "com.etzhayyim.apps.openPorts.recordContainerManifest":  return await recordContainerManifest(env, body);
          case "com.etzhayyim.apps.openPorts.reportIncident":           return await reportIncident(env, body);
          default: return err("InvalidRequest", `unknown procedure NSID: ${nsid}`, 404);
        }
      }
      return err("InvalidRequest", "method not allowed", 405);
    } catch (e: any) {
      return err("InternalError", e?.message ?? String(e), 500);
    }
  },
};
