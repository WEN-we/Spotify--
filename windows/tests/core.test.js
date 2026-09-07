/**
 * TextConversionSkill 单元测试（注入假转换服务，隔离外部依赖）
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createTextConversion, hasCJK } from '../core/textConversion.js';

/** 假转换服务：仅映射两个字，便于断言 */
function createFakeService(throwOnCall = false) {
  return {
    convert(text) {
      if (throwOnCall) throw new Error('mock error');
      return text.replaceAll('漢語', '汉语').replaceAll('愛', '爱');
    },
  };
}

test('hasCJK：识别中文', () => {
  assert.equal(hasCJK('漢語'), true);
  assert.equal(hasCJK('abc 汉语'), true);
  assert.equal(hasCJK('hello world'), false);
  assert.equal(hasCJK(''), false);
  assert.equal(hasCJK(null), false);
  assert.equal(hasCJK(undefined), false);
});

test('toSimplified：繁体转简体', () => {
  const conversion = createTextConversion(createFakeService());
  assert.equal(conversion.toSimplified('漢語'), '汉语');
  assert.equal(conversion.toSimplified('我愛你'), '我爱你');
});

test('toSimplified：无中文原样返回（零开销路径）', () => {
  const conversion = createTextConversion(createFakeService());
  assert.equal(conversion.toSimplified('Hello World'), 'Hello World');
  assert.equal(conversion.toSimplified(''), '');
});

test('toSimplified：转换服务抛异常时降级返回原文', () => {
  const conversion = createTextConversion(createFakeService(true));
  assert.equal(conversion.toSimplified('漢語'), '漢語');
});

test('containsTraditional：检测繁体', () => {
  const conversion = createTextConversion(createFakeService());
  assert.equal(conversion.containsTraditional('漢語'), true);
  assert.equal(conversion.containsTraditional('汉语'), false); // 简体不误报
  assert.equal(conversion.containsTraditional('English'), false);
  assert.equal(conversion.containsTraditional(''), false);
});
