// 转人工意图识别:用户在对话框里用自然语言要求人工时,前端直接短路掉 LLM,
// 触发真实的建单/接入流程(等价于点「转人工」按钮),而不是让 AI 礼貌回一句空话。
// 关键词覆盖常见说法;演示足够,无需上 NLU。
const TRANSFER_RE = /转人工|转接人工|人工客服|人工坐席|人工服务|找人工|要人工|叫人工|换人工|接入人工|联系人工|真人客服|真人坐席/

export function isTransferIntent(text) {
  return !!text && TRANSFER_RE.test(text)
}
