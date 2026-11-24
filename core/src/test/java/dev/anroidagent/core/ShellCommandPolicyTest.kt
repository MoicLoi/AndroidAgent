package dev.anroidagent.core

import dev.anroidagent.core.policy.ShellClass
import dev.anroidagent.core.policy.ShellCommandPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * shell 命令分类闸门。
 *
 * 这是"让模型主动调 adb"这条能力的安全边界：分类错了，
 * 模型就能拿到 shell。所以每个分支都要有负例。
 */
class ShellCommandPolicyTest {

    @Test
    fun `read only commands pass`() {
        listOf(
            "dumpsys activity activities",
            "dumpsys window windows",
            "getprop ro.build.version.sdk",
            "settings get global policy_control",
            "pm list packages",
            "pm path com.example.app",
            "wm size",
            "wm density",
            "id",
            "whoami",
            "date",
            "ps",
            "cat /sys/class/net/wlan0/address",
        ).forEach {
            assertEquals("应为只读：$it", ShellClass.READ_ONLY, ShellCommandPolicy.classify(it))
        }
    }

    @Test
    fun `pipelines with filters stay read only`() {
        listOf(
            "pm list packages | grep tencent",
            "dumpsys window windows | grep SECURE",
            "dumpsys activity activities | head -20",
            "dumpsys package com.a.b | grep permission | wc -l",
        ).forEach {
            assertEquals("管道过滤应放行：$it", ShellClass.READ_ONLY, ShellCommandPolicy.classify(it))
        }
    }

    @Test
    fun `mutating commands need confirmation`() {
        listOf(
            "settings put global policy_control immersive.full=*",
            "pm clear com.example.app",
            "pm grant com.a.b android.permission.CAMERA",
            "pm suspend com.a.b",
            "am force-stop com.a.b",
            "am start -n com.a.b/.Main",
            "input tap 100 200",
            "cmd appops set com.a.b CAMERA deny",
            "wm size 1080x1920",
        ).forEach {
            assertEquals("应需确认：$it", ShellClass.MUTATING, ShellCommandPolicy.classify(it))
        }
    }

    @Test
    fun `dangerous commands are forbidden`() {
        listOf(
            "rm -rf /sdcard",
            "su",
            "su -c id",
            "reboot",
            "dd if=/dev/zero of=/dev/block/sda",
            "mkfs.ext4 /dev/block/sda",
            "mount -o rw,remount /system",
            "chmod 777 /data",
            "kill -9 1234",
            "setenforce 0",
        ).forEach {
            assertEquals("应拒绝：$it", ShellClass.FORBIDDEN, ShellCommandPolicy.classify(it))
        }
    }

    @Test
    fun `command chaining is always forbidden`() {
        listOf(
            "dumpsys activity; rm -rf /sdcard",
            "getprop x && reboot",
            "getprop x || reboot",
            "echo \$(id)",
            "echo `id`",
            "dumpsys activity > /sdcard/x",
            "cat /proc/1/cmdline < /dev/zero",
            "getprop a\ngetprop b",
        ).forEach {
            assertEquals("命令链应拒绝：$it", ShellClass.FORBIDDEN, ShellCommandPolicy.classify(it))
        }
    }

    @Test
    fun `unknown commands are treated as forbidden`() {
        listOf(
            "mycustomtool --flag",
            "python3 script.py",
            "sh",
            "toybox",
            "grep foo",
        ).forEach {
            assertEquals("未知命令一律拒绝：$it", ShellClass.FORBIDDEN, ShellCommandPolicy.classify(it))
        }
    }

    @Test
    fun `empty and overlong pipelines are rejected`() {
        assertEquals(ShellClass.FORBIDDEN, ShellCommandPolicy.classify(""))
        assertEquals(ShellClass.FORBIDDEN, ShellCommandPolicy.classify("   "))

        val tooLong = (1..6).joinToString(" | ") { "grep x" }
        assertEquals(ShellClass.FORBIDDEN, ShellCommandPolicy.classify("dumpsys | $tooLong"))
    }

    @Test
    fun `mixed pipeline takes the strictest class`() {
        // 只读命令 + 变更命令 → 整体按变更处理
        assertEquals(
            ShellClass.MUTATING,
            ShellCommandPolicy.classify("dumpsys activity | grep x | pm clear com.a.b"),
        )
    }

    @Test
    fun `prompt examples cover the same vocabulary`() {
        val text = ShellCommandPolicy.promptExamples()
        assertTrue(text.contains("dumpsys"))
        assertTrue(text.contains("settings put"))
        assertTrue(text.contains("pm clear"))
        assertTrue(text.contains("getprop"))
    }
}
