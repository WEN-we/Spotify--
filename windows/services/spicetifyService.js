/**
 * SpicetifyIntegrationSkill：Spicetify API 集成
 * 规则：Spicetify 不可用时静默降级（返回 null / false），绝不抛错
 */

/** 获取全局 Spicetify 对象（不可用时返回 null） */
export function getSpicetify() {
  return typeof Spicetify !== 'undefined' ? Spicetify : null;
}

/**
 * 注册顶部菜单项（需要 Spicetify.Menu 支持）
 * @param {{name: string, isEnabled: () => boolean, onClick: (item: object) => void}} params
 * @returns {boolean} 注册成功返回 true；Spicetify 不可用返回 false（降级为无菜单）
 */
export function registerMenuItem({ name, isEnabled, onClick }) {
  const spicetify = getSpicetify();
  if (!spicetify?.Menu?.Item) return false;
  try {
    new spicetify.Menu.Item(name, isEnabled(), (item) => onClick(item)).register();
    return true;
  } catch {
    return false;
  }
}
