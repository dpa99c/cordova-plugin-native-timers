export interface NativeTimerEvent {
  id: string;
  label: string;
  dueAt: number;
  firedAt: number;
  lateMs: number;
}

export interface NativeTimerError extends Error {
  code: string;
}

export interface NativeTimerOptions {
  label?: string;
  onError?: (error: NativeTimerError) => void;
}

export interface NativeTimeoutOptions extends NativeTimerOptions {
  wakeLock?: boolean;
}

export interface NativeTimersPlugin {
  setTimeout(
    callback: (event: NativeTimerEvent) => void,
    delayMs: number,
    options?: NativeTimeoutOptions
  ): string;
  setInterval(
    callback: (event: NativeTimerEvent) => void,
    intervalMs: number,
    options?: NativeTimerOptions
  ): string;
  clearTimeout(id: string): void;
  clearInterval(id: string): void;
  clearAll(): void;
}

declare global {
  interface CordovaPlugins {
    nativeTimers: NativeTimersPlugin;
  }
}

export {};
