package dev.anroidagent.core

import dev.anroidagent.core.model.Step
import dev.anroidagent.core.policy.ActionGuard
import dev.anroidagent.core.policy.BannedTargets
import dev.anroidagent.core.policy.PolicyDecider
import dev.anroidagent.core.policy.PolicyVocab
import dev.anroidagent.core.policy.Risk
import dev.anroidagent.core.policy.RiskClass
import dev.anroidagent.core.policy.RuleHit
import dev.anroidagent.core.policy.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 红线策略的硬性断言。
 *
 * 这些测试是合规证据链的一部分：它们证明"禁用目标被拒绝"与
 * "比价场景不误杀"是代码层面的不变式，而不是文档承诺。
 */
class PolicyTest {

    // ---------- 目标硬禁 ----------

    @Test
    fun `wechat qq and alipay are blocked by package`() {
        listOf(
            "com.tencent.mm",
            "com.tencent.mobileqq",
            "com.tencent.tim",
            "com.eg.android.AlipayGphone",
            "com.unionpay",
        ).forEach { pkg ->
            assertNotNull("应命中禁用规则：$pkg", BannedTargets.byPackage(pkg))
        }
    }

    @Test
    fun `ordinary package is not blocked`() {
        assertTrue(BannedTargets.byPackage("com.example.shop") == null)
    }

    // ---------- 比价场景：零误杀 ----------

    @Test
    fun `browsing and price comparison pass`() {
        val browsing = listOf(
            Step.Tap(index = 3, label = "火锅探店"),
            Step.Tap(index = 4, label = "加入购物车"),
            Step.Tap(index = 5, label = "¥199"),
            Step.Scroll,
            Step.Back,
            Step.Wait(500),
            Step.Swipe(540, 1800, 540, 600),
        )
        browsing.forEach { step ->
            assertTrue("浏览动作不应被阻断：$step", ActionGuard.check(step) !is Verdict.Escalate)
        }
    }

    @Test
    fun `typing into search box passes`() {
        val step = Step.Type(index = 2, text = "咖啡", targetLabel = "搜索商品", targetHint = "输入关键词")
        assertEquals(Verdict.Pass, ActionGuard.check(step))
    }

    // ---------- 凭证与支付：必须拦 ----------

    @Test
    fun `typing into credential field escalates`() {
        listOf(
            Step.Type(1, "123456", targetHint = "支付密码"),
            Step.Type(1, "8888", targetLabel = "短信验证码"),
            Step.Type(1, "6222", targetHint = "银行卡号"),
            Step.Type(1, "123", targetHint = "CVV2"),
        ).forEach { step ->
            val v = ActionGuard.check(step)
            assertTrue("凭证输入必须升级：$step", v is Verdict.Escalate)
            assertEquals(
                RiskClass.CREDENTIAL,
                (v as Verdict.Escalate).risk.cls,
            )
        }
    }

    @Test
    fun `tapping pay verbs escalates`() {
        listOf("立即支付", "确认支付", "去支付", "指纹支付").forEach { label ->
            val v = ActionGuard.check(Step.Tap(1, label))
            assertTrue(v is Verdict.Escalate)
            assertEquals(RiskClass.PAYMENT, (v as Verdict.Escalate).risk.cls)
        }
    }

    @Test
    fun `order verbs are soft escalate only`() {
        val v = ActionGuard.check(Step.Tap(1, "提交订单"))
        assertTrue(v is Verdict.Escalate)
        assertTrue("下单只做软提级，不中断", (v as Verdict.Escalate).soft)
        assertFalse(v.isBlocking)
    }

    // ---------- 单调性：不可绕过的不变式 ----------

    @Test
    fun `model risk can never produce pass`() {
        val risks = listOf(
            Risk(RiskClass.PAYMENT, "e1", 0.1f),
            Risk(RiskClass.CREDENTIAL, "e2", 0.5f),
            Risk(RiskClass.UNKNOWN_SENSITIVE, "e3", 1.0f),
        )
        risks.forEach { r ->
            val v = PolicyDecider.decide(ruleHit = null, modelRisk = r)
            assertTrue("模型上报永远不能返回 Pass", v is Verdict.Escalate)
        }
    }

    @Test
    fun `hard rule always kills regardless of model`() {
        val hit = RuleHit(hard = true, reason = "secure-window")
        val v = PolicyDecider.decide(hit, Risk(RiskClass.PAYMENT, "noise", 0f))
        assertTrue(v is Verdict.Kill)
    }

    @Test
    fun `soft escalate yields to hard escalate`() {
        val soft = Verdict.Escalate(Risk(RiskClass.ORDER_ONLY, "提交订单"), soft = true)
        val hard = Verdict.Escalate(Risk(RiskClass.CREDENTIAL, "支付密码"))
        val merged = PolicyDecider.decide(null, soft, null)
        assertFalse(merged.isBlocking)

        val merged2 = PolicyDecider.decide(null, soft, Risk(RiskClass.CREDENTIAL, "支付密码"))
        assertTrue("软提级必须被硬信号覆盖", merged2.isBlocking)
        assertTrue(hard.isBlocking)
    }

    // ---------- 单一数据源 ----------

    @Test
    fun `prompt fragment contains every rule keyword`() {
        val prompt = PolicyVocab.promptFragment()
        PolicyVocab.CREDENTIAL_HINTS.forEach {
            assertTrue("提示词缺少凭证词：$it", prompt.contains(it))
        }
        PolicyVocab.PAY_VERBS.forEach {
            assertTrue("提示词缺少支付动词：$it", prompt.contains(it))
        }
    }

    @Test
    fun `payment schemes are detected`() {
        assertTrue(PolicyVocab.paymentSchemeHit("alipays://platformapi/startapp?x=1"))
        assertTrue(PolicyVocab.paymentSchemeHit("weixin://dl/business"))
        assertFalse(PolicyVocab.paymentSchemeHit("https://example.com/pay"))
    }
}
