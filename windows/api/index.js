/**
 * API统一调度层（扩展入口 / 组装根）
 * 职责：装配 core / modules / services / config / ui（单向依赖，禁止反向）
 * 降级规则：任何一层初始化失败只影响该层，转换主链路优先保活
 */
import { createConfig } from '../config/config.js';
import { createOpenCCService } from '../services/openccService.js';
import { createTextConversion } from '../core/textConversion.js';
import { createDomObserver } from '../modules/domObserver.js';
import { registerConversionMenu } from '../ui/menu.js';
import { logger, setDebugEnabled } from '../utils/logger.js';
import { safe } from '../utils/errors.js';

let domObserver = null;

/** 装配并启动（每一步独立降级） */
function boot() {
  const config = createConfig();
  setDebugEnabled(config.get('debug'));

  // services → core 注入
  const conversion = createTextConversion(createOpenCCService());

  // core + config → modules 注入
  domObserver = createDomObserver({ textConversion: conversion, config });

  if (config.get('enabled')) {
    domObserver.start();
  }

  // ui（菜单不可用时静默降级）
  safe(
    () => registerConversionMenu(config, (enabled) => {
      if (enabled) {
        domObserver?.start();
      } else {
        domObserver?.stop();
      }
    }),
    false,
    'MENU_REGISTER_FAILED',
  );

  logger.info('扩展已启动', config.getAll());
}

/**
 * Spicetify 扩展入口（经典 script IIFE 模式，参考官方扩展惯例）
 * 菜单注册依赖 Spicetify 就绪；转换核心不依赖。等待就绪后启动，超时后仍启动（菜单静默降级）。
 */
(function main(retries = 100) {
  if (typeof Spicetify === 'undefined' || !Spicetify?.Platform) {
    if (retries > 0) {
      setTimeout(() => main(retries - 1), 300);
    } else {
      safe(boot, null, 'BOOT_TIMEOUT_FALLBACK'); // 超时：仍启动核心转换（菜单降级）
    }
    return;
  }
  safe(boot, null, 'BOOT_FAILED');
})();
