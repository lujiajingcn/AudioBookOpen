"""AudioBookLocalTtsTool —— 本地电子书语音合成 + 一键入库/上传工具。

模块划分（依赖方向自上而下，无环）：

    app.config      配置数据与持久化（不含任何业务逻辑）
    app.core.*      纯逻辑：切句、正文提取、MOSS 桥接、MySQL 存取、合成编排、上传
    app.ui.*        PySide6 界面（只做参数收集与展示，不含业务规则）

界面层不直接碰 MySQL、不直接碰 MOSS 引擎，一律经由 app.core，
这样命令行复现与单元测试都不需要开窗口。
"""

__version__ = "1.0.0"
