import type {
  ScreenConfigView,
  ScreenEndView,
  ScreenEvent,
  ScreenStatusState,
  ScreenStopReason,
} from './types.ts';

/**
 * Everything about the screen view that is not the DOM.
 *
 * The parser lives here rather than in the view for the usual reason, and one extra:
 * a stream of frames is the one place where a wrong byte is invisible until it is a
 * black picture, so the framing has to be tested rather than watched.
 */

/** One raw Server-Sent Event, before it is interpreted. */
export interface SseMessage {
  readonly event: string;
  readonly data: string;
}

/**
 * Incremental `text/event-stream` parser.
 *
 * Network chunks do not respect event boundaries: a single frame regularly arrives
 * split across two reads, and two small events arrive in one. Anything that assumes
 * "one chunk is one event" works perfectly on a fast local connection and falls apart
 * over mobile data.
 */
export class SseParser {
  private buffer = '';

  /** Feeds one network chunk and returns the events that are now complete. */
  push(chunk: string): SseMessage[] {
    // Normalise the three permitted line endings first, so the split below only has
    // to deal with one of them.
    this.buffer += chunk.replace(/\r\n|\r/gu, '\n');
    const messages: SseMessage[] = [];
    for (;;) {
      const end = this.buffer.indexOf('\n\n');
      if (end === -1) {
        return messages;
      }
      const block = this.buffer.slice(0, end);
      this.buffer = this.buffer.slice(end + 2);
      const message = parseBlock(block);
      if (message !== null) {
        messages.push(message);
      }
    }
  }
}

function parseBlock(block: string): SseMessage | null {
  let event = 'message';
  const data: string[] = [];
  for (const line of block.split('\n')) {
    if (line === '' || line.startsWith(':')) {
      // A comment. The server sends one every few seconds so an idle proxy does not
      // close the connection while the owner is being asked.
      continue;
    }
    const colon = line.indexOf(':');
    const field = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) {
      value = value.slice(1);
    }
    if (field === 'event') {
      event = value;
    } else if (field === 'data') {
      data.push(value);
    }
  }
  if (data.length === 0) {
    return null;
  }
  return { event, data: data.join('\n') };
}

const STATUS_STATES: readonly ScreenStatusState[] = ['pending', 'granted', 'declined'];

const STOP_REASONS: readonly ScreenStopReason[] = [
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
];

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

/**
 * Turns one raw event into a typed one, or null when it does not hold up.
 *
 * Unknown events are dropped rather than guessed at: this is a stream from a device,
 * and the viewer has no business improvising around a payload it does not recognise.
 */
export function interpretScreenEvent(message: SseMessage): ScreenEvent | null {
  let payload: unknown;
  try {
    payload = JSON.parse(message.data) as unknown;
  } catch {
    return null;
  }
  if (!isRecord(payload)) {
    return null;
  }

  if (message.event === 'open') {
    return { kind: 'open' };
  }

  if (message.event === 'status') {
    const state = payload.state;
    if (typeof state !== 'string' || !STATUS_STATES.includes(state as ScreenStatusState)) {
      return null;
    }
    return { kind: 'status', state: state as ScreenStatusState };
  }

  if (message.event === 'config') {
    const config = readConfig(payload);
    return config === null ? null : { kind: 'config', config };
  }

  if (message.event === 'frame') {
    const sequence = payload.sequence;
    const timestampUs = payload.timestampUs;
    const data = payload.data;
    if (
      typeof sequence !== 'number' ||
      !Number.isInteger(sequence) ||
      typeof timestampUs !== 'number' ||
      typeof payload.keyFrame !== 'boolean' ||
      typeof data !== 'string'
    ) {
      return null;
    }
    const bytes = decodeBase64(data);
    if (bytes === null) {
      return null;
    }
    return {
      kind: 'frame',
      sequence,
      keyFrame: payload.keyFrame,
      timestampUs,
      data: bytes,
    };
  }

  if (message.event === 'end') {
    const reason = payload.reason;
    const end: ScreenEndView = {
      reason:
        typeof reason === 'string' && STOP_REASONS.includes(reason as ScreenStopReason)
          ? (reason as ScreenStopReason)
          : 'timeout',
    };
    return { kind: 'end', end };
  }

  return null;
}

function readConfig(payload: Record<string, unknown>): ScreenConfigView | null {
  const { width, height, codec, fps, config } = payload;
  if (
    typeof width !== 'number' ||
    !Number.isInteger(width) ||
    width <= 0 ||
    typeof height !== 'number' ||
    !Number.isInteger(height) ||
    height <= 0 ||
    typeof fps !== 'number' ||
    typeof codec !== 'string' ||
    typeof config !== 'string'
  ) {
    return null;
  }
  if (!isSupportedCodec(codec)) {
    return null;
  }
  return { width, height, codec, fps, config };
}

/**
 * v1 speaks H.264 and nothing else.
 *
 * Refusing an unknown codec is better than handing it to the decoder and hoping: a
 * `VideoDecoder` configured with a string it does not understand throws, and the
 * owner would see a blank canvas with no explanation.
 */
export function isSupportedCodec(codec: string): boolean {
  return /^avc1\.[0-9A-Fa-f]{6}$/u.test(codec);
}

/** Null when the string is not valid base64, rather than a silently shortened buffer. */
export function decodeBase64(value: string): Uint8Array | null {
  if (!/^[A-Za-z0-9+/]*={0,2}$/u.test(value) || value.length % 4 !== 0) {
    return null;
  }
  try {
    const binary = atob(value);
    const bytes = new Uint8Array(binary.length);
    for (let index = 0; index < binary.length; index += 1) {
      bytes[index] = binary.charCodeAt(index);
    }
    return bytes;
  } catch {
    return null;
  }
}

/** What the viewer is told while nothing is on screen yet. */
export function statusLine(state: ScreenStatusState): string {
  switch (state) {
    case 'pending':
      return 'Warten auf die Zustimmung am Gerät. Dort werden zwei Rückfragen angezeigt.';
    case 'granted':
      return 'Zugestimmt. Der Encoder startet.';
    case 'declined':
      return 'Am Gerät abgelehnt. Es wird nichts übertragen.';
  }
}

/**
 * Why the stream ended, in words that say who decided it.
 *
 * The difference between "the owner stopped it" and "it timed out" matters: one is
 * the system working, the other is something to look into.
 */
export function stopReasonLine(reason: ScreenStopReason): string {
  switch (reason) {
    case 'owner_stopped':
      return 'Am Gerät beendet.';
    case 'client_cancelled':
      return 'Hier beendet.';
    case 'session_expired':
      return 'Die Sitzung ist abgelaufen. Für einen weiteren Blick fragt das Gerät erneut nach.';
    case 'capability_revoked':
      return 'Die Freigabe wurde entzogen.';
    case 'device_revoked':
      return 'Das Gerät wurde widerrufen.';
    case 'consent_declined':
      return 'Am Gerät abgelehnt.';
    case 'consent_timeout':
      return 'Am Gerät wurde nicht rechtzeitig geantwortet.';
    case 'projection_stopped':
      return 'Android hat die Aufnahme beendet.';
    case 'encoder_error':
      return 'Der Encoder des Geräts hat abgebrochen.';
    case 'timeout':
      return 'Es kamen keine Bilder mehr an.';
    case 'connection_lost':
      return 'Die Verbindung zum Gerät ist abgebrochen.';
  }
}

/** The one-line summary under the picture. */
export function streamSummary(config: ScreenConfigView | null, frames: number): string {
  if (config === null) {
    return 'Noch kein Bild.';
  }
  const resolution = `${config.width}×${config.height}`;
  const rate = `${config.fps} Bilder/s`;
  const counted = frames === 1 ? '1 Bild empfangen' : `${frames} Bilder empfangen`;
  return `${resolution} · ${rate} · ${config.codec} · ${counted}`;
}

/** Whether this browser can decode the stream at all. */
export function hasVideoDecoder(scope: { VideoDecoder?: unknown } = globalThis): boolean {
  return typeof scope.VideoDecoder === 'function';
}
