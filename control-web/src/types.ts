export interface UserView {
  id: string;
  email: string;
  displayName: string | null;
}

export interface SessionView {
  user: UserView;
  csrfToken: string;
  expiresAt: string;
}

export interface PairingSummary {
  pairingId: string;
  status: string;
  device: {
    deviceId: string;
    fingerprint: string;
    deviceName: string;
    platform: string;
    osVersion: string;
    sdkInt: number | null;
    appVersion: string;
  };
  knownDevice: boolean;
  createdAt: string;
  expiresAt: string;
}

export interface DeviceView {
  id: string;
  deviceId: string;
  fingerprint: string;
  name: string;
  platform: string;
  osVersion: string;
  sdkInt: number | null;
  appVersion: string;
  pairedAt: string;
  updatedAt: string;
  lastSeenAt: string | null;
  revokedAt: string | null;
  online: boolean;
  serverGrantedCapabilities: string[];
}

export interface DeviceListResponse {
  devices: DeviceView[];
  serverTime: string;
}

export interface SystemInfoView {
  manufacturer: string;
  model: string;
  osVersion: string;
  sdkInt: number;
  appVersion: string;
  batteryPercent: number;
  charging: boolean;
  storageTotalBytes: number;
  storageFreeBytes: number;
  networkType: string;
  deviceTime: string;
  lastAgentActivity: string;
}

export interface SystemInfoResponse {
  sessionId: string;
  systemInfo: SystemInfoView;
  serverTime: string;
}

export interface AuditEventView {
  id: string;
  sessionId: string | null;
  eventType: string;
  result: string;
  detail: unknown;
  createdAt: string;
}

export interface AuditResponse {
  events: AuditEventView[];
}

export type ShareCapability = 'files.read' | 'media.photos.read' | 'media.videos.read';

export interface FileShareView {
  shareId: string;
  displayName: string;
  kind: 'tree' | 'file' | 'collection';
  /** Exactly one capability governs an area (protocol section 8.3.1). */
  capability: ShareCapability;
  addedAt: string;
}

export interface FileEntryView {
  id: string;
  name: string;
  mimeType: string | null;
  size: number | null;
  modifiedAt: string | null;
  kind: 'file' | 'directory';
}

export interface FilesSessionView {
  sessionId: string;
  expiresAt: string;
  /** What the server actually granted, which may be less than was asked for. */
  capabilities: ShareCapability[];
  maxDownloadBytes: number;
}

export interface FilesSharesResponse {
  shares: FileShareView[];
}

export interface FilesEntriesResponse {
  shareId: string;
  entries: FileEntryView[];
  nextCursor?: string | null;
}

// ------------------------------------------------------------------ screen.view

export type ScreenStatusState = 'pending' | 'granted' | 'declined';

export type ScreenStopReason =
  | 'owner_stopped'
  | 'client_cancelled'
  | 'session_expired'
  | 'capability_revoked'
  | 'device_revoked'
  | 'consent_declined'
  | 'consent_timeout'
  | 'projection_stopped'
  | 'encoder_error'
  | 'timeout'
  | 'connection_lost';

export interface ScreenSessionView {
  sessionId: string;
  expiresAt: string;
  maxWidth: number;
  maxHeight: number;
  maxFps: number;
  maxBitrateKbps: number;
}

export interface ScreenConfigView {
  width: number;
  height: number;
  /** Read out of the device encoder's own SPS, e.g. `avc1.42E01E`. */
  codec: string;
  fps: number;
  /** base64 SPS/PPS in Annex-B. */
  config: string;
}

export interface ScreenEndView {
  reason: ScreenStopReason;
}

/** One interpreted event from the SSE stream. */
export type ScreenEvent =
  | { kind: 'open' }
  | { kind: 'status'; state: ScreenStatusState }
  | { kind: 'config'; config: ScreenConfigView }
  | {
      kind: 'frame';
      sequence: number;
      keyFrame: boolean;
      timestampUs: number;
      data: Uint8Array;
    }
  | { kind: 'end'; end: ScreenEndView };
