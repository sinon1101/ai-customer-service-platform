-- ── Demo 种子数据(全新库一键起来即可登录)──
-- 仅在 MySQL 首次初始化(空数据卷)时随 01/02 脚本自动执行。
-- 密码格式为「盐@哈希」,对应明文均为 123456。
USE ai_customer_service;

-- 两个租户:acme / globex
INSERT IGNORE INTO tenant (id, name, code, status, daily_quota, create_time, update_time) VALUES
  (1, 'Acme客服',  'acme',   1, 10000, '2026-06-11 16:55:54', '2026-06-11 16:55:54'),
  (2, 'Globex客服','globex', 1, 10000, '2026-06-11 16:55:54', '2026-06-11 16:55:54');

-- Demo 账号:管理员各一 + acme 两名坐席(密码均 123456)
INSERT IGNORE INTO sys_user (id, tenant_id, username, password, nick_name, role, status, create_time, update_time) VALUES
  (1, 1, 'acme_admin',   'Tzz8qZgKK1P7deuoHaHO@09d8f516209b7741733c55878deb8538', 'Acme管理员',   'ADMIN', 1, '2026-06-11 16:55:54', '2026-06-11 16:55:54'),
  (2, 2, 'globex_admin', '7IyPicgNPUWNEJakrItU@d6f184a4d7d129b995048dea6bd173d6', 'Globex管理员', 'ADMIN', 1, '2026-06-11 16:55:54', '2026-06-11 16:55:54'),
  (3, 1, 'acme_agent1',  'Tzz8qZgKK1P7deuoHaHO@09d8f516209b7741733c55878deb8538', '坐席小A',      'AGENT', 1, '2026-06-16 21:15:39', '2026-06-18 19:39:10'),
  (4, 1, 'acme_agent2',  'Tzz8qZgKK1P7deuoHaHO@09d8f516209b7741733c55878deb8538', '坐席小B',      'AGENT', 1, '2026-06-16 21:15:39', '2026-06-18 19:39:10');
