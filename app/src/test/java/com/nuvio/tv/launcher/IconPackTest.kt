package com.nuvio.tv.launcher

import com.nuvio.tv.domain.model.AppIconChoice
import com.nuvio.tv.domain.model.AppIconLook
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IconPackTest {
    private val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private val manifest = File(main, "AndroidManifest.xml").readText()
    private val appfilter = File(main, "res/xml/appfilter.xml").readText()
    private val aliases = Regex("<item name=\"([a-z_]+)\" type=\"drawable\">@mipmap/([a-z_]+)</item>")
        .findAll(File(main, "res/values/icon_pack.xml").readText())
        .associate { it.groupValues[1] to it.groupValues[2] }

    private fun activities(): List<String> = manifest.split("</activity>").mapNotNull { part ->
        val start = part.lastIndexOf("<activity")
        if (start < 0) null else part.substring(start)
    }

    private fun appfilterEntries(): Map<String, String> =
        Regex("<item component=\"ComponentInfo\\{([^/]+)/([^}]+)\\}\" drawable=\"([a-z_]+)\" />")
            .findAll(appfilter)
            .associate { match ->
                assertEquals("com.nuvio.bipindas", match.groupValues[1])
                match.groupValues[2] to match.groupValues[3]
            }

    @Test
    fun releasePackageMatchesTheAppfilter() {
        val gradle = listOf(File("build.gradle.kts"), File("app/build.gradle.kts")).first { it.isFile }.readText()
        assertTrue(gradle.contains("applicationId = \"com.nuvio.bipindas\""))
    }

    @Test
    fun everyLauncherEntryHasItsTransparentBanner() {
        val entries = appfilterEntries()
        val launcherNames = activities()
            .filter { it.contains("android.intent.category.LEANBACK_LAUNCHER") || it.contains("android.intent.category.LAUNCHER") }
            .map { "com.nuvio.tv" + Regex("android:name=\"([^\"]+)\"").find(it)!!.groupValues[1] }
        assertEquals(AppIconChoice.all.size, launcherNames.size)
        assertEquals(launcherNames.toSet(), entries.keys)
        AppIconChoice.all.forEach { choice ->
            val drawable = entries.getValue(launcherClassName(choice))
            assertEquals(iconPackDrawableName(choice), drawable)
            val expectedLook = if (choice.look == AppIconLook.CARD) "card" else "lettering"
            assertEquals("arc_${expectedLook}_clear_${choice.colour.key}", drawable)
        }
    }

    @Test
    fun assetsCopyMatchesTheResource() {
        assertEquals(appfilter, File(main, "assets/appfilter.xml").readText())
    }

    @Test
    fun everyNamedDrawableIsATransparentBannerInBothSizes() {
        val drawableXml = File(main, "res/xml/drawable.xml").readText()
        val named = Regex("drawable=\"([a-z_]+)\"").findAll(appfilter + drawableXml).map { it.groupValues[1] }.toSet()
        assertEquals(32, aliases.size)
        named.forEach { name ->
            val mipmap = aliases[name]
            assertTrue("$name has no alias", mipmap != null)
            assertTrue(mipmap!!, mipmap.contains("_clear_"))
            listOf("mipmap-xhdpi", "mipmap-xxxhdpi").forEach { dir ->
                assertTrue("$dir/$mipmap", File(main, "res/$dir/$mipmap.webp").isFile)
            }
        }
    }

    @Test
    fun supporterColoursStayOutOfTheOpenList() {
        val drawableXml = File(main, "res/xml/drawable.xml").readText()
        AppIconChoice.all.filter { it.transparent }.forEach { choice ->
            val listed = drawableXml.contains("\"${iconPackDrawableName(choice)}\"")
            assertEquals(iconPackDrawableName(choice), choice.colour.entitlement == null, listed)
        }
    }

    @Test
    fun shrinkerKeepsThePack() {
        val keep = File(main, "res/raw/keep_icon_pack.xml").readText()
        listOf("@xml/appfilter", "@xml/drawable", "@drawable/arc_card_clear_*", "@drawable/arc_lettering_clear_*")
            .forEach { assertTrue(it, keep.contains(it)) }
    }

    @Test
    fun iconPackEntriesAddNothingToTheHomeScreen() {
        listOf(".launcher.IconPackActivity", ".launcher.IconPickerActivity").forEach { name ->
            val block = activities().single { it.contains("android:name=\"$name\"") }
            assertFalse(name, block.contains("android.intent.category.LAUNCHER"))
            assertFalse(name, block.contains("android.intent.category.LEANBACK_LAUNCHER"))
            assertFalse(name, block.contains("android:banner"))
        }
    }
}
