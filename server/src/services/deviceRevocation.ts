import type { AppContext } from '../context.js';
import type { DeviceRecord } from '../db/repositories/types.js';
import { agentFrame } from '../http/deviceAccess.js';

/**
 * Ending a pairing, in one place.
 *
 * There are two ways a pairing ends: the owner presses the button, or the server finds that two
 * parties are holding the same token chain (THREAT_MODEL 4.13). Both have to do the same seven
 * things, in the same order, and a second copy of that list would drift - the copy that forgets to
 * stop a running screen capture leaves a device filming for a pairing that no longer exists.
 */

export type RevocationReason = 'revoked_by_owner' | 'token_reuse';

const MESSAGE: Record<RevocationReason, string> = {
  revoked_by_owner: 'Geraet wurde widerrufen',
  token_reuse: 'Geraete-Token wurde doppelt verwendet',
};

export async function revokeDevice(
  context: AppContext,
  input: {
    readonly device: DeviceRecord;
    readonly reason: RevocationReason;
    readonly now: number;
    /** The owner who pressed the button, absent when the server decided by itself. */
    readonly userId?: string | undefined;
  },
): Promise<void> {
  const { device, reason, now } = input;
  const message = MESSAGE[reason];

  // Told before the socket closes, so the device knows why rather than seeing a connection drop.
  context.agentConnections.sendToDevice(
    device.id,
    agentFrame('device.revoked', { reason }, now),
  );

  await context.repositories.devices.revoke(device.id, now);
  await context.repositories.deviceTokens.revokeAllForDevice(device.id, now);
  await context.repositories.remoteSessions.revokeAllForDevice(device.id, now);
  await context.repositories.deviceCapabilities.clearForDevice(device.id);
  // Stop the bytes before closing the socket: a transfer in flight must not keep delivering a
  // file from a device that was just revoked.
  context.fileTransfers.cancelForDevice(device.id, 'device_revoked', message);
  // Same reason for the picture: a revoked device must stop capturing before its socket closes,
  // not when the stream happens to time out.
  context.screenStreams.stopForDevice(device.id, 'device_revoked', message);
  context.agentConnections.closeDevice(device.id, 4003, 'device revoked');

  await context.audit.record({
    eventType: reason === 'token_reuse' ? 'device.token.reuse' : 'device.revoke',
    result: reason === 'token_reuse' ? 'failure' : 'success',
    userId: input.userId,
    deviceId: device.deviceId,
  });
}
