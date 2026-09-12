package dev.tsdroid

import org.junit.Assert.assertEquals
import org.junit.Test

/** 安全网自检：确认 JVM 单测链路可用（能发现用例、能断言、Android 桩不炸）。 */
class BuildSmokeTest {

    @Test
    fun unitTestInfrastructureWorks() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun androidLogStubDoesNotThrow() {
        android.util.Log.i("TS3TEST", "Log 桩可用")
    }
}
