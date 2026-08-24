package com.cmi.codex.sonar

import java.nio.file.Files
import java.nio.file.NoSuchFileException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class RealPathResolverTest {
    @Test
    fun `returns canonical path for an existing file`() {
        val directory = Files.createTempDirectory("sonar-ide-real-path-")
        try {
            Files.createDirectory(directory.resolve("nested"))
            val file = Files.writeString(directory.resolve("Finding.java"), "class Finding {}")
            val nonCanonicalPath = directory.resolve("nested").resolve("..").resolve(file.fileName)

            assertEquals(file.toRealPath(), resolveRealPath(nonCanonicalPath, "unavailable"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `converts a missing file IOException to an invalid request error`() {
        val directory = Files.createTempDirectory("sonar-ide-missing-path-")
        try {
            val missingPath = directory.resolve("MissingFinding.java")

            val error = assertFailsWith<IllegalArgumentException> {
                resolveRealPath(missingPath, "file does not exist or is inaccessible")
            }

            assertEquals("file does not exist or is inaccessible", error.message)
            assertIs<NoSuchFileException>(runCatching { missingPath.toRealPath() }.exceptionOrNull())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
