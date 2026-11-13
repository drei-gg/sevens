package gg.seven.fast.core

import kotlin.math.abs
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

object ArchiveCodec {
    const val maxBytes = 25 * 1024 * 1024
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    }

    fun encode(archive: FastArchive): String = json.encodeToString(archive)

    fun decode(text: String, now: Double): FastArchive {
        require(text.toByteArray().size <= maxBytes) { "Backup exceeds 25 MB" }
        val tree = json.parseToJsonElement(text).jsonObject
        require(tree["schemaVersion"]?.jsonPrimitive?.int == 1) { "Unsupported backup version" }
        // A truncated document must not decode to an empty archive through Kotlin defaults.
        require(
            listOf("fasts", "journal", "metrics", "plans", "restDays", "presets", "settings").all {
                it in tree
            }
        ) {
            "Incomplete backup"
        }
        return json.decodeFromJsonElement<FastArchive>(tree).validated(now)
    }

    fun merge(incoming: FastArchive, current: FastArchive, now: Double): FastArchive {
        incoming.validate(now)
        current.validate(now)
        fun <T> merged(
            old: List<T>,
            added: List<T>,
            id: (T) -> String,
            same: (T, T) -> Boolean = { a, b -> a == b },
        ): List<T> {
            val known = old.associateBy { id(it).uppercase() }
            added.forEach { item ->
                known[id(item).uppercase()]?.let {
                    require(same(it, item)) {
                        "Conflicting record ${id(item)}; nothing was imported"
                    }
                }
            }
            return old + added.filterNot { id(it).uppercase() in known }
        }
        return current
            .copy(
                fasts =
                    merged(current.fasts, incoming.fasts, { it.id }) { a, b ->
                        a.copy(start = b.start, end = b.end) == b &&
                            close(a.start, b.start) &&
                            close(a.end, b.end)
                    },
                journal =
                    merged(current.journal, incoming.journal, { it.id }) { a, b ->
                        a.copy(date = b.date) == b && close(a.date, b.date)
                    },
                metrics =
                    merged(current.metrics, incoming.metrics, { it.id }) { a, b ->
                        a.copy(date = b.date) == b && close(a.date, b.date)
                    },
                plans =
                    merged(current.plans, incoming.plans, { it.id }) { a, b ->
                        a.copy(start = b.start) == b && close(a.start, b.start)
                    },
                restDays =
                    merged(current.restDays, incoming.restDays, { it.id }) { a, b ->
                        a.copy(date = b.date) == b && close(a.date, b.date)
                    },
                presets = merged(current.presets, incoming.presets, { it.id }),
            )
            .validated(now)
    }

    private fun close(a: Double?, b: Double?) =
        if (a == null || b == null) a == b else abs(a - b) <= 0.001

    fun count(a: FastArchive) =
        a.fasts.size +
            a.journal.size +
            a.metrics.size +
            a.plans.size +
            a.restDays.size +
            a.presets.size

    fun csv(a: FastArchive): String {
        val rows =
            mutableListOf(
                listOf(
                    "type",
                    "id",
                    "start",
                    "end",
                    "goal_minutes",
                    "eating_minutes",
                    "timezone",
                    "fast_id",
                    "kind",
                    "value",
                    "unit",
                    "intention",
                    "reflection_or_text",
                    "tags",
                    "mood",
                    "energy",
                    "hunger",
                    "external_id",
                )
            )
        fun row(
            type: String,
            id: String,
            start: Double? = null,
            end: Double? = null,
            goal: Int? = null,
            eating: Int? = null,
            zone: String = "",
            fast: String = "",
            kind: String = "",
            value: String = "",
            unit: String = "",
            intention: String = "",
            text: String = "",
            tags: List<String> = emptyList(),
            mood: Int? = null,
            energy: Int? = null,
            hunger: Int? = null,
            external: String = "",
        ) {
            rows +=
                listOf(
                    type,
                    id,
                    start?.instant()?.toString().orEmpty(),
                    end?.instant()?.toString().orEmpty(),
                    goal?.toString().orEmpty(),
                    eating?.toString().orEmpty(),
                    zone,
                    fast,
                    kind,
                    value,
                    unit,
                    intention,
                    text,
                    tags.joinToString(";"),
                    mood?.toString().orEmpty(),
                    energy?.toString().orEmpty(),
                    hunger?.toString().orEmpty(),
                    external,
                )
        }
        a.fasts
            .sortedBy { it.start }
            .forEach {
                row(
                    "fast",
                    it.id,
                    it.start,
                    it.end,
                    it.goalMinutes,
                    it.eatingMinutes,
                    it.timeZoneID,
                    intention = it.intention,
                    text = it.reflection,
                    tags = it.tags,
                )
            }
        a.journal
            .sortedBy { it.date }
            .forEach {
                row(
                    "journal",
                    it.id,
                    it.date,
                    fast = it.fastID.orEmpty(),
                    text = it.text,
                    tags = it.tags,
                    mood = it.mood,
                    energy = it.energy,
                    hunger = it.hunger,
                )
            }
        a.metrics
            .sortedBy { it.date }
            .forEach {
                row(
                    "metric",
                    it.id,
                    it.date,
                    kind = it.kind.name,
                    value = it.value.toString(),
                    unit = it.kind.unit,
                    external = it.externalID.orEmpty(),
                )
            }
        a.plans.forEach {
            row(
                "plan",
                it.id,
                it.start,
                goal = it.goalMinutes,
                eating = it.eatingMinutes,
                intention = it.intention,
            )
        }
        a.restDays.forEach { row("rest", it.id, it.date, text = it.note) }
        a.presets.forEach {
            row(
                "preset",
                it.id,
                goal = it.goalMinutes,
                eating = it.eatingMinutes,
                intention = it.name,
            )
        }
        return rows.joinToString("\r\n", postfix = "\r\n") {
            it.joinToString(",") { cell ->
                val protected =
                    if (cell.trimStart().firstOrNull() in listOf('=', '+', '-', '@')) "'$cell"
                    else cell
                "\"${protected.replace("\"", "\"\"")}\""
            }
        }
    }
}
