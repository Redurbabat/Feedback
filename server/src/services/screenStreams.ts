import {
  SCREEN_CHUNK_BYTES,
  SCREEN_CONSENT_TIMEOUT_MS,
  SCREEN_MAX_CONCURRENT_STREAMS,
  SCREEN_MAX_FRAME_BYTES,
  SCREEN_STREAM_IDLE_TIMEOUT_MS,
} from '../constants.js';

/** Reasons a stream may end (protocol section 8.5.8). */
export const SCREEN_STOP_REASONS = [
  'owner_stopped',
  'client_cancelled',
  'session_expired',
  'capability_revoked',
  'device_revoked',
  'consent_declined',
  'consent_timeout',
  'projection_stopped',
  'encoder_error',
  'timeout',
  'connection_lost',
] as const;

export type ScreenStopReason = (typeof SCREEN_STOP_REASONS)[number];

export function isScreenStopReason(value: unknown): value is ScreenStopReason {
  return (SCREEN_STOP_REASONS as readonly unknown[]).includes(value);
}

/** What the device reports about the owner's answer (protocol section 8.5.4). */
export type ScreenConsentState = 'pending' | 'granted' | 'declined';

export interface ScreenStreamInfo {
  readonly width: number;
  readonly height: number;
  readonly codec: string;
  readonly fps: number;
  /** base64 encoder configuration (SPS/PPS in Annex-B). */
  readonly config: string;
}

export interface ScreenFrame {
  readonly sequence: number;
  readonly keyFrame: boolean;
  readonly timestampUs: number;
  readonly data: Buffer;
}

/**
 * Everything one stream needs from the outside world.
 *
 * As with `TransferChannel`, keeping this an interface is what makes the rules below
 * testable without a socket or an HTTP response - and the rules are the part that
 * decides whether a frame reaches a viewer.
 */
export interface ScreenStreamChannel {
  /** Sends one frame to the device (`screen.frame.ack`, `screen.keyframe.request`, `screen.stop`). */
  sendToDevice(type: string, payload: Record<string, unknown>): void;
  /** The owner has been asked, has agreed, or has refused. */
  status(state: ScreenConsentState): void;
  /** The encoder is running; the viewer can configure its decoder now. */
  started(info: ScreenStreamInfo): void;
  /** One complete, in-order frame. */
  frame(frame: ScreenFrame): void;
  /** The stream is over, for any reason. */
  end(reason: ScreenStopReason, message: string): void;
}

export class TooManyStreamsError extends Error {
  constructor() {
    super('too many concurrent screen streams for this device');
    this.name = 'TooManyStreamsError';
  }
}

type StreamState = 'awaiting_consent' | 'granted' | 'streaming';

interface PartialFrame {
  readonly sequence: number;
  readonly chunkCount: number;
  readonly keyFrame: boolean;
  readonly timestampUs: number;
  readonly parts: Buffer[];
  receivedBytes: number;
}

interface ActiveStream {
  readonly streamId: string;
  readonly deviceId: string;
  readonly sessionId: string;
  readonly channel: ScreenStreamChannel;
  readonly startedAt: number;
  state: StreamState;
  lastProgressAt: number;
  /** Highest frame sequence whose first chunk was accepted; -1 before the first frame. */
  lastSequenceStarted: number;
  partial: PartialFrame | undefined;
  /** A frame was lost, so every delta frame is useless until the next keyframe. */
  needsKeyframe: boolean;
  done: boolean;
}

/**
 * Bridges `screen.*` frames onto a viewer (an SSE response, in production).
 *
 * The server is a pipe here too: a frame is reassembled in memory, handed on and
 * forgotten. Nothing reaches disk, there is no snapshot and no recording
 * (protocol section 8.5.10).
 *
 * The loss rules are deliberately the opposite of `FileTransferHub`. A file must be
 * *complete*, so a gap aborts the transfer. A live picture must be *current*, so a
 * gap costs one frame and the answer is to ask for a keyframe. What is not tolerated
 * either way is a counterpart that contradicts itself: a repeated or backwards
 * sequence is not loss, it is a broken sender, and it ends the stream.
 */
export class ScreenStreamHub {
  private readonly byStream = new Map<string, ActiveStream>();

  constructor(private readonly maxConcurrentPerDevice: number = SCREEN_MAX_CONCURRENT_STREAMS) {}

  begin(input: {
    streamId: string;
    deviceId: string;
    sessionId: string;
    now: number;
    channel: ScreenStreamChannel;
  }): void {
    if (this.byStream.has(input.streamId)) {
      throw new Error('duplicate stream id');
    }
    if (this.activeCountForDevice(input.deviceId) >= this.maxConcurrentPerDevice) {
      throw new TooManyStreamsError();
    }
    this.byStream.set(input.streamId, {
      streamId: input.streamId,
      deviceId: input.deviceId,
      sessionId: input.sessionId,
      channel: input.channel,
      startedAt: input.now,
      state: 'awaiting_consent',
      lastProgressAt: input.now,
      lastSequenceStarted: -1,
      partial: undefined,
      needsKeyframe: false,
      done: false,
    });
  }

  /**
   * The device reports what the owner did. Returns false for a frame that belongs to
   * no open stream or contradicts the state it is in.
   */
  handleConsent(
    deviceId: string,
    streamId: string,
    state: ScreenConsentState,
    now: number,
  ): boolean {
    const stream = this.lookup(deviceId, streamId);
    if (stream === undefined) {
      return false;
    }
    if (state === 'declined') {
      // Not an error and not a failure: the owner said no, which is the system
      // working. The viewer is told exactly that instead of a generic timeout.
      this.finish(stream, 'consent_declined', 'Der Besitzer hat die Freigabe abgelehnt', false);
      return true;
    }
    if (state === 'granted') {
      if (stream.state === 'streaming') {
        return false;
      }
      stream.state = 'granted';
    } else if (stream.state !== 'awaiting_consent') {
      // `pending` after the owner already answered would move the viewer backwards.
      return false;
    }
    stream.lastProgressAt = now;
    stream.channel.status(state);
    return true;
  }

  /** The encoder is configured and about to send its first keyframe. */
  handleStarted(
    deviceId: string,
    streamId: string,
    info: ScreenStreamInfo,
    now: number,
  ): boolean {
    const stream = this.lookup(deviceId, streamId);
    if (stream === undefined) {
      return false;
    }
    if (stream.state === 'streaming') {
      return false;
    }
    if (stream.state !== 'granted') {
      // Frames before a consent is on record would be exactly the case this whole
      // capability exists to prevent.
      this.abort(stream, 'projection_stopped', 'screen.started ohne vorherige Zustimmung');
      return false;
    }
    stream.state = 'streaming';
    stream.lastProgressAt = now;
    stream.needsKeyframe = true;
    stream.channel.started(info);
    return true;
  }

  /**
   * Accepts one chunk of one frame.
   *
   * Returns false only for a protocol violation, in which case the stream has already
   * been ended and the caller answers `INVALID_MESSAGE`. Ordinary loss returns true:
   * the frame is dropped, a keyframe is requested, and the stream keeps running.
   */
  handleFrame(input: {
    deviceId: string;
    streamId: string;
    sequence: number;
    chunkIndex: number;
    chunkCount: number;
    keyFrame: boolean;
    timestampUs: number;
    data: Buffer;
    now: number;
  }): boolean {
    const stream = this.lookup(input.deviceId, input.streamId);
    if (stream === undefined) {
      return false;
    }
    if (stream.state !== 'streaming') {
      this.abort(stream, 'projection_stopped', 'Frame vor screen.started');
      return false;
    }
    if (
      input.chunkCount < 1 ||
      input.chunkIndex < 0 ||
      input.chunkIndex >= input.chunkCount ||
      input.data.length > SCREEN_CHUNK_BYTES
    ) {
      this.abort(stream, 'encoder_error', 'Chunk verletzt die Groessen- oder Indexregeln');
      return false;
    }

    if (input.chunkIndex === 0) {
      if (input.sequence <= stream.lastSequenceStarted) {
        // Protocol section 8.5.7: repeated or backwards is not loss, it is a broken
        // counterpart, and replaying an old frame is how a stale picture gets sold
        // as a current one.
        this.abort(stream, 'encoder_error', 'Frame-Sequenz wiederholt sich oder laeuft rueckwaerts');
        return false;
      }
      if (stream.partial !== undefined) {
        this.dropPartial(stream, 'Vorheriger Frame blieb unvollstaendig');
      }
      stream.lastSequenceStarted = input.sequence;

      if (input.chunkCount * SCREEN_CHUNK_BYTES > SCREEN_MAX_FRAME_BYTES) {
        // Refused before a single byte is held: the declared size alone is enough to
        // know this frame must not be reassembled.
        this.acknowledge(stream, input.sequence);
        this.requireKeyframe(stream, input.keyFrame);
        stream.lastProgressAt = input.now;
        return true;
      }

      stream.partial = {
        sequence: input.sequence,
        chunkCount: input.chunkCount,
        keyFrame: input.keyFrame,
        timestampUs: input.timestampUs,
        parts: [input.data],
        receivedBytes: input.data.length,
      };
    } else {
      const partial = stream.partial;
      if (
        partial === undefined ||
        partial.sequence !== input.sequence ||
        partial.chunkCount !== input.chunkCount ||
        partial.parts.length !== input.chunkIndex
      ) {
        // A chunk that does not continue the frame in progress. Treated as loss and
        // not as an attack: dropping costs one frame, ending the stream costs the
        // whole session.
        if (partial !== undefined) {
          this.dropPartial(stream, 'Chunk passt nicht zum laufenden Frame');
        }
        return true;
      }
      partial.parts.push(input.data);
      partial.receivedBytes += input.data.length;
      if (partial.receivedBytes > SCREEN_MAX_FRAME_BYTES) {
        this.dropPartial(stream, 'Frame ueberschreitet SCREEN_MAX_FRAME_BYTES');
        return true;
      }
    }

    stream.lastProgressAt = input.now;

    const partial = stream.partial;
    if (partial === undefined || partial.parts.length < partial.chunkCount) {
      return true;
    }

    stream.partial = undefined;
    if (partial.keyFrame) {
      stream.needsKeyframe = false;
    }
    if (partial.keyFrame || !stream.needsKeyframe) {
      stream.channel.frame({
        sequence: partial.sequence,
        keyFrame: partial.keyFrame,
        timestampUs: partial.timestampUs,
        data: Buffer.concat(partial.parts, partial.receivedBytes),
      });
    }
    // Acknowledged whether or not it was delivered: an unacknowledged sequence would
    // keep a slot of the device's window occupied forever.
    this.acknowledge(stream, partial.sequence);
    return true;
  }

  /**
   * Asks the device for a keyframe on behalf of a session.
   *
   * The caller only knows its session id; a session carries at most
   * `SCREEN_MAX_CONCURRENT_STREAMS` streams, so it never has to juggle stream ids.
   */
  requestKeyframeForSession(sessionId: string): boolean {
    let requested = false;
    for (const stream of this.byStream.values()) {
      if (stream.sessionId === sessionId && this.requestKeyframe(stream.streamId)) {
        requested = true;
      }
    }
    return requested;
  }

  /** Asks the device for a keyframe, for example because a viewer just attached. */
  requestKeyframe(streamId: string, deviceId?: string): boolean {
    const stream =
      deviceId === undefined ? this.byStream.get(streamId) : this.lookup(deviceId, streamId);
    if (stream === undefined || stream.state !== 'streaming') {
      return false;
    }
    stream.channel.sendToDevice('screen.keyframe.request', { streamId: stream.streamId });
    return true;
  }

  /** The device ended the stream; no `screen.stop` is echoed back at it. */
  stopFromDevice(deviceId: string, streamId: string, reason: ScreenStopReason): boolean {
    const stream = this.lookup(deviceId, streamId);
    if (stream === undefined) {
      return false;
    }
    this.finish(stream, reason, 'Geraet hat den Strom beendet', false);
    return true;
  }

  /** Ends a stream from the server side and tells the device to stop capturing. */
  stop(streamId: string, reason: ScreenStopReason, message: string): boolean {
    const stream = this.byStream.get(streamId);
    if (stream === undefined) {
      return false;
    }
    this.abort(stream, reason, message);
    return true;
  }

  stopForSession(sessionId: string, reason: ScreenStopReason, message: string): number {
    return this.stopWhere((stream) => stream.sessionId === sessionId, reason, message);
  }

  stopForDevice(deviceId: string, reason: ScreenStopReason, message: string): number {
    return this.stopWhere((stream) => stream.deviceId === deviceId, reason, message);
  }

  /**
   * Ends streams that stalled.
   *
   * Two different deadlines, because "the owner has not answered yet" and "frames
   * stopped arriving" are different situations: waiting a minute for a person is
   * normal, fifteen seconds without a frame from a running encoder is not.
   */
  sweep(
    now: number,
    idleTimeoutMs: number = SCREEN_STREAM_IDLE_TIMEOUT_MS,
    consentTimeoutMs: number = SCREEN_CONSENT_TIMEOUT_MS,
  ): number {
    let ended = 0;
    for (const stream of [...this.byStream.values()]) {
      if (stream.state === 'streaming') {
        if (now - stream.lastProgressAt >= idleTimeoutMs) {
          this.abort(stream, 'timeout', 'Es kamen keine Frames mehr an');
          ended += 1;
        }
      } else if (now - stream.startedAt >= consentTimeoutMs) {
        this.abort(stream, 'consent_timeout', 'Am Geraet wurde nicht rechtzeitig zugestimmt');
        ended += 1;
      }
    }
    return ended;
  }

  activeCountForDevice(deviceId: string): number {
    let count = 0;
    for (const stream of this.byStream.values()) {
      if (stream.deviceId === deviceId) {
        count += 1;
      }
    }
    return count;
  }

  get size(): number {
    return this.byStream.size;
  }

  private acknowledge(stream: ActiveStream, sequence: number): void {
    stream.channel.sendToDevice('screen.frame.ack', { streamId: stream.streamId, sequence });
  }

  /**
   * Marks the picture broken and asks for a keyframe.
   *
   * Asked again when the dropped frame was itself a keyframe: otherwise a keyframe
   * that is too large or arrives torn would leave the stream permanently waiting for
   * one it already asked for.
   */
  private requireKeyframe(stream: ActiveStream, droppedWasKeyframe: boolean): void {
    const wasNeeded = stream.needsKeyframe;
    stream.needsKeyframe = true;
    if (!wasNeeded || droppedWasKeyframe) {
      stream.channel.sendToDevice('screen.keyframe.request', { streamId: stream.streamId });
    }
  }

  private dropPartial(stream: ActiveStream, _why: string): void {
    const partial = stream.partial;
    if (partial === undefined) {
      return;
    }
    stream.partial = undefined;
    this.acknowledge(stream, partial.sequence);
    this.requireKeyframe(stream, partial.keyFrame);
  }

  private stopWhere(
    predicate: (stream: ActiveStream) => boolean,
    reason: ScreenStopReason,
    message: string,
  ): number {
    let ended = 0;
    for (const stream of [...this.byStream.values()]) {
      if (predicate(stream)) {
        this.abort(stream, reason, message);
        ended += 1;
      }
    }
    return ended;
  }

  private lookup(deviceId: string, streamId: string): ActiveStream | undefined {
    const stream = this.byStream.get(streamId);
    // A stream id from another device is unknown rather than forbidden: the answer
    // must not reveal that the id exists at all.
    return stream !== undefined && stream.deviceId === deviceId ? stream : undefined;
  }

  private abort(stream: ActiveStream, reason: ScreenStopReason, message: string): void {
    this.finish(stream, reason, message, true);
  }

  private finish(
    stream: ActiveStream,
    reason: ScreenStopReason,
    message: string,
    tellDevice: boolean,
  ): void {
    if (stream.done) {
      return;
    }
    stream.done = true;
    stream.partial = undefined;
    this.byStream.delete(stream.streamId);
    if (tellDevice) {
      stream.channel.sendToDevice('screen.stop', { streamId: stream.streamId, reason });
    }
    stream.channel.end(reason, message);
  }
}
