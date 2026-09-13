import { describe, expect, it } from 'vitest';

import {
  SCREEN_CHUNK_BYTES,
  SCREEN_CONSENT_TIMEOUT_MS,
  SCREEN_MAX_FRAME_BYTES,
  SCREEN_STREAM_IDLE_TIMEOUT_MS,
} from '../../src/constants.js';
import {
  ScreenStreamHub,
  TooManyStreamsError,
  type ScreenConsentState,
  type ScreenFrame,
  type ScreenStopReason,
  type ScreenStreamChannel,
  type ScreenStreamInfo,
} from '../../src/services/screenStreams.js';

interface SentFrame {
  readonly type: string;
  readonly payload: Record<string, unknown>;
}

class FakeChannel implements ScreenStreamChannel {
  readonly sent: SentFrame[] = [];
  readonly states: ScreenConsentState[] = [];
  readonly frames: ScreenFrame[] = [];
  info: ScreenStreamInfo | undefined;
  ended: { reason: ScreenStopReason; message: string } | undefined;

  sendToDevice(type: string, payload: Record<string, unknown>): void {
    this.sent.push({ type, payload });
  }

  status(state: ScreenConsentState): void {
    this.states.push(state);
  }

  started(info: ScreenStreamInfo): void {
    this.info = info;
  }

  frame(frame: ScreenFrame): void {
    this.frames.push(frame);
  }

  end(reason: ScreenStopReason, message: string): void {
    this.ended = { reason, message };
  }

  get acked(): number[] {
    return this.sent
      .filter((entry) => entry.type === 'screen.frame.ack')
      .map((entry) => Number(entry.payload.sequence));
  }

  get keyframeRequests(): number {
    return this.sent.filter((entry) => entry.type === 'screen.keyframe.request').length;
  }

  get stops(): string[] {
    return this.sent
      .filter((entry) => entry.type === 'screen.stop')
      .map((entry) => String(entry.payload.reason));
  }
}

const DEVICE = 'device-1';
const STREAM = 'stream-1';
const SESSION = 'session-1';

const INFO: ScreenStreamInfo = {
  width: 720,
  height: 1280,
  codec: 'avc1.42E01E',
  fps: 15,
  config: Buffer.from([0, 0, 0, 1, 103]).toString('base64'),
};

function open(now = 1_000): { hub: ScreenStreamHub; channel: FakeChannel } {
  const hub = new ScreenStreamHub();
  const channel = new FakeChannel();
  hub.begin({ streamId: STREAM, deviceId: DEVICE, sessionId: SESSION, now, channel });
  return { hub, channel };
}

/** Consent granted plus `screen.started`: the state every frame test starts from. */
function streaming(now = 1_000): { hub: ScreenStreamHub; channel: FakeChannel } {
  const opened = open(now);
  opened.hub.handleConsent(DEVICE, STREAM, 'granted', now);
  opened.hub.handleStarted(DEVICE, STREAM, INFO, now);
  return opened;
}

function frame(
  hub: ScreenStreamHub,
  input: {
    sequence: number;
    chunkIndex?: number;
    chunkCount?: number;
    keyFrame?: boolean;
    data?: Buffer;
    now?: number;
  },
): boolean {
  return hub.handleFrame({
    deviceId: DEVICE,
    streamId: STREAM,
    sequence: input.sequence,
    chunkIndex: input.chunkIndex ?? 0,
    chunkCount: input.chunkCount ?? 1,
    keyFrame: input.keyFrame ?? false,
    timestampUs: input.sequence * 66_000,
    data: input.data ?? Buffer.from('frame'),
    now: input.now ?? 2_000,
  });
}

describe('ScreenStreamHub consent', () => {
  it('reports pending and granted to the viewer', () => {
    const { hub, channel } = open();
    expect(hub.handleConsent(DEVICE, STREAM, 'pending', 1_100)).toBe(true);
    expect(hub.handleConsent(DEVICE, STREAM, 'granted', 1_200)).toBe(true);
    expect(channel.states).toEqual(['pending', 'granted']);
    expect(channel.ended).toBeUndefined();
  });

  it('ends the stream when the owner declines, and says so', () => {
    const { hub, channel } = open();
    expect(hub.handleConsent(DEVICE, STREAM, 'declined', 1_100)).toBe(true);
    expect(channel.ended?.reason).toBe('consent_declined');
    // The owner said no. Telling the device to stop something it never started would
    // be noise, so nothing is sent back.
    expect(channel.stops).toEqual([]);
    expect(hub.size).toBe(0);
  });

  it('refuses to move a granted stream back to pending', () => {
    const { hub, channel } = open();
    hub.handleConsent(DEVICE, STREAM, 'granted', 1_100);
    expect(hub.handleConsent(DEVICE, STREAM, 'pending', 1_200)).toBe(false);
    expect(channel.states).toEqual(['granted']);
  });

  it('does not start without a consent on record', () => {
    const { hub, channel } = open();
    expect(hub.handleStarted(DEVICE, STREAM, INFO, 1_100)).toBe(false);
    expect(channel.info).toBeUndefined();
    expect(channel.ended?.reason).toBe('projection_stopped');
  });

  it('treats a stream id from another device as unknown', () => {
    const { hub } = open();
    expect(hub.handleConsent('other-device', STREAM, 'granted', 1_100)).toBe(false);
    expect(hub.size).toBe(1);
  });
});

describe('ScreenStreamHub frames', () => {
  it('reassembles a chunked frame and acknowledges it once', () => {
    const { hub, channel } = streaming();
    const head = Buffer.alloc(SCREEN_CHUNK_BYTES, 1);
    const tail = Buffer.alloc(64, 2);

    expect(frame(hub, { sequence: 0, chunkIndex: 0, chunkCount: 2, keyFrame: true, data: head })).toBe(true);
    expect(channel.frames).toHaveLength(0);
    expect(frame(hub, { sequence: 0, chunkIndex: 1, chunkCount: 2, keyFrame: true, data: tail })).toBe(true);

    expect(channel.frames).toHaveLength(1);
    expect(channel.frames[0]?.data).toEqual(Buffer.concat([head, tail]));
    expect(channel.frames[0]?.keyFrame).toBe(true);
    expect(channel.acked).toEqual([0]);
  });

  it('ends the stream on a repeated sequence', () => {
    const { hub, channel } = streaming();
    frame(hub, { sequence: 0, keyFrame: true });
    expect(frame(hub, { sequence: 0, keyFrame: true })).toBe(false);
    expect(channel.ended?.reason).toBe('encoder_error');
    expect(channel.stops).toEqual(['encoder_error']);
  });

  it('ends the stream on a backwards sequence', () => {
    const { hub, channel } = streaming();
    frame(hub, { sequence: 7, keyFrame: true });
    expect(frame(hub, { sequence: 6 })).toBe(false);
    expect(channel.ended?.reason).toBe('encoder_error');
  });

  it('accepts a gap in the sequence, because a dropped frame is information', () => {
    const { hub, channel } = streaming();
    frame(hub, { sequence: 0, keyFrame: true });
    expect(frame(hub, { sequence: 9 })).toBe(true);
    expect(channel.frames.map((entry) => entry.sequence)).toEqual([0, 9]);
    expect(channel.ended).toBeUndefined();
  });

  it('drops an unfinished frame, acknowledges it and asks for a keyframe', () => {
    const { hub, channel } = streaming();
    frame(hub, { sequence: 0, keyFrame: true });
    const before = channel.keyframeRequests;

    frame(hub, { sequence: 1, chunkIndex: 0, chunkCount: 3 });
    // Sequence 2 starts while 1 is still incomplete: 1 is gone.
    frame(hub, { sequence: 2, chunkIndex: 0, chunkCount: 1 });

    expect(channel.frames.map((entry) => entry.sequence)).toEqual([0]);
    expect(channel.acked).toContain(1);
    expect(channel.acked).toContain(2);
    expect(channel.keyframeRequests).toBeGreaterThan(before);
  });

  it('withholds delta frames until the next keyframe arrives', () => {
    const { hub, channel } = streaming();
    frame(hub, { sequence: 0, keyFrame: true });
    frame(hub, { sequence: 1, chunkIndex: 0, chunkCount: 2 });
    frame(hub, { sequence: 2 });
    frame(hub, { sequence: 3 });
    expect(channel.frames.map((entry) => entry.sequence)).toEqual([0]);

    frame(hub, { sequence: 4, keyFrame: true });
    frame(hub, { sequence: 5 });
    expect(channel.frames.map((entry) => entry.sequence)).toEqual([0, 4, 5]);
  });

  it('asks again when the dropped frame was itself a keyframe', () => {
    const { hub, channel } = streaming();
    frame(hub, { sequence: 0, keyFrame: true });
    frame(hub, { sequence: 1, chunkIndex: 0, chunkCount: 2 });
    const afterFirstDrop = channel.keyframeRequests;

    frame(hub, { sequence: 2, chunkIndex: 0, chunkCount: 2, keyFrame: true });
    frame(hub, { sequence: 3, chunkIndex: 0, chunkCount: 1 });

    // Without this, a keyframe that keeps arriving torn would leave the stream
    // waiting forever for one it already asked for.
    expect(channel.keyframeRequests).toBeGreaterThan(afterFirstDrop);
  });

  it('refuses a frame whose declared size cannot fit, without buffering it', () => {
    const { hub, channel } = streaming();
    const chunkCount = Math.ceil(SCREEN_MAX_FRAME_BYTES / SCREEN_CHUNK_BYTES) + 1;
    expect(frame(hub, { sequence: 0, chunkIndex: 0, chunkCount, keyFrame: true })).toBe(true);
    expect(channel.frames).toHaveLength(0);
    expect(channel.acked).toEqual([0]);
    expect(channel.ended).toBeUndefined();
  });

  it('ends the stream on an oversized chunk', () => {
    const { hub, channel } = streaming();
    const data = Buffer.alloc(SCREEN_CHUNK_BYTES + 1);
    expect(frame(hub, { sequence: 0, keyFrame: true, data })).toBe(false);
    expect(channel.ended?.reason).toBe('encoder_error');
  });

  it('ends the stream on an impossible chunk index', () => {
    const { hub, channel } = streaming();
    expect(frame(hub, { sequence: 0, chunkIndex: 2, chunkCount: 2 })).toBe(false);
    expect(channel.ended?.reason).toBe('encoder_error');
  });

  it('ignores a continuation chunk that belongs to no frame in progress', () => {
    const { hub, channel } = streaming();
    frame(hub, { sequence: 0, keyFrame: true });
    expect(frame(hub, { sequence: 1, chunkIndex: 1, chunkCount: 2 })).toBe(true);
    expect(channel.ended).toBeUndefined();
    expect(channel.frames).toHaveLength(1);
  });

  it('refuses frames before screen.started', () => {
    const { hub, channel } = open();
    hub.handleConsent(DEVICE, STREAM, 'granted', 1_100);
    expect(frame(hub, { sequence: 0, keyFrame: true })).toBe(false);
    expect(channel.ended?.reason).toBe('projection_stopped');
  });
});

describe('ScreenStreamHub lifecycle', () => {
  it('allows only one stream per device', () => {
    const { hub } = open();
    expect(() =>
      hub.begin({
        streamId: 'stream-2',
        deviceId: DEVICE,
        sessionId: SESSION,
        now: 1_000,
        channel: new FakeChannel(),
      }),
    ).toThrow(TooManyStreamsError);
  });

  it('tells the device to stop when the server ends the stream', () => {
    const { hub, channel } = streaming();
    expect(hub.stop(STREAM, 'capability_revoked', 'screen.view entzogen')).toBe(true);
    expect(channel.stops).toEqual(['capability_revoked']);
    expect(channel.ended?.reason).toBe('capability_revoked');
    expect(hub.size).toBe(0);
  });

  it('does not echo a stop back at the device that sent it', () => {
    const { hub, channel } = streaming();
    expect(hub.stopFromDevice(DEVICE, STREAM, 'owner_stopped')).toBe(true);
    expect(channel.stops).toEqual([]);
    expect(channel.ended?.reason).toBe('owner_stopped');
  });

  it('ends every stream of a revoked device', () => {
    const { hub, channel } = streaming();
    expect(hub.stopForDevice(DEVICE, 'device_revoked', 'Geraet widerrufen')).toBe(1);
    expect(channel.ended?.reason).toBe('device_revoked');
  });

  it('ends every stream of an expired session', () => {
    const { hub, channel } = streaming();
    expect(hub.stopForSession(SESSION, 'session_expired', 'Sitzung abgelaufen')).toBe(1);
    expect(channel.ended?.reason).toBe('session_expired');
  });

  it('waits a full minute for the owner but only seconds for a frame', () => {
    const waiting = open(1_000);
    expect(waiting.hub.sweep(1_000 + SCREEN_STREAM_IDLE_TIMEOUT_MS)).toBe(0);
    expect(waiting.hub.sweep(1_000 + SCREEN_CONSENT_TIMEOUT_MS)).toBe(1);
    expect(waiting.channel.ended?.reason).toBe('consent_timeout');

    const running = streaming(1_000);
    expect(running.hub.sweep(1_000 + SCREEN_STREAM_IDLE_TIMEOUT_MS)).toBe(1);
    expect(running.channel.ended?.reason).toBe('timeout');
  });

  it('keeps a stream alive while frames keep arriving', () => {
    const { hub } = streaming(1_000);
    frame(hub, { sequence: 0, keyFrame: true, now: 1_000 + SCREEN_STREAM_IDLE_TIMEOUT_MS - 1 });
    expect(hub.sweep(1_000 + SCREEN_STREAM_IDLE_TIMEOUT_MS)).toBe(0);
  });

  it('requests a keyframe only for a running stream', () => {
    const { hub, channel } = open();
    expect(hub.requestKeyframe(STREAM)).toBe(false);
    hub.handleConsent(DEVICE, STREAM, 'granted', 1_100);
    hub.handleStarted(DEVICE, STREAM, INFO, 1_100);
    expect(hub.requestKeyframe(STREAM)).toBe(true);
    expect(channel.keyframeRequests).toBe(1);
  });

  it('reports an unknown stream instead of throwing', () => {
    const hub = new ScreenStreamHub();
    expect(hub.stop('nope', 'timeout', 'x')).toBe(false);
    expect(hub.stopFromDevice(DEVICE, 'nope', 'timeout')).toBe(false);
    expect(hub.requestKeyframe('nope')).toBe(false);
  });
});
