package dev.anroidagent.core.policy

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import java.security.MessageDigest

enum class TargetClass { WECHAT, QQ, GAME, FINANCE, PAYMENT, OTHER }

/**
 * 一条硬禁用规则。包名可被改包名/多开绕过，所以同时用**证书指纹**兜底：
 * 改包名不改签名 → 证书仍命中；重签则无法登录，也就不构成目标。
 */
data class BannedRule(
    val id: String,
    val cls: TargetClass,
    val packages: Set<String> = emptySet(),
    val certSha256: Set<String> = emptySet(),
)

object BannedTargets {

    val RULES: List<BannedRule> = listOf(
        BannedRule(
            id = "wechat",
            cls = TargetClass.WECHAT,
            packages = setOf(
                "com.tencent.mm",
                "com.tencent.wechat",
            ),
            // 填入真机 `pm dump` 取到的证书指纹后生效；留空表示仅按包名匹配
            certSha256 = emptySet(),
        ),
        BannedRule(
            id = "qq",
            cls = TargetClass.QQ,
            packages = setOf(
                "com.tencent.mobileqq",
                "com.tencent.tim",
                "com.tencent.qqlite",
                "com.tencent.mobileqqi",
                "com.tencent.edu",
            ),
            certSha256 = emptySet(),
        ),
        BannedRule(
            id = "finance",
            cls = TargetClass.FINANCE,
            packages = setOf(
                "com.eg.android.AlipayGphone",
                "com.unionpay",
                "com.unionpay.mobilepay",
                "com.antfortune.wealth",
            ),
            certSha256 = emptySet(),
        ),
    )

    /** 供外部（如 ps 检测）使用的快速匹配集 */
    val ALL_PACKAGES: Set<String> = RULES.flatMap { it.packages }.toSet()

    fun byPackage(pkg: String): BannedRule? =
        RULES.firstOrNull { pkg in it.packages }

    fun byCert(digest: String?): BannedRule? {
        if (digest.isNullOrBlank()) return null
        return RULES.firstOrNull { digest in it.certSha256 }
    }
}

/**
 * 白名单：基础版默认拒绝，只允许显式支持的目标。
 * 与 [BannedTargets] 冲突时，禁令优先（先查禁，再查白）。
 */
interface Allowlist {
    fun contains(pkg: String): Boolean
}

class InMemoryAllowlist(initial: Set<String> = emptySet()) : Allowlist {
    private val set = initial.toMutableSet()
    override fun contains(pkg: String): Boolean = pkg in set
    fun add(pkg: String) { set += pkg }
    fun remove(pkg: String) { set -= pkg }
    fun all(): Set<String> = set.toSet()
}

/**
 * 目标守卫。fail-closed：任何读取失败都按拒绝处理。
 */
class TargetGuard(
    private val pm: PackageManager,
    private val allowlist: Allowlist,
) {

    sealed interface Decision {
        data object Allowed : Decision
        data class Denied(
            val ruleId: String,
            val cls: TargetClass,
            val why: String,
        ) : Decision
    }

    fun check(pkg: String): Decision {
        if (pkg.isBlank()) return deny("unknown", TargetClass.OTHER, "empty-pkg")

        BannedTargets.byPackage(pkg)?.let {
            return Decision.Denied(it.id, it.cls, "explicit-block")
        }

        val info: PackageInfo = runCatching {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        }.getOrNull() ?: return deny("unreadable", TargetClass.OTHER, "pkg-not-readable")

        BannedTargets.byCert(certSha256(info))?.let {
            return Decision.Denied(it.id, it.cls, "cert-block")
        }

        if (info.applicationInfo?.category == ApplicationInfo.CATEGORY_GAME) {
            return deny("game-category", TargetClass.GAME, "app-category")
        }

        if (!allowlist.contains(pkg)) {
            return deny("not-in-allowlist", TargetClass.OTHER, "not-allowlisted")
        }

        return Decision.Allowed
    }

    private fun deny(id: String, cls: TargetClass, why: String) =
        Decision.Denied(id, cls, why)

    private fun certSha256(info: PackageInfo): String? = runCatching {
        val signer = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return null
        MessageDigest.getInstance("SHA-256")
            .digest(signer.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }.getOrNull()
}

/** shell 执行器抽象，由追加包注入，核心模块不依赖 Shizuku */
fun interface ShellExec {
    fun exec(cmd: String): String
}

/**
 * 安全窗口（`FLAG_SECURE`）检测。
 *
 * 这是系统级的"此窗口内容敏感"标记，覆盖银行、支付、密码输入、部分 IM 会话，
 * 比任何包名/关键词方案都可靠。
 *
 * 注意 [viaDumpsys] 的失败默认值：这里返回 false，但追加包**必须**先在启动时
 * 用 [selfTest] 断言能力可用，否则不允许启用——直接 fail-closed 会导致
 * dumpsys 格式不符的机型上每一步都误杀。
 */
object SecureWindowWatch {

    fun selfTest(shell: ShellExec): Boolean = runCatching {
        shell.exec("dumpsys window windows").isNotBlank()
    }.getOrDefault(false)

    fun viaDumpsys(shell: ShellExec): Boolean = runCatching {
        shell.exec("dumpsys window windows")
            .lineSequence()
            .any { it.contains("SECURE") }
    }.getOrDefault(false)

    /**
     * 基础版（无 shell）的推断：截图全黑但无障碍树仍有节点。
     * 宁可误判为 secure，因为 secure 页面上本来也无法自动化。
     */
    fun viaBlackFrame(nodeCount: Int, isUniformlyBlack: Boolean): Boolean =
        isUniformlyBlack && nodeCount > 5
}
