/**
 * QQ 音乐源诊断扩展 v2（临时）
 * 同时测试三种请求方式，记录原始响应到 localStorage，定位 CosmosAsync 返回空的原因
 */
(function diagnose() {
  function waitSpicetify(retries = 50) {
    if (typeof Spicetify === "undefined" || !Spicetify?.Player?.data || !Spicetify?.CosmosAsync) {
      if (retries > 0) setTimeout(() => waitSpicetify(retries - 1), 500);
      return;
    }
    init();
  }

  const LOG_KEY = "t2s-diagnose:log";

  function log(msg) {
    console.log(`[t2s-diagnose] ${msg}`);
    try {
      const logs = JSON.parse(localStorage.getItem(LOG_KEY) ?? "[]");
      logs.push(`${new Date().toISOString().slice(11, 19)} ${msg}`);
      localStorage.setItem(LOG_KEY, JSON.stringify(logs.slice(-60)));
    } catch { /* 忽略 */ }
  }

  function notify(msg) {
    try { Spicetify.showNotification(`[诊断] ${msg}`, false, 10000); } catch { /* 忽略 */ }
  }

  const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:93.0) Gecko/20100101 Firefox/93.0";
  const headers = { "User-Agent": UA, Referer: "https://y.qq.com/" };

  function summarize(obj) {
    try {
      const list = obj?.data?.song?.list;
      if (Array.isArray(list)) return `ok ${list.length} 首` + (list.length ? ` 第一首:${list[0].songname}` : "");
      return `非预期结构: ${JSON.stringify(obj).slice(0, 150)}`;
    } catch {
      return `解析失败: ${String(obj).slice(0, 100)}`;
    }
  }

  async function run(trackInfo) {
    log(`▶ "${trackInfo.title}" / "${trackInfo.artist}"`);
    const query = `${trackInfo.title} ${String(trackInfo.artist ?? "").split(/,\s*|\s*\/\s*/)[0] ?? ""}`.trim();
    const url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?format=json&n=10&w=" + encodeURIComponent(query);

    // 方式1：CosmosAsync 带自定义 headers（当前 ProviderQQMusic 用法）
    try {
      const body = await Spicetify.CosmosAsync.get(url, null, headers);
      log(`① Cosmos+headers: ${summarize(body)}`);
    } catch (e) {
      log(`① Cosmos+headers 异常: ${e?.message ?? e}`);
    }

    // 方式2：CosmosAsync 不带 headers
    try {
      const body = await Spicetify.CosmosAsync.get(url);
      log(`② Cosmos裸请求: ${summarize(body)}`);
    } catch (e) {
      log(`② Cosmos裸请求 异常: ${e?.message ?? e}`);
    }

    // 方式3：原生 fetch
    try {
      const resp = await fetch(url, { headers });
      const text = await resp.text();
      let summary;
      try { summary = summarize(JSON.parse(text)); } catch { summary = `非JSON(${resp.status}): ${text.slice(0, 120)}`; }
      log(`③ fetch: HTTP ${resp.status} ${summary}`);
    } catch (e) {
      log(`③ fetch 异常: ${e?.message ?? e}`);
    }

    notify("诊断完成，请查看 localStorage 日志");
  }

  function init() {
    log("诊断扩展v2已加载");
    notify("诊断v2已加载");
    Spicetify.Player.addEventListener("songchange", () => {
      const meta = Spicetify.Player.data?.item?.metadata;
      if (!meta) return;
      run({ title: meta.title, artist: meta.artist_name });
    });
  }

  waitSpicetify();
})();
