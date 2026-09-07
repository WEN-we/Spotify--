/**
 * opencc-js 真实字典集成测试（验证真实转换质量与三种方案可用性）
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createOpenCCService } from '../services/openccService.js';
import { createTextConversion } from '../core/textConversion.js';

const conversion = createTextConversion(createOpenCCService());

test('通用繁体转简体（t2s）', () => {
  assert.equal(conversion.toSimplified('漢語', 't2s'), '汉语');
  assert.equal(conversion.toSimplified('音樂', 't2s'), '音乐');
  assert.equal(conversion.toSimplified('愛你一萬年', 't2s'), '爱你一万年');
});

test('台湾繁体转简体（tw2sp）', () => {
  assert.equal(conversion.toSimplified('面試', 'tw2sp'), '面试');
  assert.equal(conversion.toSimplified('永遠的愛', 'tw2sp'), '永远的爱');
});

test('香港繁体转简体（hk2s）', () => {
  assert.equal(conversion.toSimplified('音樂', 'hk2s'), '音乐');
  assert.equal(conversion.toSimplified('廣東話', 'hk2s'), '广东话');
});

test('简体/英文/混合文本不被破坏', () => {
  assert.equal(conversion.toSimplified('你好世界', 't2s'), '你好世界');
  assert.equal(conversion.toSimplified('Hello World', 't2s'), 'Hello World');
  assert.equal(
    conversion.toSimplified('周杰倫 - 晴天 (Official MV)', 't2s'),
    '周杰伦 - 晴天 (Official MV)',
  );
});

test('containsTraditional 不误报简体', () => {
  assert.equal(conversion.containsTraditional('晴天', 't2s'), false);
  assert.equal(conversion.containsTraditional('周杰倫', 't2s'), true);
});
