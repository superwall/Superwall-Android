package com.superwall.sdk.models.assignment

import Assignment
import org.json.JSONArray
import org.json.JSONObject

data class ConfirmedAssignmentResponse(var assignments: MutableList<Assignment>) {

    // Converts this ConfirmedAssignmentResponse object to a JSONObject.
    fun toJson(): JSONObject {
        val jsonObject = JSONObject()
        val jsonArray = JSONArray()

        for (assignment in assignments) {
            jsonArray.put(assignment.toJson())
        }

        jsonObject.put("assignments", jsonArray)

        return jsonObject
    }

    // Creates a ConfirmedAssignmentResponse object from a JSONObject.
    companion object {
        fun fromJson(jsonObject: JSONObject): ConfirmedAssignmentResponse {
            val assignmentsJson = jsonObject.getJSONArray("assignments")
            val assignments = mutableListOf<Assignment>()

            for (i in 0 until assignmentsJson.length()) {
                assignments.add(Assignment.fromJson(assignmentsJson.getJSONObject(i)))
            }

            return ConfirmedAssignmentResponse(assignments)
        }
    }
}
