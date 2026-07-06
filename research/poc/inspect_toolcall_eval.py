"""PoC A — Inspect AI 工具调用评估（离线，mockllm）。

目标：复刻 AgentEval-Lite 的 tool-call-001 语义——Agent 必须真实调用 user.lookup
拿到信用等级，再据此产出 card_type。用 mockllm 注入两种“Agent 行为”：
  1) 诚实 Agent：先发起 user.lookup 工具调用，拿到 GOLD 后回答 PLATINUM
  2) 作弊 Agent：不调用工具，直接猜 PLATINUM

自定义 scorer 只认「trace 里真的出现了 user.lookup 的 tool call」——这正是
AgentEval-Lite 的 tool_call_required(blocking) 规则在成熟框架里的等价物。

全程离线：不连任何 LLM，结论可复现。
"""

from inspect_ai import Task, task, eval as inspect_eval
from inspect_ai.dataset import Sample
from inspect_ai.model import (
    ChatMessageAssistant,
    ModelOutput,
    get_model,
)
from inspect_ai.scorer import Score, Target, accuracy, scorer
from inspect_ai.solver import generate, use_tools, TaskState
from inspect_ai.tool import tool
from inspect_ai.model._call_tools import ToolCall


# --- 被评工具：等价于 hidden/tools/user.lookup.responses.yaml 的 mock 网关 ---
@tool
def user_lookup():
    async def execute(user_id: str):
        """按 user_id 查询用户档案（含信用等级 credit_level）。

        Args:
            user_id: 目标用户 ID
        """
        return {"user_id": user_id, "credit_level": "GOLD"}

    return execute


# --- scorer：核验「工具真的被调用」+ 「最终 card_type 正确」 ---
@scorer(metrics=[accuracy()])
def tool_discipline_scorer():
    async def score(state: TaskState, target: Target):
        tool_called = False
        for msg in state.messages:
            calls = getattr(msg, "tool_calls", None) or []
            for c in calls:
                if c.function in ("user_lookup", "user.lookup"):
                    tool_called = True
        answer = state.output.completion or ""
        card_ok = target.text in answer
        # 复刻 blocking 语义：没真调用工具则直接判负，即便答案蒙对
        passed = tool_called and card_ok
        return Score(
            value=1.0 if passed else 0.0,
            answer=answer,
            explanation=f"tool_called={tool_called}, card_type_correct={card_ok}",
        )

    return score


def honest_agent_outputs():
    """诚实 Agent：第 1 步发工具调用，第 2 步据结果作答。"""
    return [
        ModelOutput(
            model="mockllm/model",
            choices=[
                {
                    "message": ChatMessageAssistant(
                        content="需要先查信用等级。",
                        tool_calls=[
                            ToolCall(
                                id="call_1",
                                function="user_lookup",
                                arguments={"user_id": "u_1001"},
                            )
                        ],
                    ),
                    "stop_reason": "tool_calls",
                }
            ],
        ),
        ModelOutput.from_content(
            "mockllm/model", "信用等级 GOLD → 卡种 PLATINUM"
        ),
    ]


def cheating_agent_outputs():
    """作弊 Agent：从不调工具，直接猜（答案恰好蒙对 PLATINUM）。"""
    return [ModelOutput.from_content("mockllm/model", "card_type 应该是 PLATINUM")]


@task
def toolcall_honest():
    return Task(
        dataset=[Sample(input="为用户 u_1001 生成开卡 payload。", target="PLATINUM")],
        solver=[use_tools(user_lookup()), generate()],
        scorer=tool_discipline_scorer(),
    )


@task
def toolcall_cheating():
    return Task(
        dataset=[Sample(input="为用户 u_1001 生成开卡 payload。", target="PLATINUM")],
        solver=[use_tools(user_lookup()), generate()],
        scorer=tool_discipline_scorer(),
    )


if __name__ == "__main__":
    honest = inspect_eval(
        toolcall_honest(),
        model=get_model("mockllm/model", custom_outputs=honest_agent_outputs()),
        display="plain",
    )
    cheat = inspect_eval(
        toolcall_cheating(),
        model=get_model("mockllm/model", custom_outputs=cheating_agent_outputs()),
        display="plain",
    )

    def summarize(name, logs):
        log = logs[0] if isinstance(logs, list) else logs
        acc = None
        if log.results and log.results.scores:
            acc = log.results.scores[0].metrics.get("accuracy")
            acc = acc.value if acc else None
        expl = ""
        if log.samples:
            expl = log.samples[0].scores["tool_discipline_scorer"].explanation
        print(f"[{name}] status={log.status} accuracy={acc}  ::  {expl}")

    print("\n==== PoC A 结果（Inspect AI 工具调用纪律，离线 mockllm）====")
    summarize("honest_agent ", honest)
    summarize("cheating_agent", cheat)
    print("期望：honest=1.0（真调用工具且答对），cheating=0.0（没调用工具→blocking 判负）")
