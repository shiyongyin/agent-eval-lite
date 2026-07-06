"""PoC D — Agent trace + tool-call 捕获与「trace 断言」（OpenInference/OTel）。

目标：用业界标准的 OpenInference 语义约定，给「一次 Agent run」打 trace：
  - 一个 agent span（整轮）
  - 一个 tool span（user.lookup 的真实调用，带入参/返回）
然后离线断言：「trace 里确实出现了 user.lookup 工具调用」——这正是
AgentEval-Lite tool_call_required(blocking) 规则在成熟 trace 生态里的等价物。

用 InMemorySpanExporter 捕获 span，全程离线、可复现；同时（若 Phoenix 在线）
把同一批 span 通过 OTLP 发给 Phoenix，证明可直接对接成熟 observability 平台。
"""

import json
from opentelemetry import trace
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter
from openinference.semconv.trace import (
    SpanAttributes,
    OpenInferenceSpanKindValues,
    ToolCallAttributes,
)

memory_exporter = InMemorySpanExporter()
provider = TracerProvider()
provider.add_span_processor(SimpleSpanProcessor(memory_exporter))

# 若本地 Phoenix 在线，则把同一批 span 也发过去（证明可直连成熟平台）。
phoenix_ok = False
try:
    from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter

    otlp = OTLPSpanExporter(endpoint="http://localhost:6006/v1/traces")
    provider.add_span_processor(SimpleSpanProcessor(otlp))
    phoenix_ok = True
except Exception as e:  # noqa: BLE001
    print(f"[warn] OTLP/Phoenix 导出不可用，仅用内存导出器：{e}")

trace.set_tracer_provider(provider)
tracer = trace.get_tracer("agenteval.poc")


def run_agent_once():
    """模拟一轮 Agent：先调 user.lookup，再据结果给出 card_type。"""
    with tracer.start_as_current_span("agent.attempt") as agent_span:
        agent_span.set_attribute(
            SpanAttributes.OPENINFERENCE_SPAN_KIND,
            OpenInferenceSpanKindValues.AGENT.value,
        )
        agent_span.set_attribute(SpanAttributes.INPUT_VALUE, "为 u_1001 开卡")

        with tracer.start_as_current_span("tool.user.lookup") as tool_span:
            tool_span.set_attribute(
                SpanAttributes.OPENINFERENCE_SPAN_KIND,
                OpenInferenceSpanKindValues.TOOL.value,
            )
            tool_span.set_attribute(SpanAttributes.TOOL_NAME, "user.lookup")
            tool_span.set_attribute(
                ToolCallAttributes.TOOL_CALL_FUNCTION_ARGUMENTS_JSON,
                json.dumps({"user_id": "u_1001"}),
            )
            result = {"user_id": "u_1001", "credit_level": "GOLD"}
            tool_span.set_attribute(SpanAttributes.OUTPUT_VALUE, json.dumps(result))

        agent_span.set_attribute(SpanAttributes.OUTPUT_VALUE, "card_type=PLATINUM")


def assert_tool_called(spans, tool_name):
    """trace 断言：是否存在一个 kind=TOOL 且 tool.name==tool_name 的 span。"""
    for s in spans:
        attrs = dict(s.attributes)
        if (
            attrs.get(SpanAttributes.OPENINFERENCE_SPAN_KIND)
            == OpenInferenceSpanKindValues.TOOL.value
            and attrs.get(SpanAttributes.TOOL_NAME) == tool_name
        ):
            return True, attrs
    return False, {}


if __name__ == "__main__":
    run_agent_once()
    provider.force_flush()
    spans = memory_exporter.get_finished_spans()

    print("\n==== PoC D 结果（OpenInference/OTel Agent trace + tool call）====")
    print(f"captured spans: {[s.name for s in spans]}")
    called, attrs = assert_tool_called(spans, "user.lookup")
    print(f"[trace 断言] user.lookup 是否被真实调用: {called}")
    if called:
        print(f"  ├─ tool args: {attrs.get(ToolCallAttributes.TOOL_CALL_FUNCTION_ARGUMENTS_JSON)}")
        print(f"  └─ tool output: {attrs.get(SpanAttributes.OUTPUT_VALUE)}")

    # 反例：断言一个未被调用的工具，应为 False（证明断言有区分力）
    called_fake, _ = assert_tool_called(spans, "payment.charge")
    print(f"[trace 断言-反例] payment.charge 是否被调用: {called_fake}（期望 False）")

    print(f"phoenix_otlp_export_enabled={phoenix_ok}")
    print("结论：OpenInference span 能记录 tool 调用的名字/入参/返回，且可据此做 blocking 断言。")
