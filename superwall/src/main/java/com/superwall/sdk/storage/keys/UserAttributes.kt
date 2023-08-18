package com.superwall.sdk.storage.keys

import com.superwall.sdk.models.serialization.AnyMapSerializer
import com.superwall.sdk.models.serialization.AnySerializer
import com.superwall.sdk.storage.CacheDirectory
import com.superwall.sdk.storage.CacheHelper
import com.superwall.sdk.storage.StorageConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encodeToString
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement


object UserAttributesConfig: StorageConfig {
    override val key: String = "store.userAttributes"
    override var directory: CacheDirectory = CacheDirectory.UserSpecificDocuments
}


object UserAttributesSerializer : KSerializer<UserAttributes> {
    override val descriptor: SerialDescriptor
        get() = buildClassSerialDescriptor("EventData") {
            element<Map<String, JsonElement>>("attributes")
        }

    override fun serialize(encoder: Encoder, value: UserAttributes) {
        val compositeEncoder = encoder.beginStructure(descriptor)
        compositeEncoder.encodeSerializableElement(descriptor, 0, AnyMapSerializer, value.attributes)
        compositeEncoder.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): UserAttributes {
        lateinit var attributes: Map<String, Any>

        val dec: CompositeDecoder = decoder.beginStructure(descriptor)
        loop@ while (true) {
            when (val i = dec.decodeElementIndex(descriptor)) {
                CompositeDecoder.DECODE_DONE -> break@loop
                0 -> attributes = dec.decodeSerializableElement(descriptor, i, MapSerializer(String.serializer(), AnySerializer))
                else -> throw SerializationException("Unknown index $i")
            }
        }
        dec.endStructure(descriptor)
        return UserAttributes(attributes)
    }
}

@Serializable(with = UserAttributesSerializer::class)
data class UserAttributes(var attributes: Map<String, @Serializable(with = AnySerializer::class) Any>)

class UserAttributesManager(cacheHelper: CacheHelper) {
    private val mutex = Mutex()
    private val cacheHelper = cacheHelper
//


    fun get(): UserAttributes? {
        this.cacheHelper.read(UserAttributesConfig)?.let {
            return Json.decodeFromString(it.decodeToString())
        }
        return null
    }

    fun set(userAttributes: UserAttributes) {
        this.cacheHelper.write(UserAttributesConfig, Json.encodeToString(userAttributes).toByteArray(Charsets.UTF_8))
    }


   fun delete() {
        this.cacheHelper.delete(UserAttributesConfig)
    }
}
