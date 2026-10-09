import json
import inspect
import socket
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener
from urllib.parse import urlsplit, urlunsplit

from errors import (
    AuthenticationError,
    NotReadyError,
    ProviderError,
    ProviderTimeoutError,
)
from lifecycle import DeadlineExceeded, RequestCancelled, RequestContext
from models import ChapterAnalysisRequest, HealthStatus
from protocol import MAX_JSON, project_analysis, strict_json_loads


class HttpDirectorProvider:
    # 外部分析服务（如百炼桥接）可能消耗云额度，向 Android 声明为计费操作。
    metered = True

    def __init__(
        self,
        base_url,
        token,
        transport=None,
        health_path="/v1/health",
        health_timeout=5,
    ):
        self.base_url = _base_url(base_url)
        self.token = token
        self.transport = transport or self._request
        self.health_path = health_path
        self.health_timeout = health_timeout

    def health(self, context=None):
        if not self.base_url or not self.token:
            return HealthStatus(ready=False)
        owns_context = context is None
        context = context or RequestContext(self.health_timeout)
        try:
            status, _, raw = _invoke_transport(
                self.transport,
                "GET",
                self.health_path,
                None,
                context,
            )
            if status != 200:
                return HealthStatus(ready=False)
            value = strict_json_loads(raw)
            return HealthStatus(
                ready=(
                    isinstance(value, dict)
                    and value.get("apiVersion") == "1"
                    and value.get("status") == "ok"
                    and value.get("directorReady") is True
                ),
            )
        except (RequestCancelled, DeadlineExceeded, ProviderError, ValueError, TypeError):
            return HealthStatus(ready=False)
        finally:
            if owns_context:
                context.cancel()

    def analyze(self, request: ChapterAnalysisRequest, context=None):
        owns_context = context is None
        context = context or RequestContext(45)
        if not self.health(context=context).ready:
            raise NotReadyError()
        status, _, raw = _invoke_transport(
            self.transport,
            "POST",
            "/v1/chapter/analyze",
            request.as_dict(),
            context,
        )
        if status in (401, 403):
            raise AuthenticationError()
        if status >= 500:
            raise ProviderError()
        if status != 200:
            raise ProviderError()
        try:
            value = strict_json_loads(raw)
            return project_analysis(value, request.as_dict())
        except (ValueError, TypeError, KeyError):
            raise ProviderError() from None
        finally:
            if owns_context:
                context.cancel()

    def _request(self, method, path, payload, context=None):
        body = None if payload is None else json.dumps(
            payload,
            ensure_ascii=False,
        ).encode("utf-8")
        request = Request(
            self.base_url + path,
            data=body,
            headers={
                "Authorization": "Bearer " + self.token,
                "Content-Type": "application/json",
                "Accept": "application/json",
            },
            method=method,
        )
        owns_context = context is None
        context = context or RequestContext(45)
        try:
            context.check()
            with _opener().open(request, timeout=context.remaining()) as response:
                return response.status, dict(response.headers.items()), _read(
                    response,
                    context,
                )
        except HTTPError as error:
            try:
                error.read(4097)
            finally:
                error.close()
            return error.code, {}, b""
        except DeadlineExceeded:
            raise ProviderTimeoutError() from None
        except RequestCancelled:
            raise
        except (socket.timeout, TimeoutError):
            if context.cancelled:
                raise RequestCancelled() from None
            raise ProviderTimeoutError() from None
        except URLError:
            raise ProviderError() from None
        finally:
            if owns_context:
                context.cancel()


def _base_url(value):
    parsed = urlsplit(value)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        raise ValueError("invalid director URL")
    return urlunsplit((parsed.scheme, parsed.netloc, parsed.path.rstrip("/"), "", ""))


def _opener():
    class NoRedirect(HTTPRedirectHandler):
        def redirect_request(self, *args, **kwargs):
            return None

    return build_opener(ProxyHandler({}), NoRedirect())


def _read(response, context=None):
    content_length = response.headers.get("Content-Length")
    if content_length is not None and (
        not content_length.isdecimal() or int(content_length) > MAX_JSON
    ):
        raise ProviderError()
    data = bytearray()
    while True:
        if context is not None:
            context.check()
        chunk = response.read(min(65536, MAX_JSON + 1 - len(data)))
        if not chunk:
            if content_length is not None and len(data) != int(content_length):
                raise ProviderError()
            return bytes(data)
        data.extend(chunk)
        if len(data) > MAX_JSON:
            raise ProviderError()


def _invoke_transport(transport, method, path, payload, context):
    parameters = inspect.signature(transport).parameters
    if "context" in parameters or any(
        parameter.kind == inspect.Parameter.VAR_KEYWORD
        for parameter in parameters.values()
    ):
        return transport(method, path, payload, context=context)
    return transport(method, path, payload)
