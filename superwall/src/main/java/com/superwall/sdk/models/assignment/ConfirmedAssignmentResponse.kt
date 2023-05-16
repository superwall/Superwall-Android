package com.superwall.sdk.models.assignment

import Assignment

import kotlinx.serialization.*
import kotlinx.serialization.json.*

@Serializable
data class ConfirmedAssignmentResponse(val assignments: MutableList<Assignment>)
