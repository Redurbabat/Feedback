import type {
  AuditResponse,
  DeviceListResponse,
  DeviceView,
  PairingSummary,
  SessionView,
  SystemInfoResponse,
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
