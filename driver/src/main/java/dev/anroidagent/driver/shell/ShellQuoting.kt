package dev.anroidagent.driver.shell

/**
 * shell 参数引用。
 *
 * 放在 driver 层是因为它有两个使用者：追加包的 `ShellCommands`
 * 和 app 侧的暂存区移动器。基础包不能依赖追加包，所以公共实现必须下沉。
 */
object ShellQuoting {

    /** 单引号包裹并转义内部单引号，可安全拼进 `sh -c` */
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
