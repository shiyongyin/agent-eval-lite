// 离线 provider（class 形式）：把「被测系统」固定为确定性函数，不连任何 LLM。
// 演示 promptfoo 作为「prompt/输出回归测试 + CI 门禁」，结论完全可复现。
// 模拟一个「开卡结论生成器」：prompt 里含 credit_level 就据规则映射卡种，
// 否则返回一个「无依据的猜测」（用于制造一个会被断言抓住的回归失败）。
class EchoCardGenProvider {
  constructor(options) {
    this.providerId = options.id || 'echo-cardgen';
  }

  id() {
    return this.providerId;
  }

  async callApi(prompt) {
    const m = /credit_level\s*[:=]\s*"?(\w+)"?/i.exec(prompt);
    const level = m ? m[1].toUpperCase() : null;
    const map = { GOLD: 'PLATINUM', SILVER: 'GOLD' };
    let card;
    if (level) card = map[level] || 'STANDARD';
    else card = 'PLATINUM'; // 无依据的猜测：会被 no-hallucination 断言判失败
    const output = JSON.stringify({ card_type: card, from_level: level });
    return { output };
  }
}

module.exports = EchoCardGenProvider;
