/**
 * ConfigManagementSkill：配置管理
 * 规则：不写死参数；支持热更新（localStorage 持久化，可注入存储实现以便测试）
 */

const STORAGE_PREFIX = 't2s:';

/** 默认配置（冻结，防止意外修改） */
export const DEFAULT_CONFIG = Object.freeze({
  /** 总开关：是否启用繁→简转换 */
  enabled: true,
  /** 转换方案：t2s 通用繁体 | tw2sp 台湾繁体（含词组） | hk2s 香港繁体 */
  profile: 't2s',
  /** 调试日志开关 */
  debug: false,
});

/** 合法转换方案列表 */
export const VALID_PROFILES = Object.freeze(['t2s', 'tw2sp', 'hk2s']);

/**
 * 创建配置实例
 * @param {{getItem: Function, setItem: Function}} [storage] 存储实现，默认 localStorage（不存在则退化为内存）
 */
export function createConfig(storage) {
  const store = storage
    ?? (typeof localStorage !== 'undefined' ? localStorage : new Map());

  const readKey = (key) => {
    try {
      return store.getItem(STORAGE_PREFIX + key);
    } catch {
      return null;
    }
  };

  const writeKey = (key, value) => {
    try {
      store.setItem(STORAGE_PREFIX + key, String(value));
    } catch {
      /* 存储不可用时静默降级为仅内存 */
    }
  };

  return {
    /**
     * 读取配置项（带类型与合法性校验，非法值回退默认）
     * @param {string} key
     */
    get(key) {
      const raw = readKey(key);
      if (raw === null) return DEFAULT_CONFIG[key];
      const defaultValue = DEFAULT_CONFIG[key];
      if (typeof defaultValue === 'boolean') return raw === 'true';
      if (VALID_PROFILES.includes(defaultValue)) {
        return VALID_PROFILES.includes(raw) ? raw : defaultValue;
      }
      return raw;
    },

    /**
     * 写入配置项（热更新：立即生效，无需重启）
     * @param {string} key
     * @param {*} value
     */
    set(key, value) {
      writeKey(key, value);
    },

    /** 读取全部配置 */
    getAll() {
      return Object.fromEntries(
        Object.keys(DEFAULT_CONFIG).map((key) => [key, this.get(key)]),
      );
    },
  };
}
