/**
 * DOMObserverSkill（modules）：DOM 观察器模块
 * 规则：可启用/禁用（start/stop）；防循环（标记自身写入）；无繁体则零开销跳过；
 *       不处理输入类元素（用户正在输入的内容不转换）
 */
import { logger } from '../utils/logger.js';
import { safe } from '../utils/errors.js';

/** 跳过转换的标签（脚本/样式/输入类，其文本节点不处理） */
const SKIP_TAGS = new Set(['SCRIPT', 'STYLE', 'NOSCRIPT', 'TEXTAREA']);

/**
 * 创建 DOM 观察器
 * @param {{ toSimplified: Function, containsTraditional: Function }} textConversion core 注入
 * @param {{ get: (key: string) => * }} config 配置注入
 */
export function createDomObserver({ textConversion, config }) {
  let observer = null;
  let scanning = false;

  /** WeakMap：textNode → 上次由本模块写入（或确认无需转换）的文本值，用于防循环 */
  const lastWritten = new WeakMap();

  /** 节点是否应跳过 */
  function shouldSkip(node) {
    const parent = node.parentElement;
    if (!parent) return true;
    if (SKIP_TAGS.has(parent.tagName)) return true;
    // 可编辑内容：浏览器标准属性 + contenteditable 属性双重检查（兼容部分环境无 isContentEditable）
    if (parent.isContentEditable) return true;
    if (parent.hasAttribute?.('contenteditable')) return true;
    return false;
  }

  /** 处理单个文本节点 */
  function processNode(node) {
    if (node.nodeType !== 3 /* TEXT_NODE */) return;
    const value = node.nodeValue;
    if (!value) return;
    if (lastWritten.get(node) === value) return; // 自身写入/已确认 → 防循环跳过
    if (shouldSkip(node)) return;
    if (!textConversion.containsTraditional(value, config.get('profile'))) {
      lastWritten.set(node, value); // 无繁体：确认后跳过，避免重复检测
      return;
    }
    const converted = safe(
      () => textConversion.toSimplified(value, config.get('profile')),
      value,
      'NODE_CONVERT_FAILED',
    );
    if (converted !== value) {
      lastWritten.set(node, converted);
      node.nodeValue = converted; // 写回 DOM（触发新 mutation，由 lastWritten 防循环）
      logger.debug('已转换:', value, '→', converted);
    } else {
      lastWritten.set(node, value);
    }
  }

  /** 收集元素子树内全部文本节点 */
  function collectTextNodes(root, out) {
    if (!root) return out;
    const walker = document.createTreeWalker(root, 4 /* SHOW_TEXT */);
    let current = walker.nextNode();
    while (current) {
      out.push(current);
      current = walker.nextNode();
    }
    return out;
  }

  /** MutationObserver 回调 */
  function onMutations(mutations) {
    for (const mutation of mutations) {
      if (mutation.type === 'characterData' && mutation.target) {
        processNode(mutation.target);
      }
      for (const added of mutation.addedNodes) {
        if (added.nodeType === 3) {
          processNode(added);
        } else if (added.nodeType === 1) {
          const nodes = collectTextNodes(added, []);
          for (const node of nodes) processNode(node);
        }
      }
    }
  }

  /** 全量扫描当前文档（启动时与配置变更后使用） */
  function scanAll() {
    if (scanning) return;
    scanning = true;
    try {
      const root = document.documentElement;
      if (!root) return;
      const nodes = collectTextNodes(root, []);
      logger.debug(`全量扫描 ${nodes.length} 个文本节点`);
      for (const node of nodes) processNode(node);
    } finally {
      scanning = false;
    }
  }

  return {
    /** 启动观察（含一次全量扫描） */
    start() {
      if (observer) return; // 幂等
      observer = new MutationObserver(onMutations);
      observer.observe(document.documentElement, {
        subtree: true,
        childList: true,
        characterData: true,
      });
      scanAll();
      logger.info('DOM 观察器已启动');
    },

    /** 停止观察 */
    stop() {
      observer?.disconnect();
      observer = null;
      logger.info('DOM 观察器已停止');
    },

    /** 是否运行中 */
    isRunning() {
      return observer !== null;
    },

    scanAll,
  };
}
