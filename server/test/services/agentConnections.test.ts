import { describe, expect, it } from 'vitest';

import type { AgentSocketLike } from '../../src/services/agentConnections.js';
import { AgentConnectionRegistry } from '../../src/services/agentConnections.js';

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

    expect(registry.snapshotsForDevice('device-a')).toEqual([
      expect.objectContaining({
        id: connection.id,
        connectedAt: 1000,
        lastMessageAt: 2500,
        deviceGrantedCapabilities: ['screen.view', 'system.info'],
      }),
    ]);
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
