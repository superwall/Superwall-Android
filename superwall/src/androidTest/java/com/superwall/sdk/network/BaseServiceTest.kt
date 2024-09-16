package com.superwall.sdk.network

import BaseHostService
import com.superwall.sdk.dependencies.ApiFactory
import com.superwall.sdk.misc.Either
import com.superwall.sdk.models.config.Config
import com.superwall.sdk.network.session.CustomHttpUrlConnection
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test

class BaseServiceTest {
    fun CoroutineScope.service(
        connection: CustomHttpUrlConnection,
        factory: ApiFactory,
    ) = BaseHostService(
        host = "test",
        version = "test",
        factory = factory,
        customHttpUrlConnection = connection,
        json = Json { ignoreUnknownKeys = true },
    )

    @Test
    fun test_calling_config() =
        runTest {
            val customConnection = mockk<CustomHttpUrlConnection>()
            val service =
                service(
                    customConnection,
                    mockk {
                        coEvery { makeHeaders(any(), any()) } returns mapOf("test" to "test")
                    },
                )

            coEvery {
                customConnection.request<Config>({
                    throw IllegalStateException()
                }, 2)
            } throws IllegalStateException()

            val res = service.config("test")
            assert(res is Either.Failure)
        }
}
