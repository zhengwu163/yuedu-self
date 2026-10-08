import threading
import time


class RequestCancelled(Exception):
    """The client or service stopped an in-flight request."""


class DeadlineExceeded(RequestCancelled):
    """The absolute request deadline elapsed."""


class RequestContext:
    def __init__(self, timeout, clock=None):
        if timeout <= 0:
            raise ValueError("timeout must be positive")
        self._clock = clock or time.monotonic
        self._deadline = self._clock() + timeout
        self._cancelled = threading.Event()
        self._lock = threading.Lock()
        self._closers = []

    @property
    def cancelled(self):
        return self._cancelled.is_set()

    def register_closer(self, closer):
        with self._lock:
            if self.cancelled:
                close_now = True
            else:
                self._closers.append(closer)
                close_now = False
        if close_now:
            _close_safely(closer)

    def unregister_closer(self, closer):
        with self._lock:
            try:
                self._closers.remove(closer)
            except ValueError:
                pass

    def cancel(self):
        if self._cancelled.is_set():
            return
        self._cancelled.set()
        with self._lock:
            closers = list(self._closers)
            self._closers.clear()
        for closer in closers:
            _close_safely(closer)

    def remaining(self):
        if self.cancelled:
            raise RequestCancelled()
        remaining = self._deadline - self._clock()
        if remaining <= 0:
            self.cancel()
            raise DeadlineExceeded()
        return remaining

    def check(self):
        return self.remaining()

    def wait(self, interval):
        remaining = self.remaining()
        if self._cancelled.wait(min(interval, remaining)):
            raise RequestCancelled()
        self.remaining()


def _close_safely(closer):
    try:
        closer()
    except OSError:
        pass
