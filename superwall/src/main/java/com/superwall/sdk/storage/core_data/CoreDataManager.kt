package com.superwall.sdk.storage.core_data

import com.superwall.sdk.models.events.EventData
import com.superwall.sdk.models.triggers.TriggerRuleOccurrence

typealias ManagedEventData = EventData
typealias ManagedTriggerRuleOccurrence = TriggerRuleOccurrence


interface CoreDataManagerInterface {
    fun saveEventData(
        eventData: EventData,
        completion: ((ManagedEventData) -> Unit)?
    )

    fun save(
        triggerRuleOccurrence: TriggerRuleOccurrence,
        completion: ((ManagedTriggerRuleOccurrence) -> Unit)?
    )

    fun deleteAllEntities()

    fun countTriggerRuleOccurrences(
        ruleOccurrence: TriggerRuleOccurrence
    ): Int
}


class CoreDataManager : CoreDataManagerInterface {
    override fun saveEventData(
        eventData: EventData,
        completion: ((ManagedEventData) -> Unit)?
    ) {
        if (completion != null) {
            completion(eventData)
        }
    }

    override fun save(
        triggerRuleOccurrence: TriggerRuleOccurrence,
        completion: ((ManagedTriggerRuleOccurrence) -> Unit)?
    ) {
        // TODO: ??
    }

    override fun deleteAllEntities() {
        // TODO: ??
    }

    override fun countTriggerRuleOccurrences(ruleOccurrence: TriggerRuleOccurrence): Int {
        // TODO: ?
        return 0
    }

}