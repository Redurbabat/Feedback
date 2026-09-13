import { describe, expect, it } from 'vitest';

import { FeedbackApi } from './api.ts';

describe('FeedbackApi endpoint policy', () => {
  it('accepts same-origin mode and HTTPS endpoints', () => {
    expect(() => new FeedbackApi('')).not.toThrow();
    expect(() => new FeedbackApi('https://feedback.example.com')).not.toThrow();
    expect(() => new FeedbackApi('https://feedback.example.com/')).not.toThrow();
  });

  it('allows cleartext only for local development hosts', () => {
    expect(() => new FeedbackApi('http://localhost:8787')).not.toThrow();
    expect(() => new FeedbackApi('http://127.0.0.1:8787')).not.toThrow();
    expect(() => new FeedbackApi('http://feedback.example.com')).toThrow(/HTTPS/);
  });

  it('rejects embedded credentials, query strings and fragments', () => {
    expect(() => new FeedbackApi('https://user:pass@feedback.example.com')).toThrow();
    expect(() => new FeedbackApi('https://feedback.example.com?token=secret')).toThrow();
    expect(() => new FeedbackApi('https://feedback.example.com/#fragment')).toThrow();
  });
});
