/**
 * UI层：Spicetify 菜单项注册（仅展示与交互，逻辑走 config / 回调）
 * 依赖 services/spicetifyService（统一入口），不直接触碰 Spicetify 全局对象
 */
import { registerMenuItem } from '../services/spicetifyService.js';
import { logger } from '../utils/logger.js';

/**
 * 注册「繁体→简体」开关菜单
 * @param {{ get: Function, set: Function }} config
 * @param {(enabled: boolean) => void} onToggle 开关变化回调（由 api 层决定启停观察器）
 * @returns {boolean} 注册成功与否（失败时静默降级为无菜单，功能不受影响）
 */
export function registerConversionMenu(config, onToggle) {
  const registered = registerMenuItem({
    name: '繁体→简体转换',
    isEnabled: () => config.get('enabled'),
    onClick: (item) => {
      const next = !config.get('enabled');
      config.set('enabled', next);
      item?.setState?.(next);
      onToggle(next);
    },
  });
  if (!registered) {
    logger.warn('Spicetify 菜单不可用，跳过菜单注册（转换功能不受影响）');
  }
  return registered;
}
