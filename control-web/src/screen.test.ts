import { describe, expect, it } from 'vitest';

import {
  SseParser,
  decodeBase64,
  interpretScreenEvent,
  isSupportedCodec,
  statusLine,
  stopReasonLine,
  streamSummary,
  hasVideoDecoder,
} from './screen.ts';

function frameEvent(overrides: Record<string, unknown> = {}): string {
  return JSON.stringify({
    sequence: 0,
    keyFrame: true,
    timestampUs: 0,
    data: 'AAAA',
    ...overrides,
  });
}

describe('SseParser', () => {
  it('returns nothing until an event is complete', () => {
    const parser = new SseParser();
    expect(parser.push('event: status\ndata: {"state":"pending"}')).toEqual([]);
    expect(parser.push('\n\n')).toEqual([
      { event: 'status', data: '{"state":"pending"}' },
    ]);
  });

  it('reassembles an event split across network chunks', () => {
    const parser = new SseParser();
    // This is the normal case on a real connection, not an edge case.
    expect(parser.push('event: fra')).toEqual([]);
    expect(parser.push('me\ndata: {"a":')).toEqual([]);
    expect(parser.push('1}\n\n')).toEqual([{ event: 'frame', data: '{"a":1}' }]);
  });

  it('returns several events that arrived in one chunk', () => {
    const parser = new SseParser();
    const messages = parser.push('event: a\ndata: 1\n\nevent: b\ndata: 2\n\n');
    expect(messages).toEqual([
      { event: 'a', data: '1' },
      { event: 'b', data: '2' },
    ]);
  });

  it('ignores keep-alive comments', () => {
    const parser = new SseParser();
    expect(parser.push(': ping\n\n')).toEqual([]);
    expect(parser.push('event: end\ndata: {}\n\n')).toEqual([{ event: 'end', data: '{}' }]);
  });

  it('accepts CRLF line endings', () => {
    const parser = new SseParser();
    expect(parser.push('event: status\r\ndata: 1\r\n\r\n')).toEqual([
      { event: 'status', data: '1' },
    ]);
  });

  it('joins several data lines with a newline', () => {
    const parser = new SseParser();
    expect(parser.push('data: one\ndata: two\n\n')).toEqual([
      { event: 'message', data: 'one\ntwo' },
    ]);
  });
});

describe('interpretScreenEvent', () => {
  it('reads a status', () => {
    expect(
      interpretScreenEvent({ event: 'status', data: '{"state":"granted"}' }),
    ).toEqual({ kind: 'status', state: 'granted' });
  });

  it('refuses a status it does not know', () => {
    expect(interpretScreenEvent({ event: 'status', data: '{"state":"maybe"}' })).toBeNull();
  });

  it('reads a configuration', () => {
    const event = interpretScreenEvent({
      event: 'config',
      data: JSON.stringify({
        width: 720,
        height: 1280,
        codec: 'avc1.42E01E',
        fps: 15,
        config: 'AAAAAWc=',
      }),
    });
    expect(event?.kind).toBe('config');
  });

  it('refuses a codec it cannot decode instead of trying', () => {
    const event = interpretScreenEvent({
      event: 'config',
      data: JSON.stringify({
        width: 720,
        height: 1280,
        codec: 'vp09.00.10.08',
        fps: 15,
        config: '',
      }),
    });
    // A VideoDecoder configured with a string it does not understand throws, and the
    // owner would be left looking at a blank canvas.
    expect(event).toBeNull();
  });

  it('refuses a configuration with an impossible size', () => {
    const event = interpretScreenEvent({
      event: 'config',
      data: JSON.stringify({ width: 0, height: 1280, codec: 'avc1.42E01E', fps: 15, config: '' }),
    });
    expect(event).toBeNull();
  });

  it('decodes a frame', () => {
    const event = interpretScreenEvent({ event: 'frame', data: frameEvent({ data: 'AQID' }) });
    expect(event).toMatchObject({ kind: 'frame', sequence: 0, keyFrame: true });
    expect(event?.kind === 'frame' && Array.from(event.data)).toEqual([1, 2, 3]);
  });

  it('refuses a frame whose payload is not base64', () => {
    expect(interpretScreenEvent({ event: 'frame', data: frameEvent({ data: 'not!' }) })).toBeNull();
  });

  it('refuses a frame with a missing field', () => {
    expect(
      interpretScreenEvent({ event: 'frame', data: '{"sequence":1,"data":"AAAA"}' }),
    ).toBeNull();
  });

  it('reads an end and falls back to a reason it knows', () => {
    expect(interpretScreenEvent({ event: 'end', data: '{"reason":"owner_stopped"}' })).toEqual({
      kind: 'end',
      end: { reason: 'owner_stopped' },
    });
    expect(interpretScreenEvent({ event: 'end', data: '{"reason":"weird"}' })).toEqual({
      kind: 'end',
      end: { reason: 'timeout' },
    });
  });

  it('drops an unknown event and broken JSON', () => {
    expect(interpretScreenEvent({ event: 'surprise', data: '{}' })).toBeNull();
    expect(interpretScreenEvent({ event: 'frame', data: 'not json' })).toBeNull();
  });
});

describe('decodeBase64', () => {
  it('decodes padded base64', () => {
    expect(Array.from(decodeBase64('AQID') ?? [])).toEqual([1, 2, 3]);
    expect(Array.from(decodeBase64('') ?? [])).toEqual([]);
  });

  it('refuses anything that is not base64 rather than truncating it', () => {
    expect(decodeBase64('AQI')).toBeNull();
    expect(decodeBase64('AQ!D')).toBeNull();
    expect(decodeBase64('AQID=')).toBeNull();
  });
});

describe('presentation', () => {
  it('names who decided when a stream ends', () => {
    expect(stopReasonLine('owner_stopped')).toContain('Gerät');
    expect(stopReasonLine('client_cancelled')).toContain('Hier');
    expect(stopReasonLine('consent_declined')).toContain('abgelehnt');
    expect(stopReasonLine('timeout')).not.toEqual(stopReasonLine('owner_stopped'));
  });

  it('says that the device asks twice while waiting', () => {
    expect(statusLine('pending')).toContain('zwei');
  });

  it('summarises the stream only once there is one', () => {
    expect(streamSummary(null, 0)).toBe('Noch kein Bild.');
    const summary = streamSummary(
      { width: 720, height: 1280, codec: 'avc1.42E01E', fps: 15, config: '' },
      1,
    );
    expect(summary).toContain('720×1280');
    expect(summary).toContain('1 Bild empfangen');
  });

  it('accepts only the codec strings v1 speaks', () => {
    expect(isSupportedCodec('avc1.42E01E')).toBe(true);
    expect(isSupportedCodec('avc1.64002A')).toBe(true);
    expect(isSupportedCodec('avc1.42E0')).toBe(false);
    expect(isSupportedCodec('vp8')).toBe(false);
    expect(isSupportedCodec('')).toBe(false);
  });

  it('detects a browser without WebCodecs', () => {
    expect(hasVideoDecoder({})).toBe(false);
    expect(hasVideoDecoder({ VideoDecoder: class {} })).toBe(true);
  });
});

describe('decodeBase64 canonical form', () => {
  it('refuses two spellings of the same bytes', () => {
    // atob accepts both: in a padded group the last character carries bits that must be zero,
    // and it discards them. Two wire strings meaning one frame is exactly the ambiguity the
    // server refuses on its side.
    expect(decodeBase64('5RGlCnk=')).not.toBeNull();
    expect(decodeBase64('5RGlCnn=')).toBeNull();
  });
});
