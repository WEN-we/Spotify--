/**
 * 从代理访问日志重建搜索缓存（QQ 搜索接口被频控时的自愈工具）
 *
 * 原理：qq-proxy-access.log 记录了历史成功请求。搜索接口被 QQ 风控（500）期间，
 *      歌词接口通常仍可用。本脚本解析日志中「search 成功 → lyric 成功」配对，
 *      用已知 songid 重建搜索响应缓存，使「搜索→取词」链路绕过被封锁的搜索接口。
 *
 * 安全机制（v2，修复错配事故）：
 *   1. 严格相邻配对：search 之后必须紧跟 lyric（中间出现其他 search 则作废），
 *      防止多歌曲并发请求交错导致「最后搜索」与「他人歌词」错配。
 *   2. [ti:] 交叉验证：拉取该 songid 的歌词，比对 [ti:歌名] 与搜索关键词的标题部分
 *      （归一化后双向包含）——「GIve up CJX7816 → 江声入旧年」类错配在此被拦截。
 *   3. seeded 标记：重建条目带 seeded:true，便于审计与清理。
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
const LYRIC_TARGET = 'https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg';
const LYRIC_HEADERS = {
  'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:93.0) Gecko/20100101 Firefox/93.0',
  Referer: 'https://y.qq.com/',
};

/** 日志行解析：返回 { q } 或 { songid, len } 或 null */
function parseLine(line) {
  let m = line.match(/GET \/search\?q=([^ &]+).*→ 200/);
  if (m) return { q: decodeURIComponent(m[1]) };
  m = line.match(/GET \/lyric\/(\d+).*→ 200 len=(\d+)/);
  if (m) return { songid: m[1], len: Number(m[2]) };
  return null;
}

/** 由搜索关键词生成歌名候选（query = cleanTitle + " " + firstArtist） */
function titleVariants(query) {
  const parts = query.split(/\s+/).filter(Boolean);
  const variants = new Set();
  for (let i = 1; i <= parts.length; i++) {
    variants.add(parts.slice(0, i).join(' '));
  }
  return [...variants];
}

/** 归一化：小写 + 去首尾空白（繁简由 Provider 侧映射，此处宽松处理） */
const norm = (s) => String(s ?? '').trim().toLowerCase();

/** 拉取歌词正文（歌词接口通常不受搜索频控影响；失败返回 null） */
async function fetchLyricBody(songid) {
  try {
    const res = await fetch(`${LYRIC_TARGET}?format=json&nobase64=1&musicid=${songid}`, {
      headers: LYRIC_HEADERS,
      signal: AbortSignal.timeout(8000),
    });
    if (!res.ok) return null;
    const text = await res.text();
    const lyric = JSON.parse(text)?.lyric ?? '';
    return lyric.length > 50 ? lyric : null;
  } catch {
    return null;
  }
}

/** [ti:] 标题与关键词标题变体交叉验证（归一化双向包含） */
function titleVerified(lyricBody, variants) {
  const m = lyricBody.match(/\[ti:([^\]]*)\]/);
  if (!m) return false;
  const ti = norm(m[1]);
  if (!ti) return false;
  return variants.some((v) => {
    const n = norm(v);
    return n.length >= 2 && (ti.includes(n) || n.includes(ti));
  });
}

// ── 1. 解析日志，严格相邻配对 search → songid ─────────────
if (!existsSync(ACCESS_LOG)) {
  logger.error(`访问日志不存在: ${ACCESS_LOG}`);
  process.exit(1);
}

const pairs = new Map(); // query → songid（仅严格相邻配对）
let pendingQuery = null;
for (const line of readFileSync(ACCESS_LOG, 'utf8').split('\n')) {
  const parsed = parseLine(line);
  if (!parsed) continue;
  if (parsed.q !== undefined) {
    pendingQuery = parsed.q;
  } else if (parsed.songid && parsed.len > 100 && pendingQuery) {
    if (!pairs.has(pendingQuery)) pairs.set(pendingQuery, parsed.songid);
    pendingQuery = null; // 消费后置空：下一个 lyric 若无紧邻 search 则不作配对
  }
}

if (!pairs.size) {
  logger.warn('日志中未找到可用的 search→lyric 相邻配对，无需重建');
  process.exit(0);
}

// ── 2. 逐对 [ti:] 交叉验证（拦截错配） ─────────────────────
let cache = {};
try {
  cache = JSON.parse(readFileSync(CACHE_FILE, 'utf8'));
} catch { /* 无缓存文件 → 新建 */ }

let added = 0;
let rejected = 0;
for (const [query, songid] of pairs) {
  const target = `${SEARCH_TARGET}?format=json&n=10&w=${encodeURIComponent(query)}`;
  if (cache[target]) continue; // 幂等：已有条目不覆盖

  const lyricBody = await fetchLyricBody(songid);
  if (!lyricBody) {
    logger.warn(`跳过（歌词拉取失败，无法验证）: ${query} → ${songid}`);
    rejected++;
    continue;
  }
  if (!titleVerified(lyricBody, titleVariants(query))) {
    const ti = lyricBody.match(/\[ti:([^\]]*)\]/)?.[1] ?? '?';
    logger.warn(`拦截错配（[ti:${ti}] 与关键词不符）: ${query} → ${songid}`);
    rejected++;
    continue;
  }

  const list = titleVariants(query).map((name) => ({
    songname: name,
    singer: [{ name: '' }],
    interval: 0, // 未知时长：匹配落到「歌名精确」规则
    songid: Number(songid),
  }));
  cache[target] = {
    body: JSON.stringify({ data: { song: { list } } }),
    contentType: 'application/json',
    ts: Date.now(),
    seeded: true, // 标记：种子重建条目（区别于真实上游响应）
  };
  added++;
}

// ── 3. 写回 ───────────────────────────────────────────────
writeFileSync(CACHE_FILE, JSON.stringify(cache, null, 1));
logger.info(`重建完成: 新增 ${added} 条 / 拦截或跳过 ${rejected} 条（共 ${pairs.size} 个配对）`);
if (added || rejected) logger.info('请重启代理（计划任务 SpotifyQQProxy）使缓存生效');
