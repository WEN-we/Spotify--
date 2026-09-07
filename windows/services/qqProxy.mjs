/**
 * QQ 音乐 API 本地 CORS 代理（零依赖，Node >= 18）
 *
 * 背景：Spotify CEF 内的 fetch 受 CORS 约束，c.y.qq.com 不返回 Access-Control-Allow-Origin；
 *      且 CEF 对部分请求形态存在拦截（诊断中），故提供多种端点形态供判别与使用。
 * 端点：
 *   GET /search?q=<关键词>        → client_search_cp 搜索（query 式）
 *   GET /s/<base64url关键词>      → 同上（路径式，无 query）
 *   GET /lyric/<songid>           → fcg_query_lyric_new 歌词（路径式）
 *   GET /ping200                  → 静态 200 JSON（判别用，不转发）
 *   GET /?url=<完整URL>            → 旧接口（外部 curl 调试）
 * 安全：仅监听本机回环；仅允许转发到 c.y.qq.com 白名单路径。
 * 日志：请求流水写入 qq-proxy-access.log（供外部诊断 CEF 请求是否到达）。
 */
import { createServer } from 'node:http';
import { appendFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { logger } from '../utils/logger.js';

const PORT = 39871;
const ACCESS_LOG = fileURLToPath(new URL('./qq-proxy-access.log', import.meta.url));

/** QQ 音乐上游固定端点（白名单，防 SSRF） */
const SEARCH_TARGET = 'https://c.y.qq.com/soso/fcgi-bin/client_search_cp';
const LYRIC_TARGET = 'https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg';
const LEGACY_ALLOWED_PREFIXES = [SEARCH_TARGET, LYRIC_TARGET];

const UPSTREAM_HEADERS = {
  'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:93.0) Gecko/20100101 Firefox/93.0',
  Referer: 'https://y.qq.com/',
};

/** 访问日志：记录每个到达的请求与转发结果（判别 CEF 请求是否到达代理） */
function accessLog(line) {
  const ts = new Date().toISOString().slice(11, 23);
  try {
    appendFileSync(ACCESS_LOG, `${ts} ${line}\n`);
  } catch { /* 日志失败不影响服务 */ }
}

function corsHeaders(origin) {
  return {
    'Access-Control-Allow-Origin': origin || '*',
    'Access-Control-Allow-Methods': 'GET, OPTIONS',
    'Access-Control-Allow-Headers': '*',
    'Access-Control-Max-Age': '86400',
  };
}

/** base64url 解码（路径式搜索关键词） */
function decodeBase64Url(s) {
  try {
    const b64 = s.replace(/-/g, '+').replace(/_/g, '/');
    return Buffer.from(b64, 'base64').toString('utf8');
  } catch {
    return null;
  }
}

/** 解析请求 → 返回 { target } 或 { static, status, contentType }；不合法返回 null */
function resolveTarget(pathname, searchParams) {
  // 静态判别端点（不转发）：状态码 × Content-Type 矩阵
  if (pathname === '/x/403json') {
    return { static: JSON.stringify({ ok: false, status: 403 }), status: 403, contentType: 'application/json' };
  }
  if (pathname === '/x/200plain') {
    return { static: 'hello world', status: 200, contentType: 'text/plain' };
  }
  if (pathname === '/x/200json') {
    return { static: JSON.stringify({ ok: true, status: 200, kind: 'json' }), status: 200, contentType: 'application/json' };
  }
  if (pathname === '/x/201json') {
    return { static: JSON.stringify({ ok: true, status: 201, kind: 'json' }), status: 201, contentType: 'application/json' };
  }
  if (pathname === '/ping200') {
    return { static: JSON.stringify({ ok: true, ts: Date.now() }), status: 200, contentType: 'application/json' };
  }
  // E组判别：QQ 的 Content-Type 复现
  if (pathname === '/x/qqct') {
    return { static: JSON.stringify({ ok: true, kind: 'qq-content-type' }), status: 200, contentType: 'application/x-javascript;charset=utf-8' };
  }
  // E组判别：大 body（约10KB，模拟 QQ 搜索响应体量）
  if (pathname === '/x/bigbody') {
    const big = JSON.stringify({ ok: true, data: 'x'.repeat(10000) });
    return { static: big, status: 200, contentType: 'application/json' };
  }
  // E组判别：慢响应（1.5s 延迟，模拟转发耗时）
  if (pathname === '/x/slow200') {
    return { static: JSON.stringify({ ok: true, kind: 'slow' }), status: 200, contentType: 'application/json', delayMs: 1500 };
  }
  // 路径式搜索：/s/<base64url 关键词>
  const pathMatch = pathname.match(/^\/s\/([A-Za-z0-9_\-=]{1,512})$/);
  if (pathMatch) {
    const keyword = decodeBase64Url(pathMatch[1]);
    if (!keyword) return null;
    return { target: `${SEARCH_TARGET}?format=json&n=10&w=${encodeURIComponent(keyword)}` };
  }
  // query 式搜索：/search?q=
  if (pathname === '/search') {
    const q = searchParams.get('q');
    if (!q) return null;
    const n = Number.parseInt(searchParams.get('n') ?? '10', 10);
    const count = Number.isFinite(n) && n > 0 && n <= 30 ? n : 10;
    return { target: `${SEARCH_TARGET}?format=json&n=${count}&w=${encodeURIComponent(q)}` };
  }
  // 路径式歌词：/lyric/<songid>
  const lyricMatch = pathname.match(/^\/lyric\/(\d{1,20})$/);
  if (lyricMatch) {
    return { target: `${LYRIC_TARGET}?format=json&nobase64=1&musicid=${lyricMatch[1]}` };
  }
  // 兼容旧 ?url=（外部调试）
  const legacy = searchParams.get('url');
  if (legacy && LEGACY_ALLOWED_PREFIXES.some((prefix) => legacy.startsWith(prefix))) {
    return { target: legacy };
  }
  return null;
}

const server = createServer(async (req, res) => {
  const origin = req.headers.origin;

  if (req.method === 'OPTIONS') {
    accessLog(`OPTIONS ${req.url} origin=${origin ?? '-'}`);
    res.writeHead(204, corsHeaders(origin));
    res.end();
    return;
  }

  if (req.method !== 'GET') {
    accessLog(`${req.method} ${req.url} → 405`);
    res.writeHead(405, corsHeaders(origin));
    res.end(JSON.stringify({ error: 'method not allowed' }));
    return;
  }

  const reqUrl = new URL(req.url, `http://127.0.0.1:${PORT}`);
  const resolved = resolveTarget(reqUrl.pathname, reqUrl.searchParams);
  if (!resolved) {
    accessLog(`GET ${req.url} → 403 origin=${origin ?? '-'}`);
    res.writeHead(403, corsHeaders(origin));
    res.end(JSON.stringify({ error: 'target not allowed' }));
    return;
  }

  // 静态端点
  if (resolved.static !== undefined) {
    const status = resolved.status ?? 200;
    if (resolved.delayMs) await new Promise((r) => setTimeout(r, resolved.delayMs));
    accessLog(`GET ${req.url} → ${status}(static ${resolved.contentType ?? '-'}) origin=${origin ?? '-'}`);
    const headers = corsHeaders(origin);
    if (resolved.contentType) headers['Content-Type'] = resolved.contentType;
    res.writeHead(status, headers);
    res.end(resolved.static);
    return;
  }

  // 转发端点
  // 注意：writeHead 必须用单个合并对象——三参数形式 (status, obj1, obj2) 会丢弃 obj1 的 CORS 头，
  // 导致 CEF 内 CORS 校验失败（"Failed to fetch"），而外部 curl 不查 CORS 故难以察觉。
  try {
    const upstream = await fetch(resolved.target, { headers: UPSTREAM_HEADERS });
    const body = await upstream.text();
    accessLog(`GET ${req.url} → ${upstream.status} len=${body.length} origin=${origin ?? '-'}`);
    const headers = {
      ...corsHeaders(origin),
      'Content-Type': upstream.headers.get('content-type') ?? 'application/json',
    };
    res.writeHead(upstream.status, headers);
    res.end(body);
  } catch (err) {
    accessLog(`GET ${req.url} → 502 ${err.message} origin=${origin ?? '-'}`);
    logger.error(`代理转发失败: ${err.message}`);
    res.writeHead(502, corsHeaders(origin));
    res.end(JSON.stringify({ error: 'upstream failed' }));
  }
});

server.listen(PORT, '127.0.0.1', () => {
  accessLog(`=== 代理启动 :${PORT}（/s/<b64> /search?q= /lyric/<id> /ping200）===`);
  logger.info(`QQ 音乐 CORS 代理已启动: http://127.0.0.1:${PORT}`);
});

process.on('SIGINT', () => process.exit(0));
