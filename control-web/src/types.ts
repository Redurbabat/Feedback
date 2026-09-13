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
