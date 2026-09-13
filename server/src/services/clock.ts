/** Injectable clock so expiry and skew logic can be tested deterministically. */
export interface Clock {
  now(): number;
}

export const systemClock: Clock = {
  now(): number {
    return Date.now();
  },
};

/** Test/helper clock with a settable current time. */
export class MutableClock implements Clock {
  private current: number;

  constructor(start: number = Date.now()) {
    this.current = start;
  }

  now(): number {
    return this.current;
  }

  set(value: number): void {
    this.current = value;
  }

  advance(milliseconds: number): void {
    this.current += milliseconds;
  }
}
