/**
 * ProviderQQMusic：QQ音乐歌词源（中文歌曲最佳覆盖）
 * - 直连官方接口（无第三方代理，稳定）
 * - 返回简体中文同步歌词（LRC 格式）
 * - 搜索后按「歌名精确 + 时长接近」智能匹配
 */
const ProviderQQMusic = (() => {
	const requestHeader = {
		"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:93.0) Gecko/20100101 Firefox/93.0",
		Referer: "https://y.qq.com/",
	};

	/** 取第一主唱歌手（Spotify 多歌手格式：A / B 或 A, B） */
	function firstArtist(artist) {
		return String(artist ?? "")
			.split(/,\s*|\s*\/\s*|;|&/)
			.map((s) => s.trim())
			.filter(Boolean)[0] ?? "";
	}

	async function findLyrics(info) {
		const searchURL = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?format=json&n=10&w=";
		const lyricURL = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?format=json&nobase64=1&musicid=";

		const cleanTitle = Utils.removeExtraInfo(Utils.removeSongFeat(Utils.normalize(info.title)));
		if (!cleanTitle) throw "Cannot find track";

		const query = `${cleanTitle} ${firstArtist(info.artist)}`.trim();
		const searchResults = await Spicetify.CosmosAsync.get(searchURL + encodeURIComponent(query), null, requestHeader);
		const items = searchResults?.data?.song?.list;
		if (!items?.length) throw "Cannot find track";

		// 匹配策略：歌名精确 + 时长接近 > 时长接近 > 歌名精确
		const durationDiff = (val) => Math.abs(info.duration - (val?.interval ?? 0) * 1000);
		let itemId = items.findIndex((val) => val.songname === cleanTitle && durationDiff(val) < 3000);
		if (itemId === -1) itemId = items.findIndex((val) => durationDiff(val) < 3000);
		if (itemId === -1) itemId = items.findIndex((val) => val.songname === cleanTitle);
		if (itemId === -1) throw "Cannot find track";

		const songId = items[itemId].songid;
		if (!songId) throw "Cannot find track";

		return await Spicetify.CosmosAsync.get(lyricURL + songId, null, requestHeader);
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
		if (!lyricStr) return null;

		let noLyrics = false;
		const lyrics = parseLrc(lyricStr)
			.map(({ startTime, text }) => {
				if (text === "纯音乐" || text === "纯音乐, 请欣赏") noLyrics = true;
				if (containCredits(text)) return null;
				return { startTime, text };
			})
			.filter(Boolean);

		if (!lyrics.length || noLyrics) return null;
		return lyrics;
	}

	return { findLyrics, getSynced };
})();
