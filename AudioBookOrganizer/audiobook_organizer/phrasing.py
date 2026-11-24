"""措辞库：把「这里原本有什么」说成人话。

这个模块只负责**怎么说**，判断逻辑在 codecut / tabulate / prose 里。
所有句子都力求「可被原文验证」——宁可少说，也不编造代码在干什么。
"""

from __future__ import annotations

import re

# ----------------------------------------------------------------------
# 命令 → 一句话用途（只用于兜底，有上下文时优先用上下文）
# ----------------------------------------------------------------------
COMMAND_GLOSS: dict[str, str] = {
    # 文件与目录
    "ls": "列出目录内容", "dir": "列出目录内容", "cd": "切换当前目录",
    "pwd": "显示当前所在目录", "cp": "复制文件或目录", "mv": "移动或重命名",
    "rm": "删除文件或目录", "mkdir": "创建目录", "rmdir": "删除空目录",
    "ln": "创建链接", "touch": "更新文件时间戳或新建空文件", "file": "判断文件类型",
    "stat": "查看文件的详细状态", "find": "按条件查找文件", "locate": "按数据库查找文件",
    "which": "显示可执行文件的位置", "whereis": "查找程序的帮助文档和源码位置",
    "basename": "取路径中的文件名部分", "dirname": "取路径中的目录部分",
    "tree": "以树形显示目录", "readlink": "显示符号链接指向的目标",
    # 查看与文本处理
    "cat": "输出文件内容", "less": "分页查看文件", "more": "分页查看文件",
    "head": "查看文件开头", "tail": "查看文件结尾", "wc": "统计行数、词数和字节数",
    "grep": "在文本中查找匹配的行", "egrep": "用扩展正则查找", "fgrep": "按固定字符串查找",
    "sed": "流式编辑文本", "awk": "按字段处理文本", "cut": "按列截取文本",
    "sort": "排序", "uniq": "去除相邻重复行", "tr": "字符替换或删除",
    "diff": "比较两个文件的差异", "patch": "应用补丁", "comm": "比较两个有序文件",
    "tee": "分流输出到文件和屏幕", "xargs": "把标准输入拼成命令行参数",
    "paste": "按列合并文件", "join": "按公共字段连接两个文件", "split": "切分文件",
    "nl": "给文本加行号", "fmt": "重排段落格式", "pr": "排版输出", "fold": "按宽度折行",
    "iconv": "转换文本编码", "dos2unix": "转换行尾格式",
    # 权限与用户
    "chmod": "修改文件权限", "chown": "修改文件所有者", "chgrp": "修改文件所属组",
    "umask": "设置新建文件的默认权限", "su": "切换用户", "sudo": "以其他身份执行命令",
    "passwd": "修改密码", "useradd": "新建用户", "userdel": "删除用户",
    "groupadd": "新建用户组", "id": "查看用户和组标识", "whoami": "显示当前用户",
    "groups": "显示用户所属的组", "who": "查看登录用户", "w": "查看登录用户及其活动",
    "last": "查看登录历史", "chsh": "修改登录 shell", "newgrp": "切换当前组",
    # 进程与作业
    "ps": "查看进程状态", "top": "实时查看系统负载和进程", "kill": "向进程发送信号",
    "killall": "按名字结束进程", "jobs": "查看当前作业", "bg": "把作业放到后台",
    "fg": "把作业调回前台", "nohup": "让命令忽略挂断信号", "nice": "调整进程优先级",
    "renice": "修改已运行进程的优先级", "sleep": "等待一段时间", "wait": "等待子进程结束",
    "pstree": "以树形显示进程", "pgrep": "按名字查找进程", "pkill": "按名字结束进程",
    # 磁盘与文件系统
    "df": "查看磁盘空间", "du": "统计目录占用", "mount": "挂载文件系统",
    "umount": "卸载文件系统", "fdisk": "分区表操作", "mkfs": "格式化文件系统",
    "fsck": "检查并修复文件系统", "dd": "按块复制数据", "sync": "把缓存写入磁盘",
    "blkid": "查看块设备标识", "lsblk": "列出块设备",
    # 打包压缩
    "tar": "打包或解包归档", "gzip": "gzip 压缩", "gunzip": "gzip 解压",
    "bzip2": "bzip2 压缩", "zip": "打包成 zip", "unzip": "解压 zip", "xz": "xz 压缩",
    # 网络
    "ping": "测试网络连通性", "traceroute": "追踪路由", "ip": "查看和配置网络接口",
    "ifconfig": "查看网络接口", "netstat": "查看网络连接", "ss": "查看套接字统计",
    "curl": "从网络取数据", "wget": "下载文件", "ssh": "远程登录", "scp": "跨主机复制文件",
    "rsync": "增量同步文件", "sftp": "安全文件传输", "ftp": "文件传输",
    "nc": "网络连接与监听", "dig": "查询 DNS", "nslookup": "查询 DNS",
    "hostname": "查看或设置主机名", "host": "解析域名",
    # 系统与服务
    "date": "显示或设置日期时间", "cal": "显示日历", "uptime": "查看运行时长和负载",
    "free": "查看内存使用", "uname": "查看系统信息", "dmesg": "查看内核消息",
    "journalctl": "查看系统日志", "systemctl": "管理系统服务", "service": "管理系统服务",
    "shutdown": "关机或重启", "reboot": "重启", "crontab": "编辑定时任务",
    "at": "安排一次性任务", "env": "查看环境变量", "export": "设置环境变量",
    "alias": "定义命令别名", "unalias": "取消别名", "history": "查看命令历史",
    "echo": "输出文本", "printf": "格式化输出", "read": "读取输入",
    "test": "判断条件", "expr": "算术或字符串运算", "seq": "生成数字序列",
    "type": "显示命令的类型", "help": "查看 bash 内置命令帮助", "man": "查看手册页",
    "info": "查看 info 文档", "apropos": "按关键词搜索手册", "whatis": "显示命令的简短说明",
    "clear": "清屏", "exit": "退出", "source": "在当前 shell 中执行脚本",
    "exec": "替换当前 shell 进程", "set": "设置 shell 选项", "unset": "取消变量",
    "shift": "移动位置参数", "trap": "设置信号处理", "ulimit": "限制资源使用",
    "time": "统计命令耗时", "ltrace": "跟踪库调用", "strace": "跟踪系统调用",
    "lsof": "列出被打开的文件", "md5sum": "计算校验和", "sha256sum": "计算校验和",
    "base64": "base64 编解码", "od": "按八进制等格式转储", "hexdump": "十六进制转储",
    "strings": "从二进制中提取可打印字符串", "gzip": "压缩",
    # 脚本与编程
    "if": "条件判断", "then": "条件成立时执行", "else": "条件不成立时执行",
    "elif": "多重条件判断", "fi": "结束条件判断", "for": "遍历循环",
    "while": "当型循环", "until": "直到型循环", "do": "循环体开始", "done": "循环体结束",
    "case": "多分支选择", "esac": "结束分支", "function": "定义函数",
    "return": "从函数返回", "local": "声明局部变量", "break": "跳出循环",
    "continue": "跳到下一轮循环", "cd": "切换目录",
}

#: 语言识别
_LANG_RULES: list[tuple[str, str]] = [
    (r"^#!.*\b(bash|sh|zsh)\b", "bash 脚本"),
    (r"^#!.*\bpython", "Python 脚本"),
    (r"^#!.*\bperl", "Perl 脚本"),
    (r"^#!.*\bnode", "Node 脚本"),
    (r"\bSELECT\b[\s\S]{0,400}\bFROM\b", "SQL 语句"),
    (r"^\s*(def |class |import |from \w+ import)", "Python 代码"),
    (r"\bfunction\s+\w+\s*\(|\bvar\s+\w+\s*=|\bconst\s+\w+\s*=|=>", "JavaScript 代码"),
    (r"\bpublic\s+(static\s+)?(void|class|int|String)\b", "Java 代码"),
    (r"^\s*#include\s*<", "C 代码"),
]

_STRUCTURE_RULES: list[tuple[re.Pattern, str]] = [
    (re.compile(r"\bwhile\b[\s\S]*\bdone\b"), "一个 while 循环"),
    (re.compile(r"\buntil\b[\s\S]*\bdone\b"), "一个 until 循环"),
    (re.compile(r"\bfor\b[\s\S]*\bdone\b"), "一个 for 循环"),
    (re.compile(r"\bcase\b[\s\S]*\besac\b"), "一个 case 分支"),
    (re.compile(r"\bif\b[\s\S]*\bfi\b"), "一段条件判断"),
    (re.compile(r"^\s*\w+\s*\(\)\s*\{", re.M), "一个函数定义"),
    (re.compile(r"\bSELECT\b"), "一条查询语句"),
]


def lang_of(code: str) -> str:
    for pattern, name in _LANG_RULES:
        if re.search(pattern, code, re.M):
            return name
    return ""


def structure_of(code: str) -> str:
    for pattern, name in _STRUCTURE_RULES:
        if pattern.search(code):
            return name
    return ""


def gloss_of(command: str) -> str:
    """命令 → 用途。没收录就返回空串，绝不瞎猜。"""
    base = command.strip().split()[0] if command.strip() else ""
    base = base.lstrip("$").strip()
    return COMMAND_GLOSS.get(base, "")


# ----------------------------------------------------------------------
# 句库
# ----------------------------------------------------------------------
#: 引出一段示例的开头，轮流用，避免通篇「接下来」
LEADS = ("接下来", "这里", "紧接着", "下面", "此处")

#: 跟在「如下所示」「格式如下」这类句子后面的接续词
FOLLOW_UPS = ("", "这段", "这一块", "它")

CODE_INTRO = {
    "session": "是命令行的实际操作示例",
    "script": "是一段脚本",
    "syntax": "是这条命令的语法格式",
    "output": "是前面命令的输出结果",
    "code": "是一段代码示例",
    "ascii": "是一张用字符拼出的示意图",
}


def join(*parts: str) -> str:
    return "".join(p for p in parts if p)


def cn_count(n: int) -> str:
    """5 → 五，12 → 12（大数保留阿拉伯数字，念起来都不别扭）。"""
    small = {0: "零", 1: "一", 2: "两", 3: "三", 4: "四", 5: "五",
             6: "六", 7: "七", 8: "八", 9: "九", 10: "十"}
    return small.get(n, str(n))
