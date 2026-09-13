import { randomUUID } from 'node:crypto';

/**
 * Minimal socket surface used by the registry. Keeping the registry independent
 * from `ws` makes the lifecycle logic easy to unit test and avoids coupling
 * domain code to one transport package.
 */
export interface AgentSocketLike {
  send(data: string): void;
  close(code?: number, reason?: string): void;
}

export interface AgentConnectionSnapshot {
  readonly id: string;
  readonly deviceId: string;
  readonly connectedAt: number;
  readonly lastMessageAt: number;
  readonly deviceGrantedCapabilities: readonly string[];
}

interface AgentConnectionEntry {
  readonly id: string;
  readonly deviceId: string;
  readonly socket: AgentSocketLike;
  readonly connectedAt: number;
  lastMessageAt: number;
  deviceGrantedCapabilities: readonly string[];
}

/**
 * Tracks currently connected device agents in-process.
 *
 * This is intentionally an MVP single-node registry. A multi-node deployment
 * needs a shared presence/event layer (for example Redis) behind the same
 * conceptual interface.
 */
export class AgentConnectionRegistry {
  private readonly byId = new Map<string, AgentConnectionEntry>();
  private readonly byDevice = new Map<string, Set<string>>();

  register(deviceId: string, socket: AgentSocketLike, now: number): AgentConnectionSnapshot {
    const id = randomUUID();
    const entry: AgentConnectionEntry = {
      id,
      deviceId,
      socket,
      connectedAt: now,
      lastMessageAt: now,
      deviceGrantedCapabilities: [],
    };
    this.byId.set(id, entry);
    const ids = this.byDevice.get(deviceId) ?? new Set<string>();
    ids.add(id);
    this.byDevice.set(deviceId, ids);
    return this.snapshotOf(entry);
  }

  unregister(connectionId: string): void {
    const entry = this.byId.get(connectionId);
    if (entry === undefined) {
      return;
    }
    this.byId.delete(connectionId);
    const ids = this.byDevice.get(entry.deviceId);
    ids?.delete(connectionId);
    if (ids !== undefined && ids.size === 0) {
      this.byDevice.delete(entry.deviceId);
    }
  }

  touch(connectionId: string, now: number): void {
    const entry = this.byId.get(connectionId);
    if (entry !== undefined) {
      entry.lastMessageAt = now;
    }
  }

  setDeviceGrantedCapabilities(
    connectionId: string,
    capabilities: readonly string[],
  ): void {
    const entry = this.byId.get(connectionId);
    if (entry !== undefined) {
      entry.deviceGrantedCapabilities = [...new Set(capabilities)].sort();
    }
  }

  isConnected(deviceId: string): boolean {
    return (this.byDevice.get(deviceId)?.size ?? 0) > 0;
  }

  snapshotsForDevice(deviceId: string): readonly AgentConnectionSnapshot[] {
    const ids = this.byDevice.get(deviceId);
    if (ids === undefined) {
      return [];
    }
    return [...ids]
      .map((id) => this.byId.get(id))
      .filter((entry): entry is AgentConnectionEntry => entry !== undefined)
      .map((entry) => this.snapshotOf(entry));
  }

  /** Sends an already serialized protocol frame to every connection of a device. */
  sendToDevice(deviceId: string, frame: string): number {
    const ids = this.byDevice.get(deviceId);
    if (ids === undefined) {
      return 0;
    }
    let sent = 0;
    for (const id of [...ids]) {
      const entry = this.byId.get(id);
      if (entry === undefined) {
        continue;
      }
      try {
        entry.socket.send(frame);
        sent += 1;
      } catch {
        this.unregister(id);
      }
    }
    return sent;
  }

  closeDevice(deviceId: string, code = 4003, reason = 'device revoked'): number {
    const ids = this.byDevice.get(deviceId);
    if (ids === undefined) {
      return 0;
    }
    let closed = 0;
    for (const id of [...ids]) {
      const entry = this.byId.get(id);
      if (entry === undefined) {
        continue;
      }
      try {
        entry.socket.close(code, reason);
      } finally {
        this.unregister(id);
        closed += 1;
      }
    }
    return closed;
  }

  get size(): number {
    return this.byId.size;
  }

  private snapshotOf(entry: AgentConnectionEntry): AgentConnectionSnapshot {
    return {
      id: entry.id,
      deviceId: entry.deviceId,
      connectedAt: entry.connectedAt,
      lastMessageAt: entry.lastMessageAt,
      deviceGrantedCapabilities: [...entry.deviceGrantedCapabilities],
    };
  }
}
