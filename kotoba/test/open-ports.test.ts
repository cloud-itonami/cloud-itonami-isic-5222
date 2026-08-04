import { describe, it, expect, beforeEach } from "vitest";
import { MockEtzhayyim } from "@etzhayyim/sdk-mock";
import {
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
  isValidLocode,
  isValidImo,
} from "../src/index.js";

describe("open-ports kotoba", () => {
  let e: any;
  beforeEach(() => {
    e = new MockEtzhayyim({ did: "did:web:open-ports.etzhayyim.com" });
  });

  describe("port + vessel", () => {
    it("validates LOCODE/IMO", () => {
      expect(isValidLocode("JPTYO")).toBe(true);
      expect(isValidLocode("JPT")).toBe(false);
      expect(isValidImo("9074729")).toBe(true);
      expect(isValidImo("12345")).toBe(false);
    });
    it("defines port (country from locode) + registers vessel", async () => {
      expect((await definePort(e, { locode: "JPTYO", name: "Tokyo", berths: 20 })).status).toBe("defined");
      expect((await getPort(e, { locode: "jptyo" })).port?.country).toBe("JP");
      expect((await listPorts(e, { country: "JP" })).total).toBe(1);
      expect((await registerVessel(e, { imo: "9074729", name: "Ever Given", mmsi: "353136000", flag: "PA", vesselType: "container" })).status).toBe("registered");
      expect((await getVessel(e, { imo: "9074729" })).vessel?.flag).toBe("PA");
      expect((await listVessels(e, { flag: "PA" })).total).toBe(1);
    });
    it("rejects bad locode/imo/mmsi", async () => {
      expect((await definePort(e, { locode: "XX", name: "x" })).status).toBe("rejected");
      expect((await registerVessel(e, { imo: "123", name: "x" })).status).toBe("rejected");
      expect((await registerVessel(e, { imo: "9074729", name: "x", mmsi: "12" })).status).toBe("rejected");
    });
  });

  describe("vessel call (lifecycle) + coverage", () => {
    beforeEach(async () => {
      await definePort(e, { locode: "JPTYO", name: "Tokyo" });
      await registerVessel(e, { imo: "9074729", name: "Ever Given" });
    });
    it("schedules against existing vessel+port; rejects missing", async () => {
      expect((await scheduleVesselCall(e, { callId: "C-1", vesselImo: "9074729", portLocode: "JPTYO", berth: "A1", eta: "2026-07-01T08:00:00Z" })).status).toBe("scheduled");
      expect((await getCall(e, { callId: "C-1" })).call?.status).toBe("scheduled");
      expect((await scheduleVesselCall(e, { callId: "C-2", vesselImo: "0000000", portLocode: "JPTYO" })).status).toBe("vesselNotFound");
      expect((await scheduleVesselCall(e, { callId: "C-3", vesselImo: "9074729", portLocode: "XXXXX" })).status).toBe("portNotFound");
    });
    it("records ATA→berthed→departed events + terminal guard", async () => {
      await scheduleVesselCall(e, { callId: "C-1", vesselImo: "9074729", portLocode: "JPTYO" });
      expect((await recordCallEvent(e, { callId: "C-1", event: "ata", at: "2026-07-01T08:05:00Z" })).newStatus).toBe("arrived");
      await recordCallEvent(e, { callId: "C-1", event: "berthed", at: "2026-07-01T09:00:00Z" });
      expect((await recordCallEvent(e, { callId: "C-1", event: "departed", at: "2026-07-01T18:00:00Z" })).newStatus).toBe("departed");
      const c = await getCall(e, { callId: "C-1" });
      expect(c.call?.times.ata).toBe("2026-07-01T08:05:00Z");
      expect((await recordCallEvent(e, { callId: "C-1", event: "berthed" })).status).toBe("rejected");
    });
    it("lists by port/status + coverage rolls up", async () => {
      await scheduleVesselCall(e, { callId: "C-1", vesselImo: "9074729", portLocode: "JPTYO" });
      expect((await listVesselCalls(e, { portLocode: "JPTYO", status: "scheduled" })).total).toBe(1);
      const cov = await coverage(e);
      expect(cov.portCount).toBe(1);
      expect(cov.vesselCount).toBe(1);
      expect(cov.callCount).toBe(1);
      expect(cov.callsByStatus?.scheduled).toBe(1);
    });
  });
});
