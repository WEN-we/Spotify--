/**
 * TextConversionSkill（core）：繁→简转换核心
 * 规则：纯函数、不碰 DOM、可单元测试；转换服务通过构造注入（core 不依赖 services 层）；
 *       转换异常时返回原文（降级）
 */

/** CJK 统一表意文字（含扩展A区、兼容表意区） */
const CJK_REGEX = /[\u3400-\u4DBF\u4E00-\u9FFF\uF900-\uFAFF]/;

/** 是否包含中文字符 */
export function hasCJK(text) {
  return typeof text === 'string' && CJK_REGEX.test(text);
}

/**
 * 创建文本转换核心
 * @param {{ convert: (text: string, profile: string) => string }} convertService 转换服务（由 api 层注入）
 */
export function createTextConversion(convertService) {
  /**
   * 繁体 → 简体
   * @param {string} text 原文本
   * @param {string} [profile] 转换方案
   * @returns {string} 转换后文本；无中文/服务异常时原样返回
   */
  function toSimplified(text, profile = 't2s') {
    if (!hasCJK(text)) return text;
    try {
      return convertService.convert(text, profile);
    } catch {
      return text; // 降级：任何异常返回原文
    }
  }

  /**
   * 是否包含繁体字（有中文且转换后发生变化）
   * @param {string} text
   * @param {string} [profile]
   * @returns {boolean}
   */
  function containsTraditional(text, profile = 't2s') {
    if (!hasCJK(text)) return false;
    return toSimplified(text, profile) !== text;
  }

  return { toSimplified, containsTraditional };
}
