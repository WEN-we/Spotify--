/**
 * 从代理访问日志重建搜索缓存（QQ 搜索接口被频控时的自愈工具）
 *
 * 原理：qq-proxy-access.log 记录了历史成功请求。搜索接口被 QQ 风控（500）期间，
 *      歌词接口通常仍可用。本脚本解析日志中「search 成功 → lyric 成功」配对，
 *      用已知 songid 重建搜索响应缓存，使「搜索→取词」链路绕过被封锁的搜索接口。
 *
 * 用法：先停止代理（计划任务 SpotifyQQProxy），运行本脚本，再启动代理。
 *      node services/seed-cache-from-log.mjs
 * 幂等：重复运行安全；不覆盖已存在的缓存条目。
 */
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { logger } from '../utils/logger.js';

const HERE = fileURLToPath(new URL('.', import.meta.url));
const ACCESS_LOG = `${HERE}qq-proxy-access.log`;
const CACHE_FILE = `${HERE}qq-proxy-cache.json`;
const SEARCH_TARGET = 'https://c.y.qq.com/soso/fcgi-bin/client_search_cp';

/** 日志行解析：返回 { q } 或 { songid, len } 或 null */
function parseLine(line) {
  let m = line.match(/GET \/search\?q=([^ &]+).*→ 200/);
  if (m) return { q: decodeURIComponent(m[1]) };
  m = line.match(/GET \/lyric\/(\d+).*→ 200 len=(\d+)/);
  if (m) return { songid: m[1], len: Number(m[2]) };
  return null;
}

/** 由搜索关键词生成歌名候选（query = cleanTitle + " " + firstArtist，
 *  cleanTitle 是空格前缀之一；全部生成为候选，靠名称精确匹配命中） */
function titleVariants(query) {
  const parts = query.split(/\s+/).filter(Boolean);
  const variants = new Set();
  for (let i = 1; i <= parts.length; i++) {
    variants.add(parts.slice(0, i).join(' '));
  }
  // 传统→常见简体归一化由 Provider 侧执行，这里原样保留繁体形式
  return [...variants];
}

// ── 1. 解析日志，配对 search → songid ─────────────────────
if (!existsSync(ACCESS_LOG)) {
  logger.error(`访问日志不存在: ${ACCESS_LOG}`);
  process.exit(1);
}

const pairs = new Map(); // query → songid
let pendingQuery = null;
for (const line of readFileSync(ACCESS_LOG, 'utf8').split('\n')) {
  const parsed = parseLine(line);
  if (!parsed) continue;
  if (parsed.q !== undefined) {
    pendingQuery = parsed.q;
  } else if (parsed.songid && parsed.len > 100 && pendingQuery) {
    // 同一搜索后紧跟的成功 lyric 即为匹配结果（搜索→取词为固定时序）
    if (!pairs.has(pendingQuery)) pairs.set(pendingQuery, parsed.songid);
    pendingQuery = null;
  }
}

if (!pairs.size) {
  logger.warn('日志中未找到可用的 search→lyric 配对，无需重建');
  process.exit(0);
}

// ── 2. 构造缓存条目 ───────────────────────────────────────
let cache = {};
try {
  cache = JSON.parse(readFileSync(CACHE_FILE, 'utf8'));
} catch { /* 无缓存文件 → 新建 */ }

let added = 0;
for (const [query, songid] of pairs) {
  const target = `${SEARCH_TARGET}?format=json&n=10&w=${encodeURIComponent(query)}`;
  if (cache[target]) continue; // 幂等：已有条目不覆盖

  const list = titleVariants(query).map((name) => ({
    songname: name,
    singer: [{ name: '' }],
    interval: 0, // 未知时长：匹配落到「歌名精确」规则（规则3）
    songid: Number(songid),
  }));
  cache[target] = {
    body: JSON.stringify({ data: { song: { list } } }),
    contentType: 'application/json',
    ts: Date.now(),
  };
  added++;
}

// ── 3. 写回 ──────────────────────────────────────────────
writeFileSync(CACHE_FILE, JSON.stringify(cache, null, 1));
logger.info(`重建完成: ${added} 条搜索缓存（共 ${pairs.size} 个配对），已写入 qq-proxy-cache.json`);
logger.info('请重启代理（计划任务 SpotifyQQProxy）使缓存生效');
