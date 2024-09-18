package com.superwall.sdk.network

import GeoService
import Given
import Then
import When
import com.superwall.sdk.dependencies.ApiFactory
import com.superwall.sdk.misc.Either
import com.superwall.sdk.models.geo.GeoInfo
import com.superwall.sdk.models.geo.GeoWrapper
import com.superwall.sdk.network.session.CustomHttpUrlConnection
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GeoServiceTest {
    private lateinit var customHttpUrlConnection: CustomHttpUrlConnection
    private lateinit var apiFactory: ApiFactory
    private lateinit var service: GeoService
    private lateinit var executor: RequestExecutor

    @Before
    fun setup() {
        customHttpUrlConnection = mockk()
        apiFactory = mockk()
        executor = mockk()
        service =
            GeoService(
                host = "test.com",
                version = "/v1/",
                factory = apiFactory,
                customHttpUrlConnection =
                    CustomHttpUrlConnection(
                        CoroutineScope(Dispatchers.IO),
                        Json { ignoreUnknownKeys = true },
                        executor,
                    ),
            )
    }

    @Test
    fun test_geo_success() =
        runTest {
            Given("a valid request for geo information") {
                val mockResponse =
                    GeoWrapper(
                        GeoInfo(
                            country = "US",
                            region = "CA",
                            city = "San Francisco",
                            postalCode = "94105",
                            longitude = -122.3959,
                            latitude = 37.7911,
                            regionCode = "CA",
                            continent = "NA",
                            timezone = "America/Los_Angeles",
                            metroCode = "USD",
                        ),
                    )
                coEvery { executor.execute(any()) } returns
                    Either.Success(
                        RequestResult(
                            requestId = "1",
                            responseCode = 200,
                            responseMessage = Json.encodeToString(mockResponse),
                            duration = 10.0,
                            headers = mapOf("Authorization" to "Bearer token"),
                        ),
                    )
                coEvery { apiFactory.makeHeaders(any(), any()) } returns mapOf("Authorization" to "Bearer token")

                When("requesting geo information") {
                    val result = service.geo()

                    Then("the result should be a success with the expected geo information") {
                        assertTrue(result is Either.Success)
                        assertEquals(mockResponse, (result as Either.Success).value)
                    }
                }
            }
        }

    @Test
    fun test_geo_failure() =
        runTest {
            Given("a network error occurs") {
                val error = NetworkError.Unknown()
                coEvery { executor.execute(any()) } returns Either.Failure(error)
                coEvery { apiFactory.makeHeaders(any(), any()) } returns mapOf("Authorization" to "Bearer token")

                When("requesting geo information") {
                    val result = service.geo()

                    Then("the result should be a failure") {
                        assertTrue(result is Either.Failure)
                        assertEquals(error, (result as Either.Failure).error)
                    }
                }
            }
        }
}
