/**
 * ProviderQQMusic：QQ音乐歌词源（中文歌曲最佳覆盖）
 * - Spotify CEF 内受 CORS 限制，经本地代理（127.0.0.1:39871）转发
 * - 注意：CEF 安全策略拦截 query 中携带完整外部 URL 的请求（?url=https%3A%2F%2F...），
 *   故代理端点使用路径参数（/search?q= 与 /lyric/<id>），query 中不出现 "://" 模式
 * - 代理不可用时自动降级为直连（外部环境仍可用），直连失败抛错给下一源
 * - 返回简体中文同步歌词（LRC 格式）
 * - 搜索后按「歌名精确 + 时长接近」智能匹配，歌名做繁→简归一化（覆盖繁体元数据歌曲）
 */
const ProviderQQMusic = (() => {
	const PROXY_SEARCH = "http://127.0.0.1:39871/search?q=";
	const PROXY_SB = "http://127.0.0.1:39871/sb?q=";
	const PROXY_LYRIC = "http://127.0.0.1:39871/lyric/";
	const SEARCH_API = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?format=json&n=10&w=";
	const SB_API = "https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg?key=";
	const LYRIC_API = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?format=json&nobase64=1&musicid=";

	/** 调试埋点：写入 localStorage（保留最新 20 条），供外部诊断 CEF 内真实行为 */
	const DEBUG_KEY = "lyrics-plus:debug:qqmusic";
	function debugLog(stage, data) {
		try {
			const arr = JSON.parse(localStorage.getItem(DEBUG_KEY) ?? "[]");
			arr.push({
				t: new Date().toISOString().slice(11, 19),
				stage,
				d: typeof data === "string" ? data.slice(0, 200) : data,
			});
			localStorage.setItem(DEBUG_KEY, JSON.stringify(arr.slice(-20)));
		} catch {}
	}

	const requestHeader = {
		"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:93.0) Gecko/20100101 Firefox/93.0",
		Referer: "https://y.qq.com/",
	};

	/** 简体常见繁体字映射（用于歌名归一化匹配，非通用转换器） */
	const TRAD_TO_SIMP = {
		聲: "声", 無: "无", 哀: "哀", 樂: "乐", 舊: "旧", 年: "年", 倫: "伦", 傑: "杰",
		愛: "爱", 萬: "万", 語: "语", 說: "说", 學: "学", 風: "风", 雲: "云", 龍: "龙",
		鳳: "凤", 鳥: "鸟", 馬: "马", 車: "车", 東: "东", 陳: "陈", 劉: "刘", 黃: "黄",
		張: "张", 吳: "吴", 趙: "赵", 錢: "钱", 孫: "孙", 書: "书", 畫: "画", 詩: "诗",
		詞: "词", 曲: "曲", 詠: "咏", 唱: "唱", 夢: "梦", 飛: "飞", 藍: "蓝", 紅: "红",
		綠: "绿", 黑: "黑", 白: "白", 光: "光", 影: "影", 時: "时", 間: "间", 門: "门",
		開: "开", 關: "关", 長: "长", 短: "短", 遠: "远", 近: "近", 邊: "边", 鐘: "钟",
	};

	/** 歌名繁→简归一化（仅映射表覆盖的字，未映射字符保持原样） */
	function normalizeToSimplified(s) {
		return [...String(s ?? "")].map((ch) => TRAD_TO_SIMP[ch] ?? ch).join("");
	}

	/**
	 * GET 请求：本地代理优先（路径参数，避开 CEF 对 query 中外部 URL 的拦截），失败降级直连
	 * @param {string} proxyUrl 本地代理 URL（不含外部 URL 的 query）
	 * @param {string} directUrl QQ 音乐直连 URL（降级用）
	 */
	async function qqGet(proxyUrl, directUrl) {
		// 1. 本地代理（补 CORS 头，Spotify CEF 内可用）
		try {
			const res = await fetch(proxyUrl, { headers: requestHeader });
			if (res.ok) {
				debugLog("proxy-ok", proxyUrl.slice(0, 60));
				return await res.json();
			}
			debugLog("proxy-status", `${res.status} ${proxyUrl.slice(0, 60)}`);
		} catch (e) {
			debugLog("proxy-fail", `${e?.message ?? e} ${proxyUrl.slice(0, 60)}`);
		}

		// 2. 直连（代理未启动场景；CEF 内会因 CORS/Origin 拦截返回空，由调用方判断）
		try {
			const direct = await Spicetify.CosmosAsync.get(directUrl, null, requestHeader);
			debugLog("direct-ok", directUrl.slice(0, 60));
			return direct;
		} catch (e) {
			debugLog("direct-fail", `${e?.message ?? e} ${directUrl.slice(0, 60)}`);
			throw e;
		}
	}

	/** 取第一主唱歌手（Spotify 多歌手格式：A / B 或 A, B） */
	function firstArtist(artist) {
		return String(artist ?? "")
			.split(/,\s*|\s*\/\s*|;|&/)
			.map((s) => s.trim())
			.filter(Boolean)[0] ?? "";
	}

	async function findLyrics(info) {
		const cleanTitle = Utils.removeExtraInfo(Utils.removeSongFeat(Utils.normalize(info.title)));
		if (!cleanTitle) throw "Cannot find track";

		const query = `${cleanTitle} ${firstArtist(info.artist)}`.trim();
		debugLog("search-start", `${query} | dur=${info.duration}ms | raw=${info.title}`);

		// 主搜索（client_search_cp）：失败/无结果不直接抛——继续 smartbox 回退
		let items = null;
		try {
			const searchResults = await qqGet(PROXY_SEARCH + encodeURIComponent(query), SEARCH_API + encodeURIComponent(query));
			items = searchResults?.data?.song?.list;
		} catch (e) {
			debugLog("search-fail", String(e?.message ?? e).slice(0, 100));
		}
		debugLog("search-result", `count=${items?.length ?? 0}`);

		// 主搜索无结果/失败（接口被封锁 500/空）：smartbox 搜索建议回退——
		// 返回 歌名/完整歌手/songid(docid)，无时长字段（匹配走「歌名+歌手」规则）
		if (!items?.length) {
			try {
				const sb = await qqGet(PROXY_SB + encodeURIComponent(query), SB_API + encodeURIComponent(query) + "&format=json");
				items = (sb?.data?.song?.itemlist ?? []).map((it) => ({
					songname: it.name,
					singer: [{ name: it.singer }],
					interval: 0,
					songid: Number(it.docid),
				}));
				debugLog("smartbox-result", `count=${items.length}`);
			} catch (e) {
				debugLog("smartbox-fail", String(e?.message ?? e).slice(0, 100));
			}
		}
		if (!items?.length) throw "Cannot find track";

		// 匹配策略（歌手感知，v2）：歌名相等（大小写/繁简不敏感）为主，
		// 歌手兼容（双向包含）为约束，时长接近为辅助；禁止纯时长匹配
		// （曾致英文歌「Give up」匹配到同时长/同名不同歌手的中文歌）。
		// 兜底（v3）：无原唱时按用户要求取其他歌手最热同名版本（QQ 搜索序≈热度）
		const simpTitle = normalizeToSimplified(cleanTitle).trim().toLowerCase();
		const wantArtist = normalizeToSimplified(firstArtist(info.artist)).trim().toLowerCase();
		const nameOf = (val) => normalizeToSimplified(val?.songname ?? "").trim().toLowerCase();
		const singerOf = (val) => normalizeToSimplified(val?.singer?.[0]?.name ?? "").trim();
		const nameEq = (val) => nameOf(val) === simpTitle;
		const nameSimilar = (val) => {
			const n = nameOf(val);
			return n.length >= 2 && simpTitle.length >= 2 && (n.includes(simpTitle) || simpTitle.includes(n));
		};
		const artistOk = (val) => {
			const singer = singerOf(val);
			if (!singer || !wantArtist) return true; // 候选歌手未知（种子缓存）或本地歌手为空
			return singer.split(/[\/，,、;]/)
				.map((p) => normalizeToSimplified(p).trim().toLowerCase())
				.some((p) => p && (p.includes(wantArtist) || wantArtist.includes(p)));
		};
		const durationDiff = (val) => Math.abs(info.duration - (val?.interval ?? 0) * 1000);
		const durClose = (val) => info.duration > 0 && (val?.interval ?? 0) > 0 && durationDiff(val) < 3000;
		const durUnknown = (val) => (val?.interval ?? 0) <= 0;
		let itemId = items.findIndex((val) => nameEq(val) && durClose(val) && artistOk(val));
		if (itemId === -1) itemId = items.findIndex((val) => nameEq(val) && durUnknown(val) && artistOk(val));
		if (itemId === -1) itemId = items.findIndex((val) => nameEq(val) && artistOk(val));
		if (itemId === -1) itemId = items.findIndex((val) => nameSimilar(val) && durClose(val) && artistOk(val));
		// 兜底：歌名一致/包含即可，取搜索首位（最热版本）——跨歌曲错配依旧不可能
		if (itemId === -1) itemId = items.findIndex((val) => nameEq(val));
		if (itemId === -1) itemId = items.findIndex((val) => nameSimilar(val));
		if (itemId === -1) {
			debugLog("match-fail", `target="${simpTitle}/${wantArtist}" candidates=${items.map((v) => `${v.songname}/${v.singer?.[0]?.name}/${v.interval}s`).join("; ").slice(0, 200)}`);
			throw "Cannot find track";
		}
		debugLog("match-hit", `#${itemId} ${items[itemId].songname} / ${items[itemId].singer?.[0]?.name}`);

		const songId = items[itemId].songid;
		if (!songId) throw "Cannot find track";

		const lyricBody = await qqGet(PROXY_LYRIC + songId, LYRIC_API + songId);
		debugLog("lyric-fetch", `len=${lyricBody?.lyric?.length ?? 0}`);
		return lyricBody;
	}

	const creditInfo = [
		"\\s?作?\\s*词|\\s?作?\\s*曲|\\s?编\\s*曲?|\\s?监\\s*制?",
		".*编写|.*和音|.*和声|.*合声|.*提琴|.*录|.*工程|.*工作室|.*设计|.*剪辑|.*制作|.*发行|.*出品|.*后期|.*混音|.*缩混",
		"原唱|翻唱|题字|文案|海报|古筝|二胡|钢琴|吉他|贝斯|笛子|鼓|弦乐",
		"lrc|publish|vocal|guitar|program|produce|write|mix",
	];
	const creditInfoRegExp = new RegExp(`^(${creditInfo.join("|")}).*(:|：)`, "i");

	function containCredits(text) {
		return creditInfoRegExp.test(text);
	}

	/** 解析 LRC 文本为 [{startTime: ms, text}]，自动跳过元数据标签行 */
	function parseLrc(lrcText) {
		const lyrics = [];
		for (const rawLine of lrcText.split(/\r?\n/)) {
			const line = rawLine.trim();
			if (!line) continue;
			const timestamps = [...line.matchAll(/\[(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?\]/g)];
			if (!timestamps.length) continue; // 无时间戳 = 元数据（[ti:] 等），跳过
			const text = line.replace(/\[[^\]]*\]/g, "").trim();
			for (const match of timestamps) {
				const min = Number.parseInt(match[1], 10);
				const sec = Number.parseInt(match[2], 10);
				const fracRaw = match[3] ?? "0";
				const ms = Number.parseInt(fracRaw.padEnd(3, "0").slice(0, 3), 10);
				if (Number.isNaN(min) || Number.isNaN(sec)) continue;
				lyrics.push({ startTime: min * 60000 + sec * 1000 + ms, text });
			}
		}
		lyrics.sort((a, b) => a.startTime - b.startTime);
		return lyrics;
	}

	function getSynced(list) {
		const lyricStr = list?.lyric;
		if (!lyricStr) {
			debugLog("no-lyric-field", "lyric 字段为空");
			return null;
		}

		let noLyrics = false;
		const lyrics = parseLrc(lyricStr)
			.map(({ startTime, text }) => {
				if (text === "纯音乐" || text === "纯音乐, 请欣赏") noLyrics = true;
				if (containCredits(text)) return null;
				return { startTime, text };
			})
			.filter(Boolean);

		if (!lyrics.length || noLyrics) {
			debugLog("parse-empty", `raw lines=${lyricStr.split("\n").length} noLyrics=${noLyrics}`);
			return null;
		}
		debugLog("parse-ok", `lines=${lyrics.length} first="${lyrics[0]?.text ?? ""}"`);
		return lyrics;
	}

	return { findLyrics, getSynced, debugLog };
})();
