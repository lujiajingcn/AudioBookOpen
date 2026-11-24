-- ============================================================================
-- tts_nodes —— MOSS-TTS-Nano 合成节点配置表
--
-- 何时需要执行本脚本：
--   生产环境 spring.jpa.hibernate.ddl-auto=validate，Hibernate 只校验不建表，
--   升级到「多节点调度」版本前必须先建好这张表，否则应用启动时校验不到
--   实体 TtsNode 对应的表，直接启动失败。
--   本地 dev 环境 ddl-auto=update 会自动建表，无需执行。
--
--   ⚠️ 请先备份数据库。上线前建议在预发环境先跑一遍完整启动验证。
--
-- 若执行后仍报 schema validation 失败（列类型不匹配），最可靠的取法是在
-- 任意 dev 库上用 ddl-auto=update 让 Hibernate 自己建表，再执行
--   SHOW CREATE TABLE tts_nodes;
-- 把结果原样搬到生产库即可 —— 那是 Hibernate 自己认可的定义。
--
-- 建表后无需手动插入数据：应用首次启动发现表为空时，会用配置项
-- moss.tts.url（生产为环境变量 MOSS_TTS_URL）自动落一条默认节点记录。
-- 若该变量也为空，则以「无可用节点」启动，随后在管理后台
-- 「合成节点管理」页面添加即可。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `tts_nodes` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT,
  `name`       VARCHAR(64)  NOT NULL                COMMENT '展示名',
  `url`        VARCHAR(255) NOT NULL                COMMENT '完整合成端点，如 http://10.0.0.11:18083/api/generate',
  `weight`     INT          NOT NULL DEFAULT 1      COMMENT '权重；最少在途选点中仅在途数相同时作为次级排序，越大越优先',
  `enabled`    BIT(1)       NOT NULL DEFAULT b'1'   COMMENT '是否参与调度；停用后立即停止分配新请求但不打断在途调用',
  `sort_order` INT          NOT NULL DEFAULT 0      COMMENT '展示与选点排序序号',
  `remark`     VARCHAR(255) DEFAULT NULL            COMMENT '备注（机房、GPU 型号等）',
  `created_at` DATETIME(6)  NOT NULL                COMMENT '创建时间',
  `updated_at` DATETIME(6)  DEFAULT NULL            COMMENT '最后修改时间',
  PRIMARY KEY (`id`),
  -- 同一台机器登记两次会被调度器当成两个节点，实际负载翻倍，必须防重
  UNIQUE KEY `uk_tts_node_url` (`url`),
  -- 注册中心每次刷新走 enabled=? ORDER BY sort_order, id，走这条索引避免全表扫描
  KEY `idx_tts_node_enabled_sort` (`enabled`, `sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MOSS-TTS-Nano 合成节点配置';
