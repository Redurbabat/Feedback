import { SseParser, interpretScreenEvent } from './screen.ts';
import type {
  AuditResponse,
  DeviceListResponse,
  DeviceView,
  PairingSummary,
  SessionView,
  FilesEntriesResponse,
  FilesSessionView,
  FilesSharesResponse,
  SystemInfoResponse,
  ScreenEvent,
  ScreenSessionView,
} from './types.ts';

interface ProtocolErrorBody {
  error?: {
    code?: unknown;
    message?: unknown;
  };
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string | null,
    message: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

export class FeedbackApi {
  private csrfToken: string | null = null;
  private readonly base: string;

  constructor(baseUrl = import.meta.env.VITE_FEEDBACK_API_BASE_URL ?? '') {
    this.base = normalizeBase(baseUrl);
  }

  setCsrfToken(value: string | null): void {
    this.csrfToken = value;
  }

  async restoreSession(): Promise<SessionView> {
    const session = await this.request<SessionView>('/auth/session', { method: 'GET' });
    this.csrfToken = session.csrfToken;
    return session;
  }

  async login(email: string, password: string): Promise<SessionView> {
    const session = await this.request<SessionView>('/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    });
    this.csrfToken = session.csrfToken;
    return session;
  }

  /**
   * Confirms the password for the short window in which a capability may be granted
   * (THREAT_MODEL 4.5). The password is passed through and never stored on this side.
   */
  async reauthenticate(password: string): Promise<{ elevatedUntil: string }> {
    return this.request<{ elevatedUntil: string }>(
      '/auth/reauthenticate',
      { method: 'POST', body: JSON.stringify({ password }) },
      true,
    );
  }

  async logout(): Promise<void> {
    await this.request<{ status: string }>(
      '/auth/logout',
      { method: 'POST', body: '{}' },
      true,
    );
    this.csrfToken = null;
  }

  async lookupPairing(displayCode: string): Promise<PairingSummary> {
    return this.request<PairingSummary>(
      '/pairing/lookup',
      { method: 'POST', body: JSON.stringify({ displayCode }) },
      true,
    );
  }

  async approvePairing(pairingId: string, displayCode: string): Promise<unknown> {
    return this.request<unknown>(
      `/pairing/${encodeURIComponent(pairingId)}/approve`,
      { method: 'POST', body: JSON.stringify({ displayCode }) },
      true,
    );
  }

  async rejectPairing(pairingId: string, displayCode: string): Promise<unknown> {
    return this.request<unknown>(
      `/pairing/${encodeURIComponent(pairingId)}/reject`,
      { method: 'POST', body: JSON.stringify({ displayCode }) },
      true,
    );
  }

  async listDevices(): Promise<DeviceListResponse> {
    return this.request<DeviceListResponse>('/devices', { method: 'GET' });
  }

  async getDevice(id: string): Promise<DeviceView> {
    return this.request<DeviceView>(`/devices/${encodeURIComponent(id)}`, { method: 'GET' });
  }

  async setCapabilities(id: string, grantedCapabilities: string[]): Promise<unknown> {
    return this.request<unknown>(
      `/devices/${encodeURIComponent(id)}/capabilities`,
      {
        method: 'PUT',
        body: JSON.stringify({ grantedCapabilities }),
      },
      true,
    );
  }

  async systemInfo(id: string): Promise<SystemInfoResponse> {
    return this.request<SystemInfoResponse>(
      `/devices/${encodeURIComponent(id)}/system-info`,
      { method: 'POST', body: '{}' },
      true,
    );
  }

  async revokeDevice(id: string): Promise<unknown> {
    return this.request<unknown>(
      `/devices/${encodeURIComponent(id)}/revoke`,
      { method: 'POST', body: '{}' },
      true,
    );
  }

  async audit(id: string, limit = 50): Promise<AuditResponse> {
    const safeLimit = Math.max(1, Math.min(200, Math.trunc(limit)));
    return this.request<AuditResponse>(
      `/devices/${encodeURIComponent(id)}/audit?limit=${safeLimit}`,
      { method: 'GET' },
    );
  }

  // ------------------------------------------------------------- files.read

  async openFilesSession(deviceId: string): Promise<FilesSessionView> {
    return this.request<FilesSessionView>(
      `/devices/${encodeURIComponent(deviceId)}/files/session`,
      { method: 'POST', body: '{}' },
      true,
    );
  }

  async closeFilesSession(deviceId: string, sessionId: string): Promise<unknown> {
    return this.request<unknown>(
      `/devices/${encodeURIComponent(deviceId)}/files/session`,
      { method: 'DELETE', body: JSON.stringify({ sessionId }) },
      true,
    );
  }

  async filesShares(deviceId: string, sessionId: string): Promise<FilesSharesResponse> {
    const query = new URLSearchParams({ sessionId });
    return this.request<FilesSharesResponse>(
      `/devices/${encodeURIComponent(deviceId)}/files/shares?${query.toString()}`,
      { method: 'GET' },
    );
  }

  async filesEntries(
    deviceId: string,
    params: {
      sessionId: string;
      shareId: string;
      directoryId?: string | null;
      cursor?: string | null;
    },
  ): Promise<FilesEntriesResponse> {
    const query = new URLSearchParams({
      sessionId: params.sessionId,
      shareId: params.shareId,
    });
    if (params.directoryId !== undefined && params.directoryId !== null) {
      query.set('directoryId', params.directoryId);
    }
    if (params.cursor !== undefined && params.cursor !== null) {
      query.set('cursor', params.cursor);
    }
    return this.request<FilesEntriesResponse>(
      `/devices/${encodeURIComponent(deviceId)}/files/entries?${query.toString()}`,
      { method: 'GET' },
    );
  }

  /**
   * Downloads one file, reporting progress as the bytes arrive.
   *
   * The body is read as a stream rather than with response.blob() so a running
   * download can be cancelled and so the user sees movement instead of a frozen
   * button. The assembled Blob still lives in memory - the browser cannot stream
   * to disk without the File System Access API - which is why the size limit
   * matters on this side too.
   */
  async downloadFile(
    deviceId: string,
    params: { sessionId: string; shareId: string; fileId: string },
    options: {
      signal?: AbortSignal;
      onProgress?: (received: number, total: number | null) => void;
    } = {},
  ): Promise<Blob> {
    const query = new URLSearchParams(params);
    let response: Response;
    try {
      response = await fetch(
        `${this.base}/api/v1/devices/${encodeURIComponent(deviceId)}/files/content?${query.toString()}`,
        {
          method: 'GET',
          credentials: 'include',
          cache: 'no-store',
          headers: { Accept: 'application/octet-stream' },
          ...(options.signal === undefined ? {} : { signal: options.signal }),
        },
      );
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') {
        throw error;
      }
      throw new ApiError(0, null, 'Der Feedback-Server ist nicht erreichbar.');
    }

    if (!response.ok) {
      throw await errorFromResponse(response);
    }

    const lengthHeader = response.headers.get('content-length');
    const total = lengthHeader === null ? null : Number.parseInt(lengthHeader, 10);
    const expected = total !== null && Number.isFinite(total) ? total : null;
    const type = response.headers.get('content-type') ?? 'application/octet-stream';

    const body = response.body;
    if (body === null) {
      return response.blob();
    }

    const reader = body.getReader();
    const chunks: Uint8Array[] = [];
    let received = 0;
    for (;;) {
      const step = await reader.read();
      if (step.done) {
        break;
      }
      if (step.value !== undefined) {
        chunks.push(step.value);
        received += step.value.byteLength;
        options.onProgress?.(received, expected);
      }
    }

    // The server destroys the response when a digest or length does not match, which
    // usually surfaces as a read error above. When it does not, a short body must
    // still not be handed over as if it were the whole file.
    if (expected !== null && received !== expected) {
      throw new ApiError(0, null, 'Die Übertragung wurde unvollständig beendet.');
    }

    return new Blob(chunks as BlobPart[], { type });
  }

  // ------------------------------------------------------------ screen.view

  async openScreenSession(deviceId: string): Promise<ScreenSessionView> {
    return this.request<ScreenSessionView>(
      `/devices/${encodeURIComponent(deviceId)}/screen/session`,
      { method: 'POST', body: '{}' },
      true,
    );
  }

  async closeScreenSession(deviceId: string, sessionId: string): Promise<unknown> {
    return this.request<unknown>(
      `/devices/${encodeURIComponent(deviceId)}/screen/session`,
      { method: 'DELETE', body: JSON.stringify({ sessionId }) },
      true,
    );
  }

  async requestScreenKeyframe(deviceId: string, sessionId: string): Promise<unknown> {
    return this.request<unknown>(
      `/devices/${encodeURIComponent(deviceId)}/screen/keyframe`,
      { method: 'POST', body: JSON.stringify({ sessionId }) },
      true,
    );
  }

  /**
   * Consumes the screen stream until it ends or the caller aborts.
   *
   * `fetch` rather than `EventSource`, for three reasons: EventSource cannot be
   * aborted properly, it reconnects on its own (which would silently ask the owner
   * for consent again), and it cannot carry credentials to another origin. Reading
   * the body as a stream also means the parser sees exactly what the network
   * delivered, boundaries and all.
   */
  async streamScreen(
    deviceId: string,
    sessionId: string,
    onEvent: (event: ScreenEvent) => void,
    signal?: AbortSignal,
  ): Promise<void> {
    const query = new URLSearchParams({ sessionId });
    let response: Response;
    try {
      response = await fetch(
        `${this.base}/api/v1/devices/${encodeURIComponent(deviceId)}/screen/stream?${query.toString()}`,
        {
          method: 'GET',
          credentials: 'include',
          cache: 'no-store',
          headers: { Accept: 'text/event-stream' },
          ...(signal === undefined ? {} : { signal }),
        },
      );
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') {
        throw error;
      }
      throw new ApiError(0, null, 'Der Feedback-Server ist nicht erreichbar.');
    }

    if (!response.ok) {
      throw await errorFromResponse(response);
    }
    const body = response.body;
    if (body === null) {
      throw new ApiError(0, null, 'Der Bildstrom kam ohne Inhalt an.');
    }

    const reader = body.getReader();
    const decoder = new TextDecoder();
    const parser = new SseParser();
    try {
      for (;;) {
        const step = await reader.read();
        if (step.done) {
          break;
        }
        if (step.value === undefined) {
          continue;
        }
        // `stream: true` matters: a multi-byte character can straddle two chunks, and
        // so can an event boundary.
        for (const message of parser.push(decoder.decode(step.value, { stream: true }))) {
          const event = interpretScreenEvent(message);
          if (event !== null) {
            onEvent(event);
          }
        }
      }
    } finally {
      reader.cancel().catch(() => undefined);
    }
  }

  private async request<T>(path: string, init: RequestInit, csrf = false): Promise<T> {
    const headers = new Headers(init.headers);
    headers.set('Accept', 'application/json');
    if (init.body !== undefined) {
      headers.set('Content-Type', 'application/json');
    }
    if (csrf) {
      if (this.csrfToken === null) {
        throw new ApiError(401, 'UNAUTHORIZED', 'Keine gültige Control-Center-Sitzung.');
      }
      headers.set('X-Feedback-CSRF', this.csrfToken);
    }

    let response: Response;
    try {
      response = await fetch(`${this.base}/api/v1${path}`, {
        ...init,
        headers,
        credentials: 'include',
        cache: 'no-store',
      });
    } catch {
      throw new ApiError(0, null, 'Der Feedback-Server ist nicht erreichbar.');
    }

    const text = await response.text();
    let body: unknown = {};
    if (text.trim() !== '') {
      try {
        body = JSON.parse(text) as unknown;
      } catch {
        throw new ApiError(response.status, null, 'Der Server hat eine ungültige Antwort gesendet.');
      }
    }

    if (!response.ok) {
      const record = isRecord(body) ? (body as ProtocolErrorBody) : {};
      const code = typeof record.error?.code === 'string' ? record.error.code : null;
      const message = typeof record.error?.message === 'string'
        ? record.error.message
        : `Serveranfrage fehlgeschlagen (${response.status}).`;
      throw new ApiError(response.status, code, message);
    }

    return body as T;
  }
}

async function errorFromResponse(response: Response): Promise<ApiError> {
  let body: unknown = {};
  try {
    const text = await response.text();
    if (text.trim() !== '') {
      body = JSON.parse(text) as unknown;
    }
  } catch {
    body = {};
  }
  const record = isRecord(body) ? (body as ProtocolErrorBody) : {};
  const code = typeof record.error?.code === 'string' ? record.error.code : null;
  const message =
    typeof record.error?.message === 'string'
      ? record.error.message
      : `Serveranfrage fehlgeschlagen (${response.status}).`;
  return new ApiError(response.status, code, message);
}

function normalizeBase(value: string): string {
  const trimmed = value.trim().replace(/\/+$/, '');
  if (trimmed === '') {
    return '';
  }
  const parsed = new URL(trimmed);
  if (parsed.protocol !== 'https:' && parsed.hostname !== 'localhost' && parsed.hostname !== '127.0.0.1') {
    throw new Error('VITE_FEEDBACK_API_BASE_URL muss HTTPS verwenden.');
  }
  if (parsed.username !== '' || parsed.password !== '' || parsed.search !== '' || parsed.hash !== '') {
    throw new Error('VITE_FEEDBACK_API_BASE_URL enthält nicht unterstützte URL-Bestandteile.');
  }
  return trimmed;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}
