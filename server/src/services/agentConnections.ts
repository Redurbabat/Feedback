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

interface PendingResponse {
  readonly deviceId: string;
  readonly sessionId: string | null;
  readonly timer: NodeJS.Timeout;
  readonly resolve: (payload: unknown) => void;
  readonly reject: (error: Error) => void;
}

export class AgentOfflineError extends Error {
  constructor() {
    super('device agent is offline');
    this.name = 'AgentOfflineError';
  }
}

export class AgentCapabilityUnavailableError extends Error {
  constructor(readonly capabilities: readonly string[]) {
    super(`device has not granted any of: ${capabilities.join(', ')}`);
    this.name = 'AgentCapabilityUnavailableError';
  }
}

export class AgentRequestTimeoutError extends Error {
  constructor() {
    super('device agent request timed out');
    this.name = 'AgentRequestTimeoutError';
  }
}

/**
 * Tracks currently connected device agents in-process and correlates
 * request/response operations by `messageId`.
 *
 * The correlation key is deliberately the message, not the Remote Session: a
 * `files.read` session carries many requests at once, so keying by session id
 * would let the second request collide with the first. The session stays the
 * authorisation frame and is recorded here only so that revoking it can fail
 * every request still waiting under it (protocol section 8.3.4).
 *
 * This is intentionally an MVP single-node registry. A multi-node deployment
 * needs a shared presence/event layer (for example Redis) behind the same
 * conceptual interface.
 */
export class AgentConnectionRegistry {
  private readonly byId = new Map<string, AgentConnectionEntry>();
  private readonly byDevice = new Map<string, Set<string>>();
  private readonly pendingResponses = new Map<string, PendingResponse>();

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
      this.rejectPendingForDevice(entry.deviceId, new AgentOfflineError());
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

  snapshot(connectionId: string): AgentConnectionSnapshot | undefined {
    const entry = this.byId.get(connectionId);
    return entry === undefined ? undefined : this.snapshotOf(entry);
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

  /**
   * Sends one privileged request to the freshest connection that locally
   * advertises `requiredCapability`, then waits for the response carrying this
   * `messageId` in `relatesTo`.
   */
  requestDevice(input: {
    deviceId: string;
    sessionId: string | null;
    messageId: string;
    /** The connection must locally advertise at least one of these. */
    requiredCapabilities: readonly string[];
    frame: string;
    timeoutMs: number;
  }): Promise<unknown> {
    if (this.pendingResponses.has(input.messageId)) {
      return Promise.reject(new Error('duplicate pending message id'));
    }

    const allConnections = this.entriesForDevice(input.deviceId);
    if (allConnections.length === 0) {
      return Promise.reject(new AgentOfflineError());
    }
    const eligible = allConnections
      .filter((entry) =>
        input.requiredCapabilities.some((capability) =>
          entry.deviceGrantedCapabilities.includes(capability),
        ),
      )
      .sort((left, right) => right.lastMessageAt - left.lastMessageAt);
    const connection = eligible[0];
    if (connection === undefined) {
      return Promise.reject(new AgentCapabilityUnavailableError(input.requiredCapabilities));
    }

    return new Promise<unknown>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pendingResponses.delete(input.messageId);
        reject(new AgentRequestTimeoutError());
      }, input.timeoutMs);
      timer.unref();

      this.pendingResponses.set(input.messageId, {
        deviceId: input.deviceId,
        sessionId: input.sessionId,
        timer,
        resolve,
        reject,
      });

      try {
        connection.socket.send(input.frame);
      } catch {
        this.finishPending(input.messageId, undefined, new AgentOfflineError());
        this.unregister(connection.id);
      }
    });
  }

  /** Returns false for a late, duplicate, wrong-device or unknown response. */
  resolveResponse(deviceId: string, messageId: string, payload: unknown): boolean {
    const pending = this.pendingResponses.get(messageId);
    if (pending === undefined || pending.deviceId !== deviceId) {
      return false;
    }
    this.finishPending(messageId, payload);
    return true;
  }

  /**
   * Fails every request still waiting under one Remote Session. Called when the
   * session is revoked or expires, so a caller never keeps waiting on an
   * authorisation that no longer exists.
   */
  rejectPendingForSession(sessionId: string, error: Error): number {
    let rejected = 0;
    for (const [messageId, pending] of [...this.pendingResponses]) {
      if (pending.sessionId === sessionId) {
        this.finishPending(messageId, undefined, error);
        rejected += 1;
      }
    }
    return rejected;
  }

  closeDevice(deviceId: string, code = 4003, reason = 'device revoked'): number {
    const ids = this.byDevice.get(deviceId);
    if (ids === undefined) {
      this.rejectPendingForDevice(deviceId, new AgentOfflineError());
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
    this.rejectPendingForDevice(deviceId, new AgentOfflineError());
    return closed;
  }

  get size(): number {
    return this.byId.size;
  }

  get pendingSize(): number {
    return this.pendingResponses.size;
  }

  private entriesForDevice(deviceId: string): AgentConnectionEntry[] {
    const ids = this.byDevice.get(deviceId);
    if (ids === undefined) {
      return [];
    }
    return [...ids]
      .map((id) => this.byId.get(id))
      .filter((entry): entry is AgentConnectionEntry => entry !== undefined);
  }

  private finishPending(messageId: string, payload?: unknown, error?: Error): void {
    const pending = this.pendingResponses.get(messageId);
    if (pending === undefined) {
      return;
    }
    this.pendingResponses.delete(messageId);
    clearTimeout(pending.timer);
    if (error !== undefined) {
      pending.reject(error);
    } else {
      pending.resolve(payload);
    }
  }

  private rejectPendingForDevice(deviceId: string, error: Error): void {
    for (const [messageId, pending] of [...this.pendingResponses]) {
      if (pending.deviceId === deviceId) {
        this.finishPending(messageId, undefined, error);
      }
    }
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
