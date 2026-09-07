/**
 * ServiceIntegrationSkill：opencc-js 繁简字典服务（外部依赖封装，可替换）
 * 规则：必须支持多方案切换；必须有 fallback（转换器不可用时返回原文）
 */
import { Converter } from 'opencc-js/t2cn';
import { logger } from '../utils/logger.js';
import { AppError } from '../utils/errors.js';

/** 配置方案 → opencc-js 参数映射 */
const PROFILE_OPTIONS = Object.freeze({
  t2s: { from: 't', to: 'cn' },      // 通用繁体 → 简体
  tw2sp: { from: 'twp', to: 'cn' },  // 台湾繁体（含词组） → 简体
  hk2s: { from: 'hk', to: 'cn' },    // 香港繁体 → 简体
});

const converterCache = new Map();

/**
 * 获取指定方案的同步转换函数
 * @param {string} profile t2s | tw2sp | hk2s
 * @returns {((text: string) => string) | null} 转换函数；不可用时返回 null（降级信号）
 */
export function getConverter(profile = 't2s') {
  const options = PROFILE_OPTIONS[profile];
  if (!options) {
    logger.warn(`未知转换方案: ${profile}，回退 t2s`);
    profile = 't2s';
  }
  if (!converterCache.has(profile)) {
    try {
      converterCache.set(profile, Converter(PROFILE_OPTIONS[profile]));
    } catch (err) {
      throw new AppError(
        'CONVERTER_INIT_FAILED',
        `转换器初始化失败 (${profile}): ${err instanceof Error ? err.message : String(err)}`,
        null,
      );
    }
  }
  return converterCache.get(profile);
}

/** 创建转换服务（供注入 core 使用） */
export function createOpenCCService() {
  return {
    /**
     * 转换文本；任何失败降级返回原文
     * @param {string} text
     * @param {string} profile
     * @returns {string}
     */
    convert(text, profile) {
      try {
        const converter = getConverter(profile);
        if (!converter) return text;
        return converter(text);
      } catch (err) {
        logger.error(`[${err.code ?? 'CONVERT_FAILED'}] ${err.message ?? err}`);
        return text; // 降级：返回原文
      }
    },
  };
}
