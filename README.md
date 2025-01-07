# WhitelistRMS

[![license](https://img.shields.io/badge/license-GPL--3.0-orange)](https://www.gnu.org/licenses/gpl-3.0.html)
[![HikariCP](https://img.shields.io/badge/HikariCP-5.0.1-blue)](https://github.com/brettwooldridge/HikariCP)
[![MariaDB](https://img.shields.io/badge/MariaDB--Client-3.1.4-blue)](https://mariadb.com/kb/en/about-mariadb-connector-j/)

一个基于 Velocity 平台的白名单管理插件，通过 MySQL 数据库进行白名单数据的存储和验证。

## 推荐搭配

推荐与 [WhitelistWebAdmin](https://github.com/XRain66/whitelistwebadmin) 一起使用，它提供了一个便捷的 Web 管理界面，可以：
- 可视化管理白名单玩家
- 处理临时登录请求

> [!WARNING]
> 由于目前插件并没有指令添加功能，如果不使用WhitelistWebAdmin，则需要自行寻找修改数据库的程序。

## 环境要求

- Java 17 或更高版本
- Velocity 3.1.1 或更高版本
- MySQL/MariaDB 数据库

## 使用说明

### 安装步骤

1. [下载 jar 文件](https://github.com/XRain66/WhitelistRMS/releases)并放入 Velocity 服务器的 `plugins` 目录
2. 启动服务器，插件会自动生成配置文件
3. 编辑 `plugins/whitelist-rms/config.yml` 配置文件
4. 重启服务器使配置生效

### 配置文件说明

配置文件位于 `plugins/whitelist-rms/config.yml`，包含以下内容：

```yaml
mysql:
  host: localhost     # 数据库地址
  port: 3306         # 数据库端口
  database: minecraft # 数据库名称
  username: root     # 数据库用户名
  password: password # 数据库密码

messages:
  not-whitelisted: "§c您没有白名单权限！" # 无白名单权限提示信息
```

### 数据库表结构

插件会自动创建以下数据表：

1. `whitelist` 表：存储白名单玩家
   - id: 自增主键
   - username: 玩家名称（唯一）

2. `temporarylogin` 表：临时登录请求管理
   - id: 自增主键
   - username: 玩家名称（唯一）
   - request_time: 请求时间
   - status: 状态（pending/approved/rejected/timeout）
   - update_time: 更新时间

### 功能特点

1. 白名单验证
   - 玩家首次连接时自动检查白名单
   - 未在白名单中的玩家将被拒绝连接

2. 临时登录请求
   - 未在白名单中的玩家可以发起临时登录请求
   - 请求状态包括：等待审核、已批准、已拒绝、已超时
   - 系统会自动清理超过90秒的临时登录请求

3. 数据库连接池
   - 使用 HikariCP 管理数据库连接
   - 自动重连和连接池优化
   - 连接超时和验证配置

## 构建

本项目使用 Gradle 进行构建。在项目根目录执行以下命令：

```bash
./gradlew build
```

构建完成后，你可以在 `build/libs` 目录下找到生成的 jar 文件。

## 依赖

- HikariCP 5.0.1 - 数据库连接池
- MariaDB Java Client 3.1.4 - 数据库驱动

## 许可证

本项目采用GPL-3.0开源协议