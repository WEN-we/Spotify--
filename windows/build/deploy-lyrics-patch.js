/**
 * lyrics-plus 中文歌词增强补丁部署脚本
 *
 * 补丁内容：
 * 1. 新增 ProviderQQMusic.js（QQ音乐源，直连官方接口，简体歌词）
 * 2. 替换 ProviderLRCLIB.js（精确匹配失败后模糊搜索回退）
 * 3. index.js 注册 qqmusic 源（默认启用）
 * 4. Providers.js 挂载 qqmusic 实现
 * 5. manifest.json subfiles 注册新文件
 *
 * 幂等：重复执行安全。spicetify 更新覆盖 lyrics-plus 后，重新运行本脚本即可。
 * 用法：node build/deploy-lyrics-patch.js [--apply]（--apply 时附带 spicetify apply）
 */
import { copyFileSync, readFileSync, writeFileSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { logger } from '../utils/logger.js';

const ROOT = dirname(dirname(fileURLToPath(import.meta.url)));
const PATCH_DIR = join(ROOT, 'lyrics-plus-patch');

// lyrics-plus 安装目录（spicetify 内置应用安装到 LOCALAPPDATA）
const TARGET_DIR = join(process.env.LOCALAPPDATA ?? '', 'spicetify', 'CustomApps', 'lyrics-plus');

if (!existsSync(TARGET_DIR)) {
  logger.error(`未找到 lyrics-plus 安装目录: ${TARGET_DIR}`);
  logger.error('请先执行: spicetify config custom_apps lyrics-plus && spicetify apply');
  process.exit(1);
}

/** 幂等字符串插入：若 anchor 存在且 insert 未存在，则插到 anchor 之前 */
function patchInsertBefore(content, anchor, insert, label) {
  if (content.includes(insert.trim().split('\n')[0].trim())) {
    logger.info(`跳过（已打过补丁）: ${label}`);
    return content;
  }
  const index = content.indexOf(anchor);
  if (index === -1) {
    logger.warn(`锚点未找到，跳过: ${label}（lyrics-plus 结构可能已变更，请人工检查）`);
    return content;
  }
  return content.slice(0, index) + insert + content.slice(index);
}

// ── 1. 复制整文件（新增/替换） ─────────────────────────────
copyFileSync(join(PATCH_DIR, 'ProviderQQMusic.js'), join(TARGET_DIR, 'ProviderQQMusic.js'));
logger.info('已复制: ProviderQQMusic.js');

copyFileSync(join(PATCH_DIR, 'ProviderLRCLIB.js'), join(TARGET_DIR, 'ProviderLRCLIB.js'));
logger.info('已复制: ProviderLRCLIB.js（含模糊搜索回退）');

// ── 2. index.js：注册 qqmusic 源配置 ────────────────────────
const indexFile = join(TARGET_DIR, 'index.js');
let indexContent = readFileSync(indexFile, 'utf8');
const neteaseConfigAnchor = `		netease: {
			on: getConfig("lyrics-plus:provider:netease:on", false),
			desc: "Crowdsourced lyrics provider ran by Chinese developers and users.",
			modes: [KARAOKE, SYNCED, UNSYNCED],
		},`;
const qqmusicConfig = `
		qqmusic: {
			on: getConfig("lyrics-plus:provider:qqmusic:on"),
			desc: "Lyrics sourced from QQ Music. Direct official API, best coverage for Chinese songs, lyrics in Simplified Chinese.",
			modes: [SYNCED, UNSYNCED],
		},`;
indexContent = patchInsertBefore(indexContent, neteaseConfigAnchor, qqmusicConfig, 'index.js qqmusic 配置');
writeFileSync(indexFile, indexContent);

// ── 3. Providers.js：挂载 qqmusic 实现 ──────────────────────
const providersFile = join(TARGET_DIR, 'Providers.js');
let providersContent = readFileSync(providersFile, 'utf8');

// 修复早期版本补丁的同行插入错误（若存在）
const brokenPattern = `	lrclib: async (info) => {	qqmusic: async (info) => {`;
if (providersContent.includes(brokenPattern)) {
  providersContent = providersContent.replace(brokenPattern, `	qqmusic: async (info) => {`);
  logger.warn('已修复早期补丁的同行插入错误（阶段1）');
}

// 修复阶段1遗留：lrclib 函数头被误删、函数体成孤儿（provider: "lrclib" 前缺声明行）
const orphanPattern = `		const result = {
			uri: info.uri,
			karaoke: null,
			synced: null,
			unsynced: null,
			provider: "lrclib",`;
if (providersContent.includes(orphanPattern) && !providersContent.includes(`	lrclib: async (info) => {`)) {
  providersContent = providersContent.replace(
    orphanPattern,
    `	lrclib: async (info) => {
		const result = {
			uri: info.uri,
			karaoke: null,
			synced: null,
			unsynced: null,
			provider: "lrclib",`,
  );
  logger.warn('已修复 lrclib 函数头缺失（阶段2）');
}

const lrclibImplAnchor = `	lrclib: async (info) => {`;
const qqmusicImpl = `	qqmusic: async (info) => {
		const result = {
			uri: info.uri,
			karaoke: null,
			synced: null,
			unsynced: null,
			provider: "QQMusic",
			copyright: null,
		};

		let body;
		try {
			body = await ProviderQQMusic.findLyrics(info);
		} catch (e) {
			ProviderQQMusic.debugLog("provider-error", String(e?.message ?? e).slice(0, 200));
			result.error = "No lyrics";
			return result;
		}

		const synced = ProviderQQMusic.getSynced(body);
		if (synced) {
			result.synced = synced;
			result.unsynced = synced;
		} else {
			result.error = "No lyrics";
		}
		return result;
	},

`;
providersContent = patchInsertBefore(providersContent, lrclibImplAnchor, qqmusicImpl, 'Providers.js qqmusic 实现');
writeFileSync(providersFile, providersContent);

// ── 4. manifest.json：注册子文件 ────────────────────────────
const manifestFile = join(TARGET_DIR, 'manifest.json');
const manifest = JSON.parse(readFileSync(manifestFile, 'utf8'));
if (!manifest.subfiles.includes('ProviderQQMusic.js')) {
  manifest.subfiles.push('ProviderQQMusic.js');
  // 保持原有缩进风格（tab）
  writeFileSync(manifestFile, JSON.stringify(manifest, null, '\t') + '\n');
  logger.info('已注册: manifest.json subfiles += ProviderQQMusic.js');
} else {
  logger.info('跳过（已注册）: manifest.json');
}

// ── 5. 可选：spicetify apply ─────────────────────────────────
if (process.argv.includes('--apply')) {
  const spicetify = join(process.env.LOCALAPPDATA ?? '', 'spicetify', 'spicetify.exe');
  const result = spawnSync(spicetify, ['apply'], { stdio: 'inherit' });
  if (result.status !== 0) {
    logger.error('spicetify apply 失败');
    process.exit(1);
  }
  logger.info('spicetify apply 完成');
} else {
  logger.info('补丁部署完成。请手动执行: spicetify apply');
}
