/**
 * ErrorHandlingSkill：错误处理
 * 规则：所有模块返回标准错误结构；任何失败不崩溃宿主（Spotify）
 */
import { logger } from './logger.js';

export class AppError extends Error {
  /**
   * @param {string} code 错误码
   * @param {string} message 错误信息
   * @param {*} fallback 降级返回值
   */
  constructor(code, message, fallback = null) {
    super(message);
    this.name = 'AppError';
    this.code = code;
    this.fallback = fallback;
  }
}

/** 将任意异常规范化为 AppError */
export function toAppError(err, code = 'UNKNOWN', fallback = null) {
  if (err instanceof AppError) return err;
  return new AppError(code, err instanceof Error ? err.message : String(err), fallback);
}

/**
 * 同步安全执行：出错时记录日志并返回 fallback，绝不抛出
 * @param {Function} fn
 * @param {*} fallback 失败时的降级返回值
 * @param {string} code 错误码
 */
export function safe(fn, fallback, code = 'SAFE_CALL_FAILED') {
  try {
    return fn();
  } catch (err) {
    const appErr = toAppError(err, code, fallback);
    logger.error(`[${appErr.code}] ${appErr.message}`);
    return appErr.fallback;
  }
}

/**
 * 异步安全执行：出错时记录日志并返回 fallback，绝不抛出
 */
export async function safeAsync(fn, fallback, code = 'SAFE_CALL_FAILED') {
  try {
    return await fn();
  } catch (err) {
    const appErr = toAppError(err, code, fallback);
    logger.error(`[${appErr.code}] ${appErr.message}`);
    return appErr.fallback;
  }
}
