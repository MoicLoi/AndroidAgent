package dev.anroidagent.core.policy

import dev.anroidagent.core.model.Step

/**
 * 风险类别。分级见 docs/policy.md。
 *
 * 设计要点：金额、价格、余额是**名词**，不构成任何风险类别。
 * 唯一的不可逆点是"提供凭证"与"触发支付"两个**动词**。
 */
enum class RiskClass {
    /** 凭证索取：密码 / 验证码 / 卡号 / CVV / 身份证 / 生物识别 */
    CREDENTIAL,

    /** 支付动作：点击立即支付类按钮 */
    PAYMENT,

    /** 仅下单，不涉及资金（软提级） */
    ORDER_ONLY,

    /** 银行类场景 */
    BANK,

    /** 身份信息 */
    IDENTITY,

    /** shell / adb 命令（含被拒绝的） */
    SHELL,

    /** 无法归类的敏感场景 */
    UNKNOWN_SENSITIVE,
}

data class Risk(
    val cls: RiskClass,
    val evidence: String,
    val conf: Float = 0f,
)

/**
 * 判定结果。
 *
 * [Escalate.soft] = true 表示"提级但不中断"（ORDER_ONLY），
 * 只把后续敏感度调高；若 3 步内未出现 L2 信号则回落。
 */
sealed interface Verdict {
    data object Pass : Verdict
    data class Escalate(val risk: Risk, val soft: Boolean = false) : Verdict
    data class Kill(val reason: String) : Verdict

    val isBlocking: Boolean
        get() = when (this) {
            is Kill -> true
            is Escalate -> !soft
            Pass -> false
        }
}

/**
 * 确定性规则命中。[hard] = true 时直接 Kill，不可被模型影响。
 */
data class RuleHit(
    val hard: Boolean,
    val reason: String,
    val risk: Risk? = null,
)

/**
 * 单一数据源：规则表与提示词片段都从这里生成，避免两边漂移。
 */
object PolicyVocab {

    /** 凭证类输入框的特征词（匹配 hintText + label） */
    val CREDENTIAL_HINTS: List<String> = listOf(
        "支付密码", "交易密码", "密码", "验证码", "短信验证码", "动态码",
        "卡号", "银行卡", "CVV", "CVV2", "安全码", "有效期",
        "身份证", "证件号", "PIN", "人脸", "指纹", "刷脸",
    )

    /** 支付动词：点击即可能扣款 */
    val PAY_VERBS: List<String> = listOf(
        "立即支付", "确认支付", "确认付款", "去支付", "立即付款",
        "指纹支付", "面容支付", "免密支付", "开通免密",
    )

    /**
     * 下单动词：不涉及资金，只做软提级。
     *
     * 注意「加入购物车」「立即购买」**不在此列**：它们只是浏览/加购，
     * 触发它们会让比价场景被打断。真正的提级点在「提交订单」。
     */
    val ORDER_VERBS: List<String> = listOf(
        "提交订单", "确认下单", "立即下单", "去结算",
    )

    /** 待付金额：仅在带语义前缀时才有意义（不参与阻断，仅作为上下文） */
    val DUE_AMOUNT: Regex =
        Regex("""(应付|实付|待付|需支付|合计|总计)[：:]?\s*[¥￥$]?\s*\d{1,3}(,\d{3})*(\.\d{1,2})?""")

    /** 支付类外部 scheme，命中直接硬停 */
    val PAYMENT_SCHEMES: List<String> = listOf(
        "alipays://", "alipayqr://", "weixin://", "unionpay://",
        "upwallet://", "ccb://", "icbc://", "abchina://",
    )

    private fun anyHit(text: String, words: List<String>): Boolean =
        text.isNotBlank() && words.any { text.contains(it, ignoreCase = true) }

    fun credentialHit(text: String): Boolean = anyHit(text, CREDENTIAL_HINTS)
    fun payVerbHit(text: String): Boolean = anyHit(text, PAY_VERBS)
    fun orderVerbHit(text: String): Boolean = anyHit(text, ORDER_VERBS)
    fun paymentSchemeHit(url: String): Boolean = anyHit(url, PAYMENT_SCHEMES)

    /** 生成系统提示词片段，保证与规则表一致 */
    fun promptFragment(): String = buildString {
        appendLine("【必须上报 risk 的情况】")
        appendLine("1. risk.class = CREDENTIAL：页面要求输入以下任一内容 —— ")
        appendLine(CREDENTIAL_HINTS.joinToString("、"))
        appendLine("2. risk.class = PAYMENT：你准备点击的按钮文字含以下任一 —— ")
        appendLine(PAY_VERBS.joinToString("、"))
        appendLine()
        appendLine("【不需要上报（正常操作）】")
        appendLine("- 商品价格、折扣、优惠券、比价、购物车、商品列表、评价、规格选择")
        appendLine("- 页面上的任何金额数字本身，无论出现多少次")
        appendLine("- 点击以下按钮：")
        appendLine(ORDER_VERBS.joinToString("、"))
        appendLine()
        appendLine("【判定原则】")
        appendLine("区分「信息展示」与「凭证索取」，只有后者需要上报。")
        appendLine("宁可误报也不要漏报，但非凭证/支付场景请勿上报。")
    }
}

/**
 * 动作级红线判定。
 *
 * 比页面级判定精确得多：浏览、滚动、点击商品卡片全部放行，
 * 只在"输入凭证"和"点击支付"两个动作上拦截。这是比价场景零误杀的原因。
 */
object ActionGuard {

    fun check(step: Step): Verdict = when (step) {

        is Step.Type -> {
            val probe = "${step.targetHint} ${step.targetLabel}"
            if (PolicyVocab.credentialHit(probe)) {
                Verdict.Escalate(Risk(RiskClass.CREDENTIAL, probe.trim(), 0.95f))
            } else {
                Verdict.Pass
            }
        }

        is Step.Tap -> when {
            PolicyVocab.payVerbHit(step.label) ->
                Verdict.Escalate(Risk(RiskClass.PAYMENT, step.label, 0.95f))

            PolicyVocab.orderVerbHit(step.label) ->
                Verdict.Escalate(Risk(RiskClass.ORDER_ONLY, step.label, 0.8f), soft = true)

            else -> Verdict.Pass
        }

        /**
         * shell 命令由分类闸门决定：只读放行，变更/危险一律硬升级。
         * 硬升级在状态机里表现为 PAUSED——命令根本不会执行，
         * 用户看到的是"这条命令需要你确认"。
         */
        is Step.Shell -> when (ShellCommandPolicy.classify(step.cmd)) {
            ShellClass.READ_ONLY -> Verdict.Pass

            ShellClass.MUTATING -> Verdict.Escalate(
                Risk(RiskClass.SHELL, "需确认: ${step.cmd.take(60)}", 0.9f),
            )

            ShellClass.FORBIDDEN -> Verdict.Escalate(
                Risk(RiskClass.SHELL, "已拒绝: ${step.cmd.take(60)}", 0.99f),
            )
        }

        // 浏览、滚动、返回、等待、截图、启动 App：全部放行
        else -> Verdict.Pass
    }
}

/**
 * 单调升级判定。
 *
 * 核心不变量：**modelRisk 分支永远不可能返回 Pass**。
 * 模型被注入、幻觉、漏检时，最坏结果是多暂停一次，不会漏过 L2。
 */
object PolicyDecider {

    fun decide(ruleHit: RuleHit?, modelRisk: Risk?): Verdict = when {
        ruleHit?.hard == true -> Verdict.Kill(ruleHit.reason)

        ruleHit != null -> Verdict.Escalate(
            ruleHit.risk ?: Risk(RiskClass.UNKNOWN_SENSITIVE, ruleHit.reason),
        )

        modelRisk != null -> Verdict.Escalate(modelRisk)

        else -> Verdict.Pass
    }

    /** 合并确定性规则与模型上报，取更保守者 */
    fun decide(ruleHit: RuleHit?, stepVerdict: Verdict, modelRisk: Risk?): Verdict {
        val a = decide(ruleHit, modelRisk)
        return when {
            a is Verdict.Kill -> a
            stepVerdict is Verdict.Kill -> stepVerdict
            a is Verdict.Escalate && !a.soft -> a
            stepVerdict is Verdict.Escalate && !stepVerdict.soft -> stepVerdict
            a is Verdict.Escalate -> a
            stepVerdict is Verdict.Escalate -> stepVerdict
            else -> Verdict.Pass
        }
    }
}
