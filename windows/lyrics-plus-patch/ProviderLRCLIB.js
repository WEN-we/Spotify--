/**
 * ProviderLRCLIB（增强版）
 * 原版仅精确匹配（track+artist+album+duration 全中才返回），中文歌曲命中率低。
 * 增强：精确匹配失败后，回退 /api/search 模糊搜索，按时长最接近挑选最佳结果。
 */
const ProviderLRCLIB = (() => {
	const USER_AGENT = `spicetify v${Spicetify.Config.version} (https://github.com/spicetify/cli)`;

	async function findLyrics(info) {
		const baseURL = "https://lrclib.net/api/get";
		const searchURL = "https://lrclib.net/api/search";
		const durr = info.duration / 1000;
		const params = {
			track_name: info.title,
			artist_name: info.artist,
			album_name: info.album,
			duration: durr,
		};

		const finalURL = `${baseURL}?${Object.keys(params)
			.map((key) => `${key}=${encodeURIComponent(params[key])}`)
			.join("&")}`;

		const body = await fetch(finalURL, {
			headers: {
				"x-user-agent": USER_AGENT,
			},
		});

		if (body.status === 200) {
			return await body.json();
		}

		// 精确匹配失败 → 模糊搜索回退
		if (!info.title) {
			return {
				error: "Request error: Track wasn't found",
				uri: info.uri,
			};
		}

		const searchParams = new URLSearchParams({
			track_name: info.title,
			artist_name: info.artist ?? "",
		});
		const searchResponse = await fetch(`${searchURL}?${searchParams.toString()}`, {
			headers: {
				"x-user-agent": USER_AGENT,
			},
		});

		if (searchResponse.status !== 200) {
			return {
				error: "Request error: Track wasn't found",
				uri: info.uri,
			};
		}

		const results = await searchResponse.json();
		if (!Array.isArray(results) || !results.length) {
			return {
				error: "Request error: Track wasn't found",
				uri: info.uri,
			};
		}

		// 按时长最接近挑选（跳过无歌词项）
		let best = null;
		let bestDiff = Infinity;
		for (const item of results) {
			if (!item.syncedLyrics && !item.plainLyrics) continue;
			const diff = Math.abs((item.duration ?? 0) - durr);
			if (diff < bestDiff) {
				bestDiff = diff;
				best = item;
			}
		}

		if (!best) {
			return {
				error: "Request error: Track wasn't found",
				uri: info.uri,
			};
		}
		return best;
	}

	function getUnsynced(body) {
		const unsyncedLyrics = body?.plainLyrics;
		const isInstrumental = body.instrumental;
		if (isInstrumental) return [{ text: "♪ Instrumental ♪" }];

		if (!unsyncedLyrics) return null;

		return Utils.parseLocalLyrics(unsyncedLyrics).unsynced;
	}

	function getSynced(body) {
		const syncedLyrics = body?.syncedLyrics;
		const isInstrumental = body.instrumental;
		if (isInstrumental) return [{ text: "♪ Instrumental ♪" }];

		if (!syncedLyrics) return null;

		return Utils.parseLocalLyrics(syncedLyrics).synced;
	}

	return { findLyrics, getSynced, getUnsynced };
})();
