package dev.anroidagent.core.device

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import java.util.TimeZone

/**
 * 设备身份的**唯一读取点**。
 *
 * 没有注入层（hook）之后，追加包无法影响基础包直接读到的 `Build.*` /
 * `Settings.Secure`。因此所有"可能被伪装"的字段都必须经过这里：
 * - 基础版：返回真实值
 * - 追加包存在：由追加包下发的配置构造伪造实现
 *
 * 硬规则：仓库内**禁止**在其它位置直接访问 Build / Settings.Secure /
 * TelephonyManager / WifiManager.getMacAddress。CI 里有 grep 门禁。
 */
interface DeviceInfoProvider {
    val androidId: String
    val brand: String
    val model: String
    val manufacturer: String
    val fingerprint: String
    val timezone: String
    val densityDpi: Int
    val screenWidth: Int
    val screenHeight: Int
    val appVersion: String

    /** 供协议层/Signer 使用的扁平字段表 */
    fun asMap(): Map<String, String>
}

@SuppressLint("HardwareIds")
class RealDeviceInfo(
    private val ctx: Context,
    private val screenW: Int,
    private val screenH: Int,
    private val versionName: String,
) : DeviceInfoProvider {

    override val androidId: String by lazy {
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
    }
    override val brand: String get() = Build.BRAND ?: ""
    override val model: String get() = Build.MODEL ?: ""
    override val manufacturer: String get() = Build.MANUFACTURER ?: ""
    override val fingerprint: String get() = Build.FINGERPRINT ?: ""
    override val timezone: String get() = TimeZone.getDefault().id
    override val densityDpi: Int get() = ctx.resources.displayMetrics.densityDpi
    override val screenWidth: Int get() = screenW
    override val screenHeight: Int get() = screenH
    override val appVersion: String get() = versionName

    override fun asMap(): Map<String, String> = mapOf(
        "android_id" to androidId,
        "brand" to brand,
        "model" to model,
        "manufacturer" to manufacturer,
        "fingerprint" to fingerprint,
        "timezone" to timezone,
        "density_dpi" to densityDpi.toString(),
        "screen_w" to screenWidth.toString(),
        "screen_h" to screenHeight.toString(),
        "app_version" to appVersion,
    )
}

/**
 * 一致性自检。
 *
 * 伪造指纹最常见的翻车点：只改 androidId 不改机型/屏幕，服务端交叉比对直接命中。
 * 上线前必须跑一次。
 */
object ConsistencyChecker {

    data class Issue(val field: String, val detail: String)

    /** 常见机型 → 合理屏幕宽度集合（示例表，实际按目标市场补充） */
    private val KNOWN_WIDTHS: Map<String, Set<Int>> = mapOf(
        "Pixel 8" to setOf(1080),
        "Pixel 7" to setOf(1080),
        "Mi 14" to setOf(1200, 1220),
        "SM-S9280" to setOf(1440),
    )

    fun check(d: DeviceInfoProvider): List<Issue> {
        val issues = mutableListOf<Issue>()

        if (d.androidId.length != 16) {
            issues.add(Issue("android_id", "长度异常：${d.androidId.length}"))
        }
        if (d.model.isBlank() || d.brand.isBlank()) {
            issues.add(Issue("model/brand", "为空"))
        }

        val widths = KNOWN_WIDTHS[d.model]
        if (widths != null && d.screenWidth !in widths) {
            issues.add(
                Issue("screen_w", "${d.model} 常见宽度为 $widths，当前 ${d.screenWidth}"),
            )
        }

        if (d.densityDpi <= 0) {
            issues.add(Issue("density_dpi", "非法值"))
        }
        if (d.timezone.isBlank()) {
            issues.add(Issue("timezone", "为空"))
        }

        return issues
    }
}
