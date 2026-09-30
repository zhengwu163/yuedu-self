"""本地单文件配置、随机桥接 Token 以及累计试用预算。"""
import os
import re
import secrets
import sqlite3
from contextlib import closing, contextmanager
from dataclasses import dataclass, field
from pathlib import Path

from protocol import LocalQuotaError, TEXT_MODEL, TTS_MODEL, require

LIMITS = {"analysis_requests": 20, "analysis_characters": 24000,
          "tts_requests": 100, "tts_characters": 5000}


class UsageGuard:
    """先持久预占再联网；SQLite IMMEDIATE 防多线程/多进程重复超领。"""

    def __init__(self, path, limits=None):
        self.path = Path(path)
        self.limits = dict(LIMITS if limits is None else limits)
        self._blocked = False

    @contextmanager
    def _transaction(self):
        try:
            require(not self.path.is_symlink())
            # 父目录必须已存在；使用者提供项目根路径，不能静默创建任意目录。
            try:
                fd = os.open(self.path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            except FileExistsError:
                created = False
            else:
                os.close(fd)
                created = True
            with closing(sqlite3.connect(self.path, timeout=2)) as db:
                db.execute("PRAGMA synchronous=FULL")
                with db:
                    db.execute("BEGIN IMMEDIATE")
                    # 只有本次 O_EXCL 创建的文件允许初始化。既有空文件/缺表/缺行
                    # 属于损坏，不能以“自动修复”把累计计数与熔断恢复成零。
                    if created:
                        db.execute("""CREATE TABLE budget (
                            id INTEGER PRIMARY KEY CHECK(id=1), version INTEGER NOT NULL,
                            token TEXT NOT NULL, blocked INTEGER NOT NULL,
                            analysis_requests INTEGER NOT NULL, analysis_characters INTEGER NOT NULL,
                            tts_requests INTEGER NOT NULL, tts_characters INTEGER NOT NULL)""")
                        db.execute("INSERT INTO budget VALUES(1,1,?,0,0,0,0,0)",
                                   (secrets.token_urlsafe(32),))
                    row = db.execute("SELECT * FROM budget WHERE id=1").fetchone()
                    require(row and row[1] == 1 and row[3] in (0, 1)
                            and isinstance(row[2], str) and len(row[2]) >= 32
                            and all(type(v) is int and v >= 0 for v in row[4:]))
                    yield db, row
        except (OSError, sqlite3.Error, ValueError):
            self._blocked = True
            raise LocalQuotaError() from None

    def token(self):
        with self._transaction() as (_, row):
            return row[2]

    def available(self, kind):
        if self._blocked:
            return False
        try:
            with self._transaction() as (_, row):
                index = 4 if kind == "analysis" else 6
                return (not row[3] and row[index] < self.limits[kind + "_requests"]
                        and row[index + 1] < self.limits[kind + "_characters"])
        except LocalQuotaError:
            return False

    def reserve(self, kind, count, requests=1):
        if (self._blocked or kind not in ("analysis", "tts") or type(count) is not int
                or count <= 0 or type(requests) is not int or requests <= 0):
            raise LocalQuotaError()
        with self._transaction() as (db, row):
            index = 4 if kind == "analysis" else 6
            if (row[3] or row[index] + requests > self.limits[kind + "_requests"]
                    or row[index + 1] + count > self.limits[kind + "_characters"]):
                raise LocalQuotaError()
            db.execute(f"UPDATE budget SET {kind}_requests={kind}_requests+?, "
                       f"{kind}_characters={kind}_characters+? WHERE id=1", (requests, count))

    def block_cloud_quota(self):
        self._blocked = True
        with self._transaction() as (db, _):
            db.execute("UPDATE budget SET blocked=1 WHERE id=1")


@dataclass(frozen=True)
class BridgeConfig:
    dashscope_api_key: str = field(default="", repr=False)
    bridge_token: str = field(default_factory=lambda: secrets.token_urlsafe(32), repr=False)
    host: str = "127.0.0.1"
    port: int = 8787
    ffmpeg_path: str = "ffmpeg"
    state_path: Path = Path("novel-audio.local.state.db")
    text_model: str = TEXT_MODEL
    tts_model: str = TTS_MODEL

    @classmethod
    def from_env_file(cls, path):
        path = Path(path)
        require(not path.is_symlink() and path.stat().st_size <= 16 * 1024)
        values = {}
        allowed = {"DASHSCOPE_API_KEY", "BRIDGE_TOKEN", "BRIDGE_HOST", "BRIDGE_PORT",
                   "TEXT_MODEL", "TTS_MODEL"}
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            key, sep, value = line.partition("=")
            key, value = key.strip(), value.strip()
            require(sep and key in allowed and key not in values)
            if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
                value = value[1:-1]
            values[key] = value
        api_key = values.get("DASHSCOPE_API_KEY", "")
        # API Key 是不透明的 Bearer 令牌（RFC 6750 §2.1），不能假定内部不含点号。
        # 保留原值；仅限制合法 header 字符、尾部 padding 与总长度，不验证云端权限。
        require(not api_key or (len(api_key) <= 4096
                               and re.fullmatch(r"[A-Za-z0-9._~+/\-]+=*", api_key)))
        require(values.get("BRIDGE_HOST", "127.0.0.1") == "127.0.0.1"
                and values.get("TEXT_MODEL", TEXT_MODEL) == TEXT_MODEL
                and values.get("TTS_MODEL", TTS_MODEL) == TTS_MODEL)
        port = int(values.get("BRIDGE_PORT", "8787"))
        require(1024 <= port <= 65535)
        token = values.get("BRIDGE_TOKEN")
        require(token is None or re.fullmatch(r"[A-Za-z0-9_-]{1,4096}", token))
        state = path.parent / "novel-audio.local.state.db"
        return cls(dashscope_api_key=api_key, bridge_token=token or UsageGuard(state).token(),
                   port=port, state_path=state)

    def cloud_ready(self):
        return bool(self.dashscope_api_key)

    def startup_summary(self):
        return ("API Key " + ("已配置" if self.cloud_ready() else "未配置")
                + f"；模型：{self.text_model} / {self.tts_model}")
