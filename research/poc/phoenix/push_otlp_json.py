#!/usr/bin/env python3
"""把 agent-eval export 产出的 OTLP/JSON 转成 protobuf 并推送到 OTLP HTTP 端点。

用途：
1. 严格校验导出 JSON 是否符合 OTLP proto3 JSON mapping（json_format.Parse 会拒绝任何未知字段）；
2. 对只接受 application/x-protobuf 的后端（如 Arize Phoenix 17.x）完成推送演示。

用法：
    python push_otlp_json.py <trace.otlp.json> [endpoint]
    endpoint 默认 http://localhost:6006/v1/traces（Phoenix）
"""
import subprocess
import sys
import tempfile

from google.protobuf import json_format
from opentelemetry.proto.collector.trace.v1 import trace_service_pb2


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    json_path = sys.argv[1]
    endpoint = sys.argv[2] if len(sys.argv) > 2 else "http://localhost:6006/v1/traces"

    with open(json_path, "r", encoding="utf-8") as f:
        payload = f.read()

    # 严格解析：JSON 中任何不符合 OTLP 规范的字段都会抛 ParseError
    request = json_format.Parse(payload, trace_service_pb2.ExportTraceServiceRequest())
    span_count = sum(
        len(ss.spans) for rs in request.resource_spans for ss in rs.scope_spans
    )
    print(f"JSON 校验通过：{span_count} 个 span 符合 OTLP proto3 JSON mapping")

    with tempfile.NamedTemporaryFile(suffix=".pb") as pb:
        pb.write(request.SerializeToString())
        pb.flush()
        # 用 curl 推送：Phoenix(uvicorn) 对部分 python 客户端的 keep-alive 处理会挂起，curl 实测稳定。
        result = subprocess.run(
            ["curl", "-sS", "--max-time", "30", "-o", "/dev/null", "-w", "%{http_code}",
             "-X", "POST", endpoint,
             "-H", "Content-Type: application/x-protobuf",
             "--data-binary", f"@{pb.name}"],
            capture_output=True, text=True, check=False,
        )
    code = result.stdout.strip()
    if result.returncode != 0 or not code.startswith("2"):
        print(f"推送失败：HTTP {code or '?'} {result.stderr.strip()}", file=sys.stderr)
        return 1
    print(f"推送成功：HTTP {code} ← {endpoint}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
