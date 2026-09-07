/**
 * DOMObserverSkill 测试（jsdom 模拟 DOM）
 * 覆盖：初始扫描转换、动态变化转换、防循环、输入元素跳过
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { JSDOM } from 'jsdom';
import { createDomObserver } from '../modules/domObserver.js';
import { createOpenCCService } from '../services/openccService.js';
import { createTextConversion } from '../core/textConversion.js';
import { createConfig } from '../config/config.js';

function setup() {
  const dom = new JSDOM('<!DOCTYPE html><html><body></body></html>', {
    pretendToBeVisual: true,
  });
  globalThis.document = dom.window.document;
  globalThis.MutationObserver = dom.window.MutationObserver;
  const conversion = createTextConversion(createOpenCCService());
  const config = createConfig(new Map()); // 内存存储，隔离测试环境
  const observer = createDomObserver({ textConversion: conversion, config });
  return { dom, observer };
}

const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

test('启动时全量扫描转换已有繁体文本', () => {
  const { dom, observer } = setup();
  dom.window.document.body.innerHTML =
    '<h1>周杰倫</h1><p>愛你一萬年</p><span>晴天</span>';
  observer.start();
  assert.equal(dom.window.document.querySelector('h1').textContent, '周杰伦');
  assert.equal(dom.window.document.querySelector('p').textContent, '爱你一万年');
  assert.equal(dom.window.document.querySelector('span').textContent, '晴天'); // 简体不变
  observer.stop();
});

test('动态新增节点自动转换', async () => {
  const { dom, observer } = setup();
  observer.start();
  const div = dom.window.document.createElement('div');
  div.textContent = '音樂播放中';
  dom.window.document.body.appendChild(div);
  await wait(50);
  assert.equal(div.textContent, '音乐播放中');
  observer.stop();
});

test('已有节点的文本变化自动转换（characterData）', async () => {
  const { dom, observer } = setup();
  const p = dom.window.document.createElement('p');
  p.textContent = '晴天';
  dom.window.document.body.appendChild(p);
  observer.start();
  p.firstChild.nodeValue = '廣東話'; // 模拟 React 更新文本
  await wait(50);
  assert.equal(p.textContent, '广东话');
  observer.stop();
});

test('防循环：转换写入不触发无限 mutation', async () => {
  const { dom, observer } = setup();
  observer.start();
  let mutationCount = 0;
  const counter = new dom.window.MutationObserver(() => mutationCount++);
  counter.observe(dom.window.document.body, {
    subtree: true,
    childList: true,
    characterData: true,
  });
  const div = dom.window.document.createElement('div');
  div.textContent = '繁體中文';
  dom.window.document.body.appendChild(div);
  await wait(150);
  assert.equal(div.textContent, '繁体中文'); // 转换发生
  assert.ok(mutationCount <= 2, `mutation 次数应为 1~2 次，实际 ${mutationCount}`);
  counter.disconnect();
  observer.stop();
});

test('输入类元素跳过（textarea / contenteditable）', async () => {
  const { dom, observer } = setup();
  dom.window.document.body.innerHTML =
    '<textarea>漢語</textarea><div contenteditable="true">漢語</div><p>漢語</p>';
  observer.start();
  await wait(50);
  assert.equal(dom.window.document.querySelector('textarea').value, '漢語'); // 不转换
  assert.equal(
    dom.window.document.querySelector('[contenteditable]').textContent,
    '漢語',
  ); // 不转换
  assert.equal(dom.window.document.querySelector('p').textContent, '汉语'); // 转换
  observer.stop();
});

test('停止后不再转换', async () => {
  const { dom, observer } = setup();
  observer.start();
  observer.stop();
  const div = dom.window.document.createElement('div');
  div.textContent = '音樂';
  dom.window.document.body.appendChild(div);
  await wait(50);
  assert.equal(div.textContent, '音樂'); // 已停止，保持原文
});
