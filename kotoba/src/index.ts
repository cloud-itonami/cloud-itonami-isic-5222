/**
 * open-ports kotoba — barrel.
 *
 * Per ADR-2605203000 Option B. Maritime port operations registry on the
 * etzhayyim substrate (AT PDS records; no RW).
 *
 *   port   : definePort / getPort / listPorts
 *   vessel : registerVessel / getVessel / listVessels
 *   call   : scheduleVesselCall / recordCallEvent / getCall / listVesselCalls
 *   coverage
 */

export * from "./types.js";
export {
  definePort,
  getPort,
  listPorts,
  registerVessel,
  getVessel,
  listVessels,
  scheduleVesselCall,
  recordCallEvent,
  getCall,
  listVesselCalls,
  coverage,
} from "./registry.js";
