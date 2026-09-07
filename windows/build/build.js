/**
 * 构建脚本：esbuild 打包为单文件 Spicetify 扩展
 * --deploy：打包后复制到 Spicetify Extensions 目录
 */
import { build } from 'esbuild';
import { copyFileSync, mkdirSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { homedir } from 'node:os';
import { logger } from '../utils/logger.js';

const ROOT = dirname(dirname(fileURLToPath(import.meta.url)));
const DIST_FILE = join(ROOT, 'dist', 't2s-converter.js');

const isDeploy = process.argv.includes('--deploy');

await build({
  entryPoints: [join(ROOT, 'api', 'index.js')],
  bundle: true,
  format: 'iife', // Spicetify 扩展经经典 <script> 标签加载，必须 IIFE（ESM 的 export 会语法报错）
  outfile: DIST_FILE,
  minify: true,
  target: ['chrome110'],
  legalComments: 'none',
  logLevel: 'info',
});

logger.info(`打包完成: ${DIST_FILE}`);

if (isDeploy) {
  // Spicetify 扩展目录：%APPDATA%\spicetify\Extensions
  const extDir = join(
    process.env.APPDATA ?? join(homedir(), 'AppData', 'Roaming'),
    'spicetify',
    'Extensions',
  );
  if (!existsSync(extDir)) {
    mkdirSync(extDir, { recursive: true });
  }
  const target = join(extDir, 't2s-converter.js');
  copyFileSync(DIST_FILE, target);
  logger.info(`已部署: ${target}`);
  logger.info('后续步骤: spicetify config extensions t2s-converter.js && spicetify apply');
}
