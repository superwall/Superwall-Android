package com.superwall.sdk.models.events

import com.superwall.sdk.models.serialization.AnySerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.*

@kotlinx.serialization.ExperimentalSerializationApi
class EventDataSerializerTest {

    private val json = Json {
        serializersModule = SerializersModule {
            contextual(EventDataSerializer)
            contextual(AnySerializer)
        }
    }

    @Test
    fun testSerialization() {
        val eventData = EventData(
            id = "1234",
            name = "Test Event",
            parameters = mapOf("param1" to "value1", "param2" to 42),
            createdAt = Date(1634165048000)
        )
        val jsonString = json.encodeToString(EventDataSerializer, eventData)

        println("jsonString: $jsonString")

        assertEquals(
            """{"id":"1234","name":"Test Event","parameters":{"param1":"value1","param2":42},"createdAt":"2021-10-13T22:44:08.000"}""",
            jsonString
        )
    }

    @Test
    fun testDeserialization() {
        val jsonString =
            """{"id":"1234","name":"Test Event","parameters":{"param1":"value1","param2":42},"createdAt":"2021-10-13T22:44:08.000"}"""
        val eventData = json.decodeFromString(EventDataSerializer, jsonString)
        assertEquals("1234", eventData.id)
        assertEquals("Test Event", eventData.name)
        assertEquals(mapOf("param1" to "value1", "param2" to 42), eventData.parameters)
        assertEquals(Date(1634165048000), eventData.createdAt)
    }

    @Test
    fun testRoundTrip() {
        val eventData = EventData(
            id = "5678",
            name = "Another Event",
            parameters = mapOf("key1" to 123, "key2" to "stringValue"),
            createdAt = Date(1634200000000)
        )
        val jsonString = json.encodeToString(EventDataSerializer, eventData)
        val decodedEventData = json.decodeFromString(EventDataSerializer, jsonString)
        assertEquals(eventData, decodedEventData)
    }
}