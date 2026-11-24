# AudioBookOpen

把电子书自动合成为有声书，并在 Web、Android、桌面端提供「边读边听、点哪句播哪句、句级高亮」体验的完整系统。

本仓库是脱敏后的公开副本：原始工程中的服务器地址、密钥、个人路径等均已替换为示例值或占位符。

## 主要功能

- 电子书解析：TXT / Markdown / EPUB / MOBI / AZW / PDF，自动生成正文与目录。
- 批量合成：按句切段，调用 TTS 引擎逐段生成音频，支持断点续跑、失败重试、分段音色。
- 音色克隆与预设音色：支持参考音频（≤8 秒）克隆，也支持引擎内置预设音色。
- 多节点调度：合成节点由数据库表 `tts_nodes` 管理，支持启停、权重、熔断与故障转移。
- 阅读体验：正文与音频段映射、句级高亮、长按菜单（复制 / 收藏 / 翻译 / 查字典 / 从此处读）。
- 离线英汉词典（ECDICT）、单词本、书城、书架、收藏、讨论区、积分充值。
- 跨端同步：阅读进度、书架、收藏、单词本、设置等在 Android / Web / 桌面端之间同步。

## 目录结构

| 目录 | 技术栈 | 说明 |
| --- | --- | --- |
| `AudioBookServer` | Java 17 / Spring Boot 3.2 / Maven | 后端单体服务，提供全部 REST API、TTS 编排、支付与书城 |
| `AudioBookPlayer` | Kotlin / Android（AGP 8.6.1，compileSdk 34） | Android 客户端，含端侧 MOSS-TTS-Nano 推理 |
| `AudioBookWeb` | Vue 3 / TypeScript / Vite / Ant Design Vue | Web 客户端（书城、阅读、听书、合成） |
| `AudioBookManager` | Vue 3 / TypeScript / Vite / Ant Design Vue | 管理后台（用户、书城、节点、版本发布） |
| `AudioBookShared` | TypeScript | Web 与 Manager 共用的 API 契约、认证与 HTTP 基础设施 |
| `AudioBookPlayerPC` | Python / PySide6 | 桌面阅读器，支持云端音频与跨端同步 |
| `AudioBookLocalTtsTool` | Python / PySide6 | 本机批量合成工具，可把产物发布到书城 |
| `AudioBookOrganizer` | Python | 把计算机类专业书精简改写为适合听书的 EPUB / TXT（纯标准库） |
| `scripts` | Batch / Shell | 环境搭建与构建辅助脚本 |

更详细的架构说明见 [`AudioBook工程架构文档.md`](./AudioBook工程架构文档.md)。

## 技术栈

- 后端：Spring Boot 3.2、Spring Security + JWT（jjwt 0.12.6）、Spring Data JPA / Hibernate、MySQL、Redis、WebClient、PDFBox 3.0.2、JAVE2/FFmpeg。
- 前端：Vue 3、TypeScript、Vite 5、Ant Design Vue 4、Pinia。
- Android：Kotlin、AGP 8.6.1、Room / SQLite、PDFium（`io.legere:pdfiumandroid:1.0.31`）。
- 桌面端：Python 3.10+、PySide6。
- TTS 引擎：MOSS-TTS-Nano（中文，默认）；Pocket TTS（拉丁语种，需自行启用）。

## 快速开始

### 前置条件

- JDK 17、Maven 3.9+
- Node.js 18+ 与 npm
- Python 3.10+
- MySQL 8、Redis
- Android Studio（仅构建 Android 端时需要，compileSdk 34）
- 可选：MOSS-TTS-Nano 引擎节点（没有它系统仍可启动，只是无法合成音频）

### 1. 初始化数据库

```bash
mysql -u root -p < AudioBookServer/init_database.sql
```

脚本会创建 `audiobook_db` 及全部表。生产环境使用 `ddl-auto=validate`，升级前必须先执行脚本。

### 2. 启动后端

开发模式（默认连接 `localhost:3306` 的 `audiobook_db` 与 `localhost:6379` 的 Redis）：

```bash
cd AudioBookServer
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

服务监听 `8080`。开发配置中的数据库账号、密码与 JWT 密钥都是占位值，可通过环境变量 `DB_USERNAME`、`DB_PASSWORD`、`JWT_SECRET` 覆盖。

生产模式必须显式注入配置，`JWT_SECRET` 不能为空：

```bash
export SPRING_PROFILES_ACTIVE=prod
export DB_URL='jdbc:mysql://localhost:3306/audiobook_db'
export DB_USERNAME='<your-user>'
export DB_PASSWORD='<your-password>'
export JWT_SECRET='<your-random-secret>'
java -jar target/audiobookserver-1.0.0.jar
```

其余可选环境变量见 `AudioBookServer/src/main/resources/application*.yml`，包括 `REDIS_*`、`MOSS_TTS_URL`、`CORS_ALLOWED_ORIGINS`、支付渠道密钥等。支付密钥留空时会自动降级为 Mock 支付。

### 3. 启动 Web 与管理后台

```bash
cd AudioBookShared && npm install
cd ../AudioBookWeb && npm install && npm run dev     # http://localhost:3000
cd ../AudioBookManager && npm install && npm run dev # http://localhost:3001
```

两个前端都把 `/api` 代理到 `http://localhost:8080`；Web 端可用 `VITE_API_TARGET` 指向别的后端地址。

构建静态产物：

```bash
npm run build
```

### 4. 构建 Android 客户端

用 Android Studio 打开 `AudioBookPlayer/` 目录（仓库未包含 Gradle Wrapper，可用本机 Gradle 或 Android Studio 的 Gradle 面板执行任务）。后端地址默认写在 `app/build.gradle.kts`，可用属性覆盖：

```bash
gradle assembleDebug -PAUDIOBOOK_DEV_BASE_URL=http://10.0.2.2:8080/
```

release 签名需要本地 `local.properties` 提供 `AUDIOBOOK_STORE_FILE` / `AUDIOBOOK_STORE_PASSWORD` / `AUDIOBOOK_KEY_ALIAS` / `AUDIOBOOK_KEY_PASSWORD`，该文件不会入库。

### 5. 桌面端

```bash
cd AudioBookPlayerPC
pip install -r requirements.txt
python run.py
```

本机合成工具同理：

```bash
cd AudioBookLocalTtsTool
pip install -r requirements.txt
python run.py
```

`AudioBookLocalTtsTool` 的服务器地址、SSH 密钥路径等默认是示例值，需在界面或 `%APPDATA%/AudioBookLocalTtsTool/config.json` 中按自己的环境填写。

### 6. 配置 TTS 引擎

后端启动后，在管理后台的「合成节点」页面添加引擎节点，地址形如 `http://<host>:18083/api/generate`（MOSS）。`application.yml` 中的 `moss.tts.url` 只是首次部署的种子值，之后节点以 `tts_nodes` 表为准。Pocket TTS 默认关闭，设置 `POCKET_TTS_ENABLED=true` 并配置音色后才会对拉丁语种分流。

## 数据与隐私说明

- 音频文件落磁盘，不写入数据库；`audio_segments.audio_data` 是历史遗留列，恒为 NULL。
- 所有敏感配置（数据库口令、JWT 密钥、支付密钥、上传目录等）通过环境变量或本地未入库文件提供。
- 本公开仓库中的 IP、域名、口令均为示例值，部署时必须替换。

## 许可

本项目采用 [MIT License](./LICENSE)。

第三方组件（包括语音合成引擎、模型、数据集与解析库）各有自己的许可条款，详见 [`第三方开源项目清单与许可说明.md`](./第三方开源项目清单与许可说明.md)。分发或商用前请自行核对。
