package com.superwall.sdk.store.testmode

import com.superwall.sdk.models.config.Config
import com.superwall.sdk.store.testmode.models.TestStoreUserType

/** Pure decision logic — `null` means deactivate. */
internal object TestModeLogic {
    fun evaluate(
        config: Config,
        bundleId: String,
        appUserId: String?,
        aliasId: String?,
        behavior: TestModeBehavior,
        isTestEnvironment: Boolean,
    ): TestModeReason? =
        when (behavior) {
            TestModeBehavior.NEVER -> null
            TestModeBehavior.ALWAYS -> TestModeReason.TestModeOption
            TestModeBehavior.WHEN_ENABLED_FOR_USER ->
                checkConfigMatch(config, appUserId, aliasId)
            TestModeBehavior.AUTOMATIC -> {
                if (isTestEnvironment) {
                    null
                } else {
                    checkConfigMatch(config, appUserId, aliasId)
                        ?: checkPackageNameMismatch(config, bundleId)
                }
            }
        }

    private fun checkConfigMatch(
        config: Config,
        appUserId: String?,
        aliasId: String?,
    ): TestModeReason? {
        val testUsers = config.testModeUserIds ?: return null
        for (testUser in testUsers) {
            val match =
                when (testUser.type) {
                    TestStoreUserType.UserId -> appUserId == testUser.value
                    TestStoreUserType.AliasId -> aliasId == testUser.value
                }
            if (match) {
                return TestModeReason.ConfigMatch(matchedId = testUser.value)
            }
        }
        return null
    }

    private fun checkPackageNameMismatch(
        config: Config,
        actualPackageName: String,
    ): TestModeReason? {
        val expectedPackageName = config.bundleIdConfig
        if (expectedPackageName.isNullOrEmpty()) return null
        if (expectedPackageName == actualPackageName) return null
        // Treat actual = expected + ".something" as an extension/variant — not a mismatch.
        if (actualPackageName.startsWith("$expectedPackageName.")) return null
        return TestModeReason.ApplicationIdMismatch(
            expected = expectedPackageName,
            actual = actualPackageName,
        )
    }
}
