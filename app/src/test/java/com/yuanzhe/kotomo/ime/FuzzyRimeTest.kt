package com.yuanzhe.kotomo.ime

import com.yuanzhe.kotomo.engine.Rime
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Host test T08 (PLAN §9.6): feeds the rule file the app generates ([Fuzzy.yaml]) to the host
 * librime on the prebuilt data, the way the deploy process and the keyboard use it: deploy into a
 * staging directory, move `build/` into the user directory, open there.
 *
 * Notes: runs only in tests/conversion/run.sh (`kotomo.corpus.root` set, host libraries built);
 * a plain `./gradlew check` skips it. The corpus lines T08-* cover each rule's positive and
 * negative cases; this test covers what the corpus cannot: the private schema, the swap and going
 * back to the prebuilt data.
 */
class FuzzyRimeTest {
    private lateinit var shared: File
    private lateinit var tmp: File

    @Before
    fun setUp() {
        val root = System.getProperty("kotomo.corpus.root")?.let(::File)
        assumeTrue("run tests/conversion/run.sh", root?.isDirectory == true)
        shared = root!!.resolve("build/rime-prebuilt")
        tmp = Files.createTempDirectory("kotomo-fuzzy").toFile()
    }

    @After
    fun tearDown() {
        if (::tmp.isInitialized) {
            Rime.shutdown()
            tmp.deleteRecursively()
        }
    }

    /** Returns the texts of the first [limit] candidates for [input]. */
    private fun candidates(input: String, limit: Int = 5): List<String> {
        Rime.clear()
        Rime.setInput(input)
        return Rime.getCandidates(limit).map { it.text }
    }

    /**
     * Deploys [rules] as the app does: the rule file into a staging user directory, a deploy
     * there, then `build/` moved into [user]; opens librime on [user].
     */
    private fun deployLikeTheApp(rules: Set<FuzzyRule>, user: File) {
        val staging = tmp.resolve("staging").apply { deleteRecursively(); mkdirs() }
        staging.resolve(Fuzzy.FILE).writeText(checkNotNull(Fuzzy.yaml(rules)))
        staging.resolve("build").mkdirs()
        Rime.initialize(shared.path, staging.path)
        assertTrue("deploy with ${rules.map { it.key }}: ${Rime.deployFailures()}", Rime.deploy())
        Rime.shutdown()
        user.resolve("build").deleteRecursively()
        user.mkdirs()
        assertTrue(staging.resolve("build").renameTo(user.resolve("build")))
        assertTrue(Rime.initialize(shared.path, user.path))
        Rime.setOption("zh_hans", true)
    }

    @Test
    fun generatedRulesDeployAndApplyToBothSchemas() {
        val user = tmp.resolve("user").apply { mkdirs() }
        assertTrue(Rime.initialize(shared.path, user.path))
        Rime.setOption("zh_hans", true)
        // T08 negative: the prebuilt data has no fuzzy pinyin.
        assertTrue("prebuilt: zongguo offers 中国", "中国" !in candidates("zongguo"))

        deployLikeTheApp(setOf(FuzzyRule.Z_ZH), user)
        assertEquals("z/zh on: zongguo", "中国", candidates("zongguo").first())
        assertEquals("z/zh on: zhongguo unchanged", "中国", candidates("zhongguo").first())
        assertEquals("z/zh on: nihao unchanged", "你好", candidates("nihao").first())
        // Only the rule that is on applies: c/ch stays off.
        assertTrue("z/zh on: cifan offers 吃饭", "吃饭" !in candidates("cifan"))

        // The schema for sensitive fields carries the same rules.
        assertTrue(Rime.setLearning(false))
        Rime.setOption("zh_hans", true)
        assertEquals("private schema: zongguo", "中国", candidates("zongguo").first())
        assertTrue(Rime.setLearning(true))
    }

    @Test
    fun allRulesOffReturnsToThePrebuiltData() {
        val user = tmp.resolve("user").apply { mkdirs() }
        deployLikeTheApp(setOf(FuzzyRule.Z_ZH, FuzzyRule.N_L), user)
        assertEquals("lihao", "你好", candidates("lihao").first())
        Rime.shutdown()
        // What the keyboard does when every switch is off: no rule file, no deployed build.
        assertNull(Fuzzy.yaml(emptySet()))
        user.resolve("build").deleteRecursively()
        assertTrue(Rime.initialize(shared.path, user.path))
        Rime.setOption("zh_hans", true)
        assertTrue("rules off: lihao offers 你好", "你好" !in candidates("lihao"))
        assertTrue("rules off: zongguo offers 中国", "中国" !in candidates("zongguo"))
        assertEquals("rules off: nihao", "你好", candidates("nihao").first())
    }
}
