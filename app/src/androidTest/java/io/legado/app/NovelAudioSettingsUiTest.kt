package io.legado.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTextInputSelection
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.help.readaloud.server.NovelAudioServerSettingsDraft
import io.legado.app.ui.book.read.config.NovelAudioServerConfigContent
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NovelAudioSettingsUiTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun selectedAddressUsesTheDialogAccent() {
        lateinit var style: AppDialogStyle
        val draft = NovelAudioServerSettingsDraft().withUrl("https://audio.invalid/").withToken("fixture-token")
        rule.setContent {
            LegadoComposeTheme {
                style = rememberAppDialogStyle()
                NovelAudioServerConfigContent(draft, false, "", false, {}, {}, {}, {}, {})
            }
        }
        val address = rule.onNodeWithText("服务地址")
        address.performClick()
        address.performTextInputSelection(TextRange(0, 15))
        val pixels = address.captureToImage().toPixelMap()
        val selection = style.accent.copy(alpha = 0.4f).compositeOver(style.fieldSurface)
        val selectedPixels = (0 until pixels.width).sumOf { x ->
            (0 until pixels.height).count { y ->
                val pixel = pixels[x, y]
                kotlin.math.abs(pixel.red - selection.red) < 0.015f &&
                    kotlin.math.abs(pixel.green - selection.green) < 0.015f &&
                    kotlin.math.abs(pixel.blue - selection.blue) < 0.015f
            }
        }
        assertTrue("选区背景应使用弹框强调色", selectedPixels > 100)
    }

    @Test
    fun changingServerClearsTheOldTokenAndConsent() {
        var draft by mutableStateOf(NovelAudioServerSettingsDraft()
            .withUrl("http://first.invalid/").withToken("fixture-token").withHttpConsent(true))
        rule.setContent {
            LegadoComposeTheme {
                NovelAudioServerConfigContent(draft, false, "", false, { draft = it }, {}, {}, {}, {})
            }
        }
        rule.onNodeWithText("服务地址").performTextReplacement("http://second.invalid/")
        rule.runOnIdle {
            assertEquals("", draft.token)
            assertFalse(draft.allowInsecureHttp)
        }
        rule.onNodeWithText("保存").assertIsNotEnabled()
        rule.onNodeWithText("测试连接").assertIsNotEnabled()
    }

    @Test
    fun draftActionsAreExplicitAndBusyStateBlocksRepeats() {
        var busy by mutableStateOf(false)
        var tested = 0
        var saved = 0
        var cleared = 0
        val draft = NovelAudioServerSettingsDraft().withUrl("https://audio.invalid/").withToken("fixture-token")
        rule.setContent {
            LegadoComposeTheme {
                NovelAudioServerConfigContent(
                    draft, busy, "", false, {},
                    { tested++ }, { saved++ }, { cleared++ }, {}
                )
            }
        }
        rule.onNodeWithText("测试连接").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(1, tested)
            assertEquals(0, saved)
            assertEquals(0, cleared)
            busy = true
        }
        rule.onNodeWithText("保存").assertIsNotEnabled()
        rule.onNodeWithText("测试连接").assertIsNotEnabled()
        rule.onNodeWithText("清除配置").assertIsNotEnabled()
        rule.runOnIdle { busy = false }
        rule.onNodeWithText("保存").performClick()
        rule.runOnIdle { assertEquals(1, saved) }
        rule.onNodeWithText("清除配置").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1, cleared) }
    }

    @Test
    fun failedConnectionShowsAnErrorAndKeepsTheDraft() {
        val draft = NovelAudioServerSettingsDraft().withUrl("https://audio.invalid/").withToken("fixture-token")
        rule.setContent {
            LegadoComposeTheme {
                NovelAudioServerConfigContent(
                    draft, false, "连接未就绪，请检查服务后重试", true, {}, {}, {}, {}, {}
                )
            }
        }
        rule.onNodeWithText("连接未就绪，请检查服务后重试").performScrollTo().assertIsDisplayed()
        rule.runOnIdle { assertEquals("fixture-token", draft.token) }
    }
}
