import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    include: ['test/**/*.test.ts'],
    environment: 'node',
    // Each suite owns an in-memory database; running the files sequentially
    // keeps the rate limiter and clock assertions deterministic.
    pool: 'forks',
    fileParallelism: false,
    hookTimeout: 30_000,
    testTimeout: 30_000,
  },
});
