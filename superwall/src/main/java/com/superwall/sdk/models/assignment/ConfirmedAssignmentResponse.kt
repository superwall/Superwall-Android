package com.superwall.sdk.models.assignment

import com.superwall.sdk.models.SerializableEntity

import kotlinx.serialization.*
import kotlinx.serialization.json.*

@Serializable
data class ConfirmedAssignmentResponse(val assignments: MutableList<Assignment>): SerializableEntity
