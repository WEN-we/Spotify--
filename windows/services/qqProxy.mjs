/**
 * QQ 音乐 API 本地 CORS 代理（零依赖，Node >= 18）
 *
 * 背景：Spotify CEF 内的 fetch/CosmosAsync 受 CORS 约束，
 *      c.y.qq.com 不返回 Access-Control-Allow-Origin，请求被浏览器拦截。
 * 方案：本地起 127.0.0.1:39871 反向代理，转发请求并补 CORS 头。
 * 安全：仅监听本机回环；仅允许转发到 c.y.qq.com 白名单路径。
 */
import { createServer } from 'node:http';
import { logger } from '../utils/logger.js';

const PORT = 39871;
/** 允许转发的目标前缀（白名单，防 SSRF） */
const ALLOWED_TARGETS = [
  'https://c.y.qq.com/soso/fcgi-bin/client_search_cp',
  'https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg',
];

const UPSTREAM_HEADERS = {
  'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:93.0) Gecko/20100101 Firefox/93.0',
  Referer: 'https://y.qq.com/',
};

function corsHeaders(origin) {
  return {
    'Access-Control-Allow-Origin': origin || '*',
    'Access-Control-Allow-Methods': 'GET, OPTIONS',
    'Access-Control-Allow-Headers': '*',
    'Access-Control-Max-Age': '86400',
  };
}

const server = createServer(async (req, res) => {
  const origin = req.headers.origin;

  // CORS 预检
  if (req.method === 'OPTIONS') {
    res.writeHead(204, corsHeaders(origin));
    res.end();
    return;
  }

  if (req.method !== 'GET') {
    res.writeHead(405, corsHeaders(origin));
    res.end(JSON.stringify({ error: 'method not allowed' }));
    return;
  }

  // 目标地址 = query 参数 ?url=（须命中白名单）
  const target = new URL(req.url, `http://127.0.0.1:${PORT}`).searchParams.get('url');
  if (!target || !ALLOWED_TARGETS.some((prefix) => target.startsWith(prefix))) {
    res.writeHead(403, corsHeaders(origin));
    res.end(JSON.stringify({ error: 'target not allowed' }));
    return;
  }

  try {
    const upstream = await fetch(target, { headers: UPSTREAM_HEADERS });
    const body = await upstream.text();
    res.writeHead(
      upstream.status,
      corsHeaders(origin),
      { 'Content-Type': upstream.headers.get('content-type') ?? 'application/json' },
    );
    res.end(body);
  } catch (err) {
    logger.error(`代理转发失败: ${err.message}`);
    res.writeHead(502, corsHeaders(origin));
    res.end(JSON.stringify({ error: 'upstream failed' }));
  }
});

server.listen(PORT, '127.0.0.1', () => {
  logger.info(`QQ 音乐 CORS 代理已启动: http://127.0.0.1:${PORT}`);
});

process.on('SIGINT', () => process.exit(0));
