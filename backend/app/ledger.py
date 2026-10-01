"""Who is asking, and how many guided tasks they may start.

A small SQLite ledger, written atomically. It records identities (a random
id and the hash of a secret only that install holds), and tasks: one row per
guided task, keyed by the id the phone chose when the task began, so asking
twice — a retry, a double tap, a lost reply — admits it once and charges once.

What a credit pays for is a task: the person's goal and every step,
question and recovery on the way to it. Planning requests are counted
against the task only as a guard against runaway loops; they never cost a
credit. A task is admitted by reserving a credit, and the credit is spent
only once Dot has actually helped with it — its first useful answer. A
task that ends before that (the service failed, the task was one Dot could
not do) is released and its credit comes back; so is one left unused for
hours.

Nothing here stores a goal, a screen, a question or an answer.
"""

from __future__ import annotations

import hashlib
import hmac
import math
import os
import secrets
import sqlite3
import threading
import time
from dataclasses import dataclass, field

WEEK_S = 7 * 24 * 3600.0

# The free allowance, and Plus's. Free is two Ask tasks to try TapBack and
# one complete course; there is no recurring weekly free task. Plus is ten
# Ask tasks per subscription week and every course.
WELCOME_TASKS = 2
FREE_WEEKLY_TASKS = 0
PLUS_TASKS_PER_PERIOD = 10

# The one full course free for everyone. An operator may name others
# (comma-separated ids in DOT_FREE_COURSES), for a demonstration or a promotion.
FREE_COURSES = frozenset(
    c.strip() for c in os.environ.get("DOT_FREE_COURSES", "maps").split(",") if c.strip()
) or frozenset({"maps"})

# Guards against a loop, not prices: generous for any real task.
MAX_REQUESTS_PER_TASK = 150
MAX_REQUESTS_PER_DAY = 800

# A reservation nobody used is released after this long.
STALE_RESERVATION_S = 6 * 3600.0

SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
    user_id     TEXT PRIMARY KEY,
    key_hash    TEXT NOT NULL,
    created_at  REAL NOT NULL,
    day         TEXT NOT NULL DEFAULT '',
    day_count   INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS tasks (
    task_id     TEXT PRIMARY KEY,
    user_id     TEXT NOT NULL REFERENCES users(user_id),
    kind        TEXT NOT NULL,
    course_id   TEXT NOT NULL DEFAULT '',
    source      TEXT NOT NULL,
    period      TEXT NOT NULL DEFAULT '',
    state       TEXT NOT NULL,
    admitted_at REAL NOT NULL,
    requests    INTEGER NOT NULL DEFAULT 0,
    useful      INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS tasks_by_user ON tasks(user_id, source, period, state);
CREATE TABLE IF NOT EXISTS preview_grants (
    user_id     TEXT PRIMARY KEY REFERENCES users(user_id),
    granted_at  REAL NOT NULL,
    expires_at  REAL NOT NULL,
    revoked_at  REAL,
    via         TEXT NOT NULL
);
"""

# Sources a task's credit can come from.
WELCOME = "welcome"
FREE_WEEKLY = "free_weekly"
PLUS = "plus"
LESSON_FREE = "lesson_free"
LESSON_PLUS = "lesson_plus"

ACTIVE = "active"
DONE = "done"
RELEASED = "released"


def _hash(key: str) -> str:
    return hashlib.sha256(key.encode()).hexdigest()


@dataclass(frozen=True)
class PlusPeriod:
    """A verified Dot Plus entitlement: when its current paid period began and ends."""

    active: bool
    period_start: float | None = None
    expires_at: float | None = None
    will_renew: bool = False
    # Where the answer came from, for the phone to say honestly: "verified",
    # "cached", "unverified" (couldn't check), "unconfigured" (no billing here),
    # "preview" (temporary preview access, never a purchase).
    status: str = "verified"
    # RevenueCat's own promotional grant rather than a store purchase.
    promotional: bool = False
    # The store product behind the entitlement ("dot_plus_weekly"), when RevenueCat said.
    product: str = ""

    @property
    def key(self) -> str:
        return f"plus:{int(self.period_start or 0)}"


NOT_PLUS = PlusPeriod(active=False)


@dataclass
class Allowance:
    """What the person may start now, and when more comes. Said to the phone as is."""

    can_start: bool
    next_source: str | None
    welcome_left: int
    free_weekly_available: bool
    plus_left: int | None
    # When the next free task arrives, if none is available now; epoch seconds.
    next_free_at: float | None
    plus: dict = field(default_factory=dict)
    free_courses: list[str] = field(default_factory=list)

    def body(self) -> dict:
        return {
            "can_start": self.can_start,
            "next_source": self.next_source,
            "welcome_left": self.welcome_left,
            "free_weekly_available": self.free_weekly_available,
            "plus_left": self.plus_left,
            "next_free_at": self.next_free_at,
            "plus": self.plus,
            "free_courses": self.free_courses,
        }


@dataclass(frozen=True)
class Admission:
    admitted: bool
    source: str | None
    reason: str = ""
    replay: bool = False


class LedgerError(Exception):
    """A refusal the phone can act on: a fixed code, never content."""

    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


class Ledger:
    def __init__(self, path: str, clock=time.time):
        self._path = path
        self._clock = clock
        self._lock = threading.Lock()
        if path != ":memory:":
            os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        self._db = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
        self._db.execute("PRAGMA journal_mode=WAL" if path != ":memory:" else "PRAGMA journal_mode=MEMORY")
        self._db.execute("PRAGMA foreign_keys=ON")
        self._db.executescript(SCHEMA)

    # ------------------------------------------------------------ plumbing

    def _tx(self):
        """One atomic transaction, serialised: BEGIN IMMEDIATE takes the write lock up front."""
        ledger = self

        class _T:
            def __enter__(self):
                ledger._lock.acquire()
                ledger._db.execute("BEGIN IMMEDIATE")
                return ledger._db

            def __exit__(self, exc_type, exc, tb):
                try:
                    ledger._db.execute("ROLLBACK" if exc_type else "COMMIT")
                finally:
                    ledger._lock.release()
                return False

        return _T()

    # ------------------------------------------------------------ identity

    def register(self) -> tuple[str, str]:
        """A new identity: its id, and the secret only this install will hold."""
        user_id = "dot_" + secrets.token_urlsafe(18)
        key = secrets.token_urlsafe(32)
        with self._tx() as db:
            db.execute(
                "INSERT INTO users(user_id, key_hash, created_at) VALUES (?, ?, ?)",
                (user_id, _hash(key), self._clock()),
            )
        return user_id, key

    def authenticate(self, user_id: str | None, key: str | None) -> str:
        """The caller is this identity, or LedgerError('unknown_identity')."""
        if not user_id or not key or len(user_id) > 64 or len(key) > 128:
            raise LedgerError("unknown_identity")
        row = self._db.execute("SELECT key_hash FROM users WHERE user_id = ?", (user_id,)).fetchone()
        # Compared whether or not the id was known, so timing says nothing.
        expected = row[0] if row else _hash(secrets.token_urlsafe(16))
        if not hmac.compare_digest(expected, _hash(key)) or row is None:
            raise LedgerError("unknown_identity")
        return user_id

    # ----------------------------------------------------------- allowance

    def _created(self, db, user_id: str) -> float:
        return db.execute("SELECT created_at FROM users WHERE user_id = ?", (user_id,)).fetchone()[0]

    def _used(self, db, user_id: str, source: str, period: str | None = None) -> int:
        if period is None:
            q = "SELECT COUNT(*) FROM tasks WHERE user_id=? AND source=? AND state != ?"
            return db.execute(q, (user_id, source, RELEASED)).fetchone()[0]
        q = "SELECT COUNT(*) FROM tasks WHERE user_id=? AND source=? AND period=? AND state != ?"
        return db.execute(q, (user_id, source, period, RELEASED)).fetchone()[0]

    def _release_stale(self, db, user_id: str, now: float) -> None:
        db.execute(
            "UPDATE tasks SET state=? WHERE user_id=? AND state=? AND useful=0 AND admitted_at < ?",
            (RELEASED, user_id, ACTIVE, now - STALE_RESERVATION_S),
        )

    def _room(self, db, user_id: str, source: str, period: str) -> bool:
        """Whether a credit of this kind, for this period, is still unspent."""
        if source in (LESSON_FREE, LESSON_PLUS):
            return True
        if source == WELCOME:
            return self._used(db, user_id, WELCOME) < WELCOME_TASKS
        if source == FREE_WEEKLY:
            return self._used(db, user_id, FREE_WEEKLY, period) < FREE_WEEKLY_TASKS
        return self._used(db, user_id, PLUS, period) < PLUS_TASKS_PER_PERIOD

    @staticmethod
    def free_week(created: float, now: float) -> int:
        """Which free week this is: 0 is the first, which has the welcome tasks instead."""
        return max(0, int(math.floor((now - created) / WEEK_S)))

    def _allowance(self, db, user_id: str, plus: PlusPeriod, now: float) -> Allowance:
        created = self._created(db, user_id)
        welcome_left = max(0, WELCOME_TASKS - self._used(db, user_id, WELCOME))
        week = self.free_week(created, now)
        # One a week from the second week on, and never saved up: an unused
        # week's task is simply gone when the next week begins.
        free_weekly = week >= 1 and self._used(db, user_id, FREE_WEEKLY, str(week)) < FREE_WEEKLY_TASKS
        plus_left = None
        if plus.active:
            plus_left = max(0, PLUS_TASKS_PER_PERIOD - self._used(db, user_id, PLUS, plus.key))
        order = [(PLUS, (plus_left or 0) > 0), (WELCOME, welcome_left > 0), (FREE_WEEKLY, free_weekly)]
        next_source = next((s for s, ok in order if ok), None)
        # No free task comes back on its own any more; when the welcome tasks
        # are spent, more Ask help is TapBack Plus.
        next_free_at = None if (welcome_left or free_weekly or FREE_WEEKLY_TASKS <= 0) else created + max(1, week + 1) * WEEK_S
        return Allowance(
            can_start=next_source is not None,
            next_source=next_source,
            welcome_left=welcome_left,
            free_weekly_available=free_weekly,
            plus_left=plus_left,
            next_free_at=next_free_at,
            plus={
                "active": plus.active,
                "expires_at": plus.expires_at,
                "will_renew": plus.will_renew,
                "status": plus.status,
                # So the phone can tell a purchase from a grant: a promotional
                # entitlement or a preview is never a confirmed purchase.
                "promotional": plus.promotional,
                "product": plus.product,
                "tasks_per_period": PLUS_TASKS_PER_PERIOD,
            },
            free_courses=sorted(FREE_COURSES),
        )

    # ------------------------------------------------------ preview access

    def grant_preview(self, user_id: str, days: float, via: str) -> tuple[PlusPeriod, bool]:
        """Temporary Plus for lesson previews, once per person: redeeming again
        returns the same grant rather than extending it. @return (grant, new)."""
        now = self._clock()
        with self._tx() as db:
            row = db.execute("SELECT granted_at, expires_at, revoked_at FROM preview_grants WHERE user_id=?", (user_id,)).fetchone()
            if row is not None and row[2] is None and row[1] > now:
                return self._preview_period(row[0], row[1]), False
            if row is not None and row[2] is None:
                # Expired: a code redeemed again later may start one new preview.
                db.execute("DELETE FROM preview_grants WHERE user_id=?", (user_id,))
            elif row is not None:
                # Revoked grants are not renewed by the same install.
                raise LedgerError("preview_revoked")
            expires = now + days * 24 * 3600.0
            db.execute(
                "INSERT INTO preview_grants(user_id, granted_at, expires_at, revoked_at, via) VALUES (?, ?, ?, NULL, ?)",
                (user_id, now, expires, via),
            )
            return self._preview_period(now, expires), True

    def preview(self, user_id: str) -> PlusPeriod | None:
        """The person's preview access, while it lasts."""
        now = self._clock()
        with self._tx() as db:
            row = db.execute("SELECT granted_at, expires_at, revoked_at FROM preview_grants WHERE user_id=?", (user_id,)).fetchone()
        if row is None or row[2] is not None or row[1] <= now:
            return None
        return self._preview_period(row[0], row[1])

    def preview_count(self) -> int:
        with self._tx() as db:
            return db.execute("SELECT COUNT(*) FROM preview_grants").fetchone()[0]

    def revoke_preview(self, user_id: str) -> bool:
        with self._tx() as db:
            cur = db.execute("UPDATE preview_grants SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL", (self._clock(), user_id))
            return cur.rowcount > 0

    @staticmethod
    def _preview_period(granted_at: float, expires_at: float) -> PlusPeriod:
        # One Plus week for the whole preview: ten Ask tasks, every course.
        return PlusPeriod(active=True, period_start=granted_at, expires_at=expires_at, will_renew=False, status="preview")

    def allowance(self, user_id: str, plus: PlusPeriod) -> Allowance:
        now = self._clock()
        with self._tx() as db:
            self._release_stale(db, user_id, now)
            return self._allowance(db, user_id, plus, now)

    # ----------------------------------------------------------- admission

    def admit(self, user_id: str, task_id: str, kind: str, plus: PlusPeriod, course_id: str = "") -> tuple[Admission, Allowance]:
        """Start a task, once. The same task id admitted again is the same answer, charged once."""
        if kind not in ("ask", "lesson") or not task_id or len(task_id) > 64:
            raise LedgerError("bad_task")
        now = self._clock()
        with self._tx() as db:
            existing = db.execute("SELECT user_id, source, state FROM tasks WHERE task_id = ?", (task_id,)).fetchone()
            if existing is not None:
                owner, source, state = existing
                if owner != user_id:
                    raise LedgerError("bad_task")
                return (
                    Admission(admitted=state != RELEASED, source=source, reason="" if state != RELEASED else "released", replay=True),
                    self._allowance(db, user_id, plus, now),
                )
            self._release_stale(db, user_id, now)

            if kind == "lesson":
                if course_id in FREE_COURSES:
                    source, period = LESSON_FREE, ""
                elif plus.active:
                    source, period = LESSON_PLUS, plus.key
                else:
                    return Admission(False, None, "premium_course"), self._allowance(db, user_id, plus, now)
            else:
                allowance = self._allowance(db, user_id, plus, now)
                source = allowance.next_source
                if source is None:
                    return Admission(False, None, "no_allowance"), allowance
                period = {
                    PLUS: plus.key,
                    FREE_WEEKLY: str(self.free_week(self._created(db, user_id), now)),
                    WELCOME: "",
                }[source]
            db.execute(
                "INSERT INTO tasks(task_id, user_id, kind, course_id, source, period, state, admitted_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                (task_id, user_id, kind, course_id[:40], source, period, ACTIVE, now),
            )
            return Admission(True, source), self._allowance(db, user_id, plus, now)

    # ------------------------------------------------------ while it runs

    def count_request(self, user_id: str, task_id: str) -> None:
        """One planning request for an admitted task, within the loop guards."""
        now = self._clock()
        day = time.strftime("%Y-%m-%d", time.gmtime(now))
        with self._tx() as db:
            row = db.execute("SELECT user_id, state, requests FROM tasks WHERE task_id = ?", (task_id,)).fetchone()
            if row is None or row[0] != user_id:
                raise LedgerError("not_admitted")
            if row[1] == RELEASED:
                # Released while it sat unused, then carried on (a resume the
                # next morning): its credit is taken again if it is still
                # there, never a second one on top.
                source, period = db.execute("SELECT source, period FROM tasks WHERE task_id=?", (task_id,)).fetchone()
                if not self._room(db, user_id, source, period):
                    raise LedgerError("no_allowance")
                db.execute("UPDATE tasks SET state=?, admitted_at=? WHERE task_id=?", (ACTIVE, now, task_id))
            if row[2] >= MAX_REQUESTS_PER_TASK:
                raise LedgerError("task_limit")
            user_day, count = db.execute("SELECT day, day_count FROM users WHERE user_id = ?", (user_id,)).fetchone()
            count = count if user_day == day else 0
            if count >= MAX_REQUESTS_PER_DAY:
                raise LedgerError("daily_limit")
            db.execute("UPDATE users SET day=?, day_count=? WHERE user_id=?", (day, count + 1, user_id))
            db.execute("UPDATE tasks SET requests = requests + 1 WHERE task_id = ?", (task_id,))

    def mark_useful(self, task_id: str) -> None:
        """Dot helped with this task: its credit is spent and can no longer be released."""
        with self._tx() as db:
            db.execute("UPDATE tasks SET useful = useful + 1 WHERE task_id = ?", (task_id,))

    def release(self, user_id: str, task_id: str) -> bool:
        """Give the credit back — only for a task Dot never actually helped with."""
        with self._tx() as db:
            row = db.execute("SELECT user_id, state, useful FROM tasks WHERE task_id = ?", (task_id,)).fetchone()
            if row is None or row[0] != user_id:
                raise LedgerError("not_admitted")
            if row[1] == RELEASED:
                return True
            if row[2] > 0:
                return False
            db.execute("UPDATE tasks SET state=? WHERE task_id=?", (RELEASED, task_id))
            return True

    def finish(self, user_id: str, task_id: str) -> None:
        with self._tx() as db:
            db.execute("UPDATE tasks SET state=? WHERE task_id=? AND user_id=? AND state=?", (DONE, task_id, user_id, ACTIVE))

    def task(self, task_id: str) -> dict | None:
        row = self._db.execute(
            "SELECT user_id, kind, source, state, requests, useful FROM tasks WHERE task_id = ?", (task_id,)
        ).fetchone()
        if row is None:
            return None
        return dict(zip(("user_id", "kind", "source", "state", "requests", "useful"), row))
