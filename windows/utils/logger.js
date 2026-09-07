/**
 * LoggingSkill：日志与追踪
 * 规则：默认静默，debug 模式开启；所有关键行为可记录
 */
let debugEnabled = false;

export function setDebugEnabled(enabled) {
  debugEnabled = Boolean(enabled);
}

export const logger = {
  debug(...args) {
    if (debugEnabled) console.debug('[t2s]', ...args);
  },
  info(...args) {
    console.info('[t2s]', ...args);
  },
  warn(...args) {
    console.warn('[t2s]', ...args);
  },
  error(...args) {
    console.error('[t2s]', ...args);
  },
};
