package com.superwall.superapp.test

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superwall.superapp.ui.theme.MyApplicationTheme

class TimelineViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MyApplicationTheme {
                TimelineViewerRoot()
            }
        }
    }
}

@Composable
private fun TimelineViewerRoot() {
    var selectedTimeline by remember { mutableStateOf<Pair<String, EventTimeline>?>(null) }
    val timelines by TimelineStore.timelinesFlow.collectAsState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        if (selectedTimeline == null) {
            TimelineListScreen(
                timelines = timelines,
                onSelect = { name, timeline -> selectedTimeline = name to timeline },
            )
        } else {
            val (name, timeline) = selectedTimeline!!
            TimelineDetailScreen(
                name = name,
                timeline = timeline,
                onBack = { selectedTimeline = null },
            )
        }
    }
}

@Composable
private fun TimelineListScreen(
    timelines: Map<String, EventTimeline>,
    onSelect: (String, EventTimeline) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Event Timelines",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(16.dp),
        )

        if (timelines.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No timelines recorded yet.\nRun a test to capture events.",
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            LazyColumn {
                timelines.entries.sortedByDescending { it.value.allEvents().size }.forEach { (name, timeline) ->
                    item(key = name) {
                        val liveEvents by timeline.eventsFlow.collectAsState()
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .clickable { onSelect(name, timeline) },
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = name,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = "${liveEvents.size} events",
                                        color = Color.Gray,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    val lastElapsed = liveEvents.lastOrNull()?.elapsed
                                    Text(
                                        text = if (lastElapsed != null) formatDuration(lastElapsed.inWholeMilliseconds) else "--",
                                        color = MaterialTheme.colorScheme.primary,
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimelineDetailScreen(
    name: String,
    timeline: EventTimeline,
    onBack: () -> Unit,
) {
    val events by timeline.eventsFlow.collectAsState()
    var firstSelected by remember { mutableStateOf<Int?>(null) }
    var secondSelected by remember { mutableStateOf<Int?>(null) }

    // Reset selection if events list shrinks (e.g. timeline cleared)
    if (firstSelected != null && firstSelected!! >= events.size) firstSelected = null
    if (secondSelected != null && secondSelected!! >= events.size) secondSelected = null

    val selectionDuration by remember(firstSelected, secondSelected, events) {
        derivedStateOf {
            val a = firstSelected
            val b = secondSelected
            if (a != null && b != null && a < events.size && b < events.size) {
                val first = events[minOf(a, b)]
                val second = events[maxOf(a, b)]
                second.elapsed - first.elapsed
            } else {
                null
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(16.dp),
        ) {
            Text(
                text = "< Back",
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onBack() },
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = name,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Total: ${formatDuration(timeline.totalDuration().inWholeMilliseconds)}",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "${events.size} events",
                    color = Color.Gray,
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            // Preload expandable section
            PreloadSection(events)

            // Selection duration banner
            val currentSelection = selectionDuration
            if (currentSelection != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(12.dp),
                ) {
                    val fromIdx = minOf(firstSelected!!, secondSelected!!)
                    val toIdx = maxOf(firstSelected!!, secondSelected!!)
                    Column {
                        Text(
                            text = "Selection: ${formatDuration(currentSelection.inWholeMilliseconds)}",
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "${events[fromIdx].eventName} -> ${events[toIdx].eventName}",
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            } else if (firstSelected != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(12.dp),
                ) {
                    Text(
                        text = "Long press a second event to measure duration",
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        // Event list
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(events, key = { index, _ -> index }) { index, event ->
                val isFirst = index == firstSelected
                val isSecond = index == secondSelected
                val isInRange = run {
                    val a = firstSelected
                    val b = secondSelected
                    a != null && b != null && index in minOf(a, b)..maxOf(a, b)
                }

                val bgColor by animateColorAsState(
                    targetValue = when {
                        isFirst || isSecond -> MaterialTheme.colorScheme.primaryContainer
                        isInRange -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        else -> Color.Transparent
                    },
                    label = "eventBg",
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bgColor)
                        .combinedClickable(
                            onClick = {
                                // Clear selection on tap
                                firstSelected = null
                                secondSelected = null
                            },
                            onLongClick = {
                                when {
                                    firstSelected == null -> firstSelected = index
                                    secondSelected == null -> secondSelected = index
                                    else -> {
                                        firstSelected = index
                                        secondSelected = null
                                    }
                                }
                            },
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "#$index",
                                color = Color.Gray,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                modifier = Modifier.width(32.dp),
                            )
                            Text(
                                text = event.eventName,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        Text(
                            text = formatDuration(event.elapsed.inWholeMilliseconds),
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    // Delta from previous event
                    if (index > 0) {
                        val delta = event.elapsed - events[index - 1].elapsed
                        Text(
                            text = "+${formatDuration(delta.inWholeMilliseconds)}",
                            fontFamily = FontFamily.Monospace,
                            color = Color.Gray,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(start = 32.dp),
                        )
                    }

                    // Show params if non-empty
                    if (event.params.isNotEmpty()) {
                        Text(
                            text = event.params.entries.joinToString(", ") { "${it.key}=${it.value}" },
                            color = Color.Gray,
                            fontSize = 10.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 32.dp, top = 2.dp),
                        )
                    }
                }
                HorizontalDivider(color = Color.LightGray.copy(alpha = 0.3f))
            }
        }
    }
}

private data class LoadCounts(val started: Int, val completed: Int, val failed: Int)

@Composable
private fun PreloadSection(events: List<TimedEvent>) {
    var expanded by remember { mutableStateOf(false) }

    val preloadDuration by remember(events) {
        derivedStateOf {
            val configAttr = events.firstOrNull { it.eventName == "config_attributes" }
            val lastWebviewComplete = events.lastOrNull { it.eventName == "paywallWebviewLoad_complete" }
            if (configAttr != null && lastWebviewComplete != null) {
                lastWebviewComplete.elapsed - configAttr.elapsed
            } else {
                null
            }
        }
    }

    val preloadCounts by remember(events) {
        derivedStateOf {
            LoadCounts(
                started = events.count { it.eventName == "paywallPreload_start" },
                completed = events.count { it.eventName == "paywallPreload_complete" },
                failed = 0, // no preload error event exists
            )
        }
    }

    val responseCounts by remember(events) {
        derivedStateOf {
            LoadCounts(
                started = events.count { it.eventName == "paywallResponseLoad_start" },
                completed = events.count { it.eventName == "paywallResponseLoad_complete" },
                failed = events.count {
                    it.eventName == "paywallResponseLoad_fail" || it.eventName == "paywallResponseLoad_notFound"
                },
            )
        }
    }

    val webviewCounts by remember(events) {
        derivedStateOf {
            LoadCounts(
                started = events.count { it.eventName == "paywallWebviewLoad_start" },
                completed = events.count { it.eventName == "paywallWebviewLoad_complete" },
                failed = events.count {
                    it.eventName == "paywallWebviewLoad_fail" || it.eventName == "paywallWebviewLoad_timeout"
                },
            )
        }
    }

    val productsCounts by remember(events) {
        derivedStateOf {
            LoadCounts(
                started = events.count { it.eventName == "paywallProductsLoad_start" },
                completed = events.count { it.eventName == "paywallProductsLoad_complete" },
                failed = events.count { it.eventName == "paywallProductsLoad_fail" },
            )
        }
    }

    val hasAnyPreloadEvents = preloadCounts.started > 0 || responseCounts.started > 0 ||
        webviewCounts.started > 0 || productsCounts.started > 0

    if (!hasAnyPreloadEvents && preloadDuration == null) return

    Spacer(modifier = Modifier.height(6.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f))
            .clickable { expanded = !expanded }
            .padding(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (expanded) "v" else ">",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.width(20.dp),
                )
                Text(
                    text = "Preload",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            preloadDuration?.let { d ->
                Text(
                    text = formatDuration(d.inWholeMilliseconds),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(start = 20.dp, top = 8.dp)) {
                if (preloadCounts.started > 0) {
                    LoadCountRow("Preload", preloadCounts)
                }
                if (responseCounts.started > 0) {
                    LoadCountRow("Response", responseCounts)
                }
                if (webviewCounts.started > 0) {
                    LoadCountRow("Webview", webviewCounts)
                }
                if (productsCounts.started > 0) {
                    LoadCountRow("Products", productsCounts)
                }
            }
        }
    }
}

@Composable
private fun LoadCountRow(label: String, counts: LoadCounts) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CountBadge(count = counts.started, label = "started", color = Color.Gray)
            CountBadge(count = counts.completed, label = "done", color = Color(0xFF4CAF50))
            if (counts.failed > 0) {
                CountBadge(count = counts.failed, label = "failed", color = Color(0xFFF44336))
            }
        }
    }
}

@Composable
private fun CountBadge(count: Int, label: String, color: Color) {
    Text(
        text = "$count $label",
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        color = color,
    )
}

private fun formatDuration(ms: Long): String =
    when {
        ms < 1000 -> "${ms}ms"
        ms < 60_000 -> "%.2fs".format(ms / 1000.0)
        else -> {
            val minutes = ms / 60_000
            val seconds = (ms % 60_000) / 1000.0
            "${minutes}m %.1fs".format(seconds)
        }
    }
