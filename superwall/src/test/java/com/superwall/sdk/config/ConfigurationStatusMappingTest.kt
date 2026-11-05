package com.superwall.sdk.config

import com.superwall.sdk.Given
import com.superwall.sdk.Then
import com.superwall.sdk.When
import com.superwall.sdk.config.models.ConfigurationStatus
import com.superwall.sdk.models.config.Config
import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigurationStatusMappingTest {
    @Test
    fun `retrieved config maps to configured`() =
        Given("a retrieved config") {
            val state = ConfigState.Retrieved(Config.stub())

            val status = When("it is mapped") { state.toConfigurationStatus() }

            Then("the status is configured") {
                assertEquals(ConfigurationStatus.Configured, status)
            }
        }

    @Test
    fun `failed config maps to failed`() =
        Given("a failed config fetch") {
            val state = ConfigState.Failed(IllegalStateException("boom"))

            val status = When("it is mapped") { state.toConfigurationStatus() }

            Then("the status is failed") {
                assertEquals(ConfigurationStatus.Failed, status)
            }
        }

    @Test
    fun `in-flight and empty states map to pending`() =
        Given("the states before a config is known") {
            val states = listOf(ConfigState.None, ConfigState.Retrieving, ConfigState.Retrying)

            val statuses = When("each is mapped") { states.map { it.toConfigurationStatus() } }

            Then("every one is pending") {
                assertEquals(List(3) { ConfigurationStatus.Pending }, statuses)
            }
        }
}
