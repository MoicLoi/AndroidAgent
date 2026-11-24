package dev.anroidagent.core.media

/**
 * 受保护的路径。
 *
 * 相册清理是**唯一会直接删除用户文件**的功能，所以路径级黑名单是硬需求：
 * 包级黑名单（WeChat/QQ/游戏/银行）在这里不管用，因为我们要看的是文件。
 *
 * 原则：宁可少清，不可误删。
 */
object SafePaths {

    private val PROTECTED_PREFIXES = listOf(
        "/sdcard/Android/data/",
        "/sdcard/Android/obb/",
        "/sdcard/Android/media/",
        "/sdcard/tencent/",
        "/sdcard/Tencent/",
        "/sdcard/WeiXin/",
        "/sdcard/weixin/",
        "/sdcard/MicroMsg/",
        "/sdcard/DCIM/.thumbnails",
        "/sdcard/Pictures/.agent_trash", // 我们自己的暂存区，不能当回收目标
        "/data/",
    )

    /** 路径里出现这些包名一律排除 */
    private val PROTECTED_PACKAGE_MARKERS = listOf(
        "com.tencent.mm",
        "com.tencent.mobileqq",
        "com.miui.gallery/databases",
    )

    fun isProtected(path: String): Boolean {
        val p = path.trim()
        if (p.isEmpty()) return true
        if (PROTECTED_PREFIXES.any { p.startsWith(it, ignoreCase = true) }) return true
        if (PROTECTED_PACKAGE_MARKERS.any { p.contains(it, ignoreCase = true) }) return true
        return false
    }

    /** 清理目标是否可回收：必须是不在保护区的普通文件路径 */
    fun canReclaim(path: String): Boolean = !isProtected(path)
}
