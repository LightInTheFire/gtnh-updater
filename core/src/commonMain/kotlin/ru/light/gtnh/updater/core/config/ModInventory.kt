package ru.light.gtnh.updater.core.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.openZip
import okio.use

private const val MODS_DIRECTORY = "mods"
private const val MOD_BASELINE = ".gtnh-shipped-mods.json"
private const val MOD_METADATA = "/mcmod.info"
private const val MAX_METADATA_BYTES = 128L * 1024
private val versionDirectory = Regex("""[0-9]+\.[0-9]+(?:\.[0-9]+)?""")
private val versionSuffix = Regex("""(?:^|[-_. +\[])v?\d+\.\d+""", RegexOption.IGNORE_CASE)
private val defaultServerListJar = Regex("""defaultserverlist(?:[-_].*)?\.jar""", RegexOption.IGNORE_CASE)

data class ExtraMods(val hasBaseline: Boolean, val paths: List<String>)

private fun isDefaultServerListJar(name: String): Boolean = defaultServerListJar.matches(name)

internal fun removeDefaultServersMod(fs: FileSystem, game: Path) {
    val mods = game / MODS_DIRECTORY
    check(fs.metadata(game).symlinkTarget == null) { "The instance has a linked game directory." }
    val metadata = fs.metadataOrNull(mods) ?: return
    check(metadata.isDirectory && metadata.symlinkTarget == null) { "The instance has an invalid mods directory." }
    for (file in fs.list(mods)) {
        if (!isDefaultServerListJar(file.name)) continue
        val entry = fs.metadata(file)
        if (entry.isRegularFile || entry.symlinkTarget != null) fs.delete(file)
    }
}

internal class ModInventory(private val fs: FileSystem) {
    fun storeBaseline(instance: Path, game: Path) {
        val file = instance / MOD_BASELINE
        check(!fs.exists(file)) { "The client archive contains the updater's mod inventory." }
        val mods = JsonArray(modFiles(game).map(::JsonPrimitive))
        fs.write(file, mustCreate = true) { writeUtf8(mods.toString()) }
    }

    fun review(source: Path, previousGame: Path, nextGame: Path): ExtraMods {
        val baseline = loadBaseline(source)
        val next = modFiles(nextGame)
        val nextFamilies = next.map(::family).toSet()
        val stock = baseline?.map(::family)?.toSet().orEmpty()
        val candidates = modFiles(previousGame).filter { path ->
            !isDefaultServerListJar(path.substringAfterLast('/')) && family(path) !in stock && family(path) !in nextFamilies
        }
        if (candidates.isEmpty()) return ExtraMods(baseline != null, emptyList())
        val nextIds = next.flatMap { modIds(nextGame / it) }.toSet()
        val stockIds = if (baseline != null) {
            val byName = baseline.toSet()
            modFiles(previousGame).filter { it in byName }.flatMap { modIds(previousGame / it) }.toSet()
        } else {
            emptySet()
        }

        val available = candidates.filter { path ->
            !fs.exists(nextGame / path) && modIds(previousGame / path).none { it in nextIds || it in stockIds }
        }
        return ExtraMods(baseline != null, available)
    }

    fun ensureDistinctSelection(game: Path, paths: Set<String>) {
        val ids = mutableSetOf<String>()
        val families = mutableSetOf<String>()
        for (path in paths) {
            check(families.add(family(path)) && modIds(game / path).all(ids::add)) {
                "Multiple versions of the same extra mod were selected: ${path.substringAfterLast('/')}."
            }
        }
    }

    private fun loadBaseline(instance: Path): List<String>? = runCatching {
        val path = instance / MOD_BASELINE
        val metadata = fs.metadataOrNull(path) ?: return null
        if (!metadata.isRegularFile || metadata.symlinkTarget != null ||
            (metadata.size ?: Long.MAX_VALUE) > MAX_METADATA_BYTES
        ) return null
        fs.read(path) {
            val json = Json.parseToJsonElement(readUtf8()) as JsonArray
            json.map { (it as JsonPrimitive).content }
        }
    }.getOrNull()

    private fun modFiles(game: Path): List<String> {
        val root = game / MODS_DIRECTORY
        if (!isDirectory(root)) return emptyList()
        return buildList {
            for (path in fs.list(root)) {
                if (isJar(path)) add("$MODS_DIRECTORY/${path.name}")
                else if (versionDirectory.matches(path.name) && isDirectory(path)) {
                    fs.list(path).filter(::isJar).forEach { add("$MODS_DIRECTORY/${path.name}/${it.name}") }
                }
            }
        }.sortedBy { it.lowercase() }
    }

    private fun isDirectory(path: Path): Boolean =
        fs.metadataOrNull(path)?.let { it.isDirectory && it.symlinkTarget == null } == true

    private fun isJar(path: Path): Boolean =
        path.name.endsWith(".jar", ignoreCase = true) &&
            fs.metadataOrNull(path)?.let { it.isRegularFile && it.symlinkTarget == null } == true

    private fun family(path: String): String {
        val name = path.substringAfterLast('/').dropLast(".jar".length).lowercase()
        val suffix = versionSuffix.find(name)?.range?.first ?: name.length
        return name.take(suffix).ifBlank { name }.filter(Char::isLetterOrDigit)
    }

    private fun modIds(path: Path): Set<String> = runCatching {
        fs.openZip(path).use { zip ->
            val info = MOD_METADATA.toPath()
            val metadata = zip.metadataOrNull(info) ?: return emptySet()
            if ((metadata.size ?: Long.MAX_VALUE) > MAX_METADATA_BYTES) return emptySet()
            val root = zip.read(info) {
                if (request(MAX_METADATA_BYTES + 1)) return@read null
                Json.parseToJsonElement(readUtf8().removePrefix("\uFEFF"))
            }
            val modules = when (root) {
                is JsonArray -> root
                is JsonObject -> root["modList"] as? JsonArray ?: JsonArray(listOf(root))
                else -> return emptySet()
            }
            modules.mapNotNull { module ->
                ((module as? JsonObject)?.get("modid") as? JsonPrimitive)
                    ?.content?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            }.toSet()
        }
    }.getOrDefault(emptySet())
}
