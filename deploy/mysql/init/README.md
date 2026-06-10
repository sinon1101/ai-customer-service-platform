# MySQL 初始化脚本目录

放在这里的 `*.sql` 文件会在 **MySQL 容器首次启动时**(数据卷为空时)按文件名顺序自动执行,
用来建表、初始化数据。

- 数据库 `ai_customer_service` 已由 compose 的 `MYSQL_DATABASE` 自动创建,无需在脚本里再 `CREATE DATABASE`。
- 等我们设计好新项目的表结构,把建表 SQL 放进来(如 `01-schema.sql`、`02-seed.sql`)。
- 注意:只有「数据卷为空的首次启动」才会执行;改了脚本要重新初始化,需 `docker compose down -v` 清空数据卷后再 `up`。
