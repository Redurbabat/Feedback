import { describe, expect, it } from 'vitest';

import type { AgentSocketLike } from '../../src/services/agentConnections.js';
import {
  AgentCapabilityUnavailableError,
  AgentConnectionRegistry,
  AgentOfflineError,
  AgentRequestTimeoutError,
} from '../../src/services/agentConnections.js';

class FakeSocket implements AgentSocketLike {
  readonly sent: string[] = [];
  readonly closed: Array<{ code?: number; reason?: string }> = [];
  failSend = false;

  send(data: string): void {
    if (this.failSend) {
      throw new Error('send failed');
    }
    this.sent.push(data);
  }

  close(code?: number, reason?: string): void {
    this.closed.push({ code, reason });
  }
}

describe('AgentConnectionRegistry', () => {
  it('tracks multiple connections per device and cleans them up independently', () => {
    const registry = new AgentConnectionRegistry();
    const first = new FakeSocket();
    const second = new FakeSocket();
    const a = registry.register('device-a', first, 1000);
    const b = registry.register('device-a', second, 1001);

    expect(registry.isConnected('device-a')).toBe(true);
    expect(registry.size).toBe(2);
    expect(registry.snapshotsForDevice('device-a')).toHaveLength(2);

    registry.unregister(a.id);
    expect(registry.isConnected('device-a')).toBe(true);
    expect(registry.size).toBe(1);

    registry.unregister(b.id);
    expect(registry.isConnected('device-a')).toBe(false);
    expect(registry.size).toBe(0);
  });

  it('updates last activity and stores a deduplicated local capability snapshot', () => {
    const registry = new AgentConnectionRegistry();
    const connection = registry.register('device-a', new FakeSocket(), 1000);

    registry.touch(connection.id, 2500);
    registry.setDeviceGrantedCapabilities(connection.id, [
      'system.info',
      'system.info',
      'screen.view',
    ]);

    expect(registry.snapshot(connection.id)).toEqual(
      expect.objectContaining({
        id: connection.id,
        connectedAt: 1000,
        lastMessageAt: 2500,
        deviceGrantedCapabilities: ['screen.view', 'system.info'],
      }),
    );
  });

  it('sends a frame to every live connection and removes a socket that throws', () => {
    const registry = new AgentConnectionRegistry();
    const good = new FakeSocket();
    const broken = new FakeSocket();
    broken.failSend = true;
    registry.register('device-a', good, 1000);
    registry.register('device-a', broken, 1000);

    expect(registry.sendToDevice('device-a', '{"type":"test"}')).toBe(1);
    expect(good.sent).toEqual(['{"type":"test"}']);
    expect(registry.size).toBe(1);
  });

  it('correlates a privileged request by session id and resolves exactly once', async () => {
    const registry = new AgentConnectionRegistry();
    const socket = new FakeSocket();
    const connection = registry.register('device-a', socket, 1000);
    registry.setDeviceGrantedCapabilities(connection.id, ['system.info']);

    const pending = registry.requestDevice({
      deviceId: 'device-a',
      sessionId: 'session-1',
      messageId: 'message-1',
      requiredCapability: 'system.info',
      frame: '{"type":"system.info.request"}',
      timeoutMs: 1000,
    });

    expect(socket.sent).toEqual(['{"type":"system.info.request"}']);
    expect(registry.pendingSize).toBe(1);
    expect(registry.resolveResponse('other-device', 'message-1', { ok: false })).toBe(false);
    // The session id is not a correlation key and must not resolve anything.
    expect(registry.resolveResponse('device-a', 'session-1', { ok: false })).toBe(false);
    expect(registry.resolveResponse('device-a', 'message-1', { ok: true })).toBe(true);
    await expect(pending).resolves.toEqual({ ok: true });
    expect(registry.pendingSize).toBe(0);
    expect(registry.resolveResponse('device-a', 'message-1', { ok: true })).toBe(false);
  });

  it('denies a privileged request when the device did not grant the capability', async () => {
    const registry = new AgentConnectionRegistry();
    registry.register('device-a', new FakeSocket(), 1000);

    await expect(
      registry.requestDevice({
        deviceId: 'device-a',
        sessionId: 'session-1',
        messageId: 'message-1',
        requiredCapability: 'system.info',
        frame: '{}',
        timeoutMs: 100,
      }),
    ).rejects.toBeInstanceOf(AgentCapabilityUnavailableError);
  });

  it('fails fast when the device is offline and rejects pending work on disconnect', async () => {
    const registry = new AgentConnectionRegistry();
    await expect(
      registry.requestDevice({
        deviceId: 'device-a',
        sessionId: 'missing',
        messageId: 'message-missing',
        requiredCapability: 'system.info',
        frame: '{}',
        timeoutMs: 100,
      }),
    ).rejects.toBeInstanceOf(AgentOfflineError);

    const socket = new FakeSocket();
    const connection = registry.register('device-a', socket, 1000);
    registry.setDeviceGrantedCapabilities(connection.id, ['system.info']);
    const pending = registry.requestDevice({
      deviceId: 'device-a',
      sessionId: 'disconnecting',
      messageId: 'message-disconnecting',
      requiredCapability: 'system.info',
      frame: '{}',
      timeoutMs: 1000,
    });
    registry.unregister(connection.id);
    await expect(pending).rejects.toBeInstanceOf(AgentOfflineError);
  });

  it('times out unanswered requests and releases the pending slot', async () => {
    const registry = new AgentConnectionRegistry();
    const connection = registry.register('device-a', new FakeSocket(), 1000);
    registry.setDeviceGrantedCapabilities(connection.id, ['system.info']);

    const pending = registry.requestDevice({
      deviceId: 'device-a',
      sessionId: 'session-timeout',
      messageId: 'message-timeout',
      requiredCapability: 'system.info',
      frame: '{}',
      timeoutMs: 5,
    });
    await expect(pending).rejects.toBeInstanceOf(AgentRequestTimeoutError);
    expect(registry.pendingSize).toBe(0);
  });

  it('keeps two requests of one session apart and fails both when the session dies', async () => {
    const registry = new AgentConnectionRegistry();
    const socket = new FakeSocket();
    const connection = registry.register('device-a', socket, 1000);
    registry.setDeviceGrantedCapabilities(connection.id, ['files.read']);

    // A files session carries several requests at once. Keying on the session id
    // would make the second collide with the first.
    const first = registry.requestDevice({
      deviceId: 'device-a',
      sessionId: 'files-session',
      messageId: 'message-first',
      requiredCapability: 'files.read',
      frame: '{"n":1}',
      timeoutMs: 1000,
    });
    const second = registry.requestDevice({
      deviceId: 'device-a',
      sessionId: 'files-session',
      messageId: 'message-second',
      requiredCapability: 'files.read',
      frame: '{"n":2}',
      timeoutMs: 1000,
    });
    expect(registry.pendingSize).toBe(2);

    expect(registry.resolveResponse('device-a', 'message-second', { which: 2 })).toBe(true);
    await expect(second).resolves.toEqual({ which: 2 });
    expect(registry.pendingSize).toBe(1);

    // Revoking the session must not leave the first request waiting forever.
    expect(registry.rejectPendingForSession('files-session', new AgentOfflineError())).toBe(1);
    await expect(first).rejects.toBeInstanceOf(AgentOfflineError);
    expect(registry.pendingSize).toBe(0);
  });

  it('leaves requests of other sessions untouched when one session is revoked', async () => {
    const registry = new AgentConnectionRegistry();
    const connection = registry.register('device-a', new FakeSocket(), 1000);
    registry.setDeviceGrantedCapabilities(connection.id, ['files.read']);

    const keep = registry.requestDevice({
      deviceId: 'device-a',
      sessionId: 'session-keep',
      messageId: 'message-keep',
      requiredCapability: 'files.read',
      frame: '{}',
      timeoutMs: 1000,
    });

    expect(registry.rejectPendingForSession('session-other', new AgentOfflineError())).toBe(0);
    expect(registry.pendingSize).toBe(1);

    expect(registry.resolveResponse('device-a', 'message-keep', { ok: true })).toBe(true);
    await expect(keep).resolves.toEqual({ ok: true });
  });

  it('closes and unregisters every connection of a revoked device', () => {
    const registry = new AgentConnectionRegistry();
    const first = new FakeSocket();
    const second = new FakeSocket();
    registry.register('device-a', first, 1000);
    registry.register('device-a', second, 1000);
    registry.register('device-b', new FakeSocket(), 1000);

    expect(registry.closeDevice('device-a', 4003, 'device revoked')).toBe(2);
    expect(first.closed).toEqual([{ code: 4003, reason: 'device revoked' }]);
    expect(second.closed).toEqual([{ code: 4003, reason: 'device revoked' }]);
    expect(registry.isConnected('device-a')).toBe(false);
    expect(registry.isConnected('device-b')).toBe(true);
    expect(registry.size).toBe(1);
  });
});
