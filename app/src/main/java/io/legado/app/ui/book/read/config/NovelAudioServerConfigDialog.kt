package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.readaloud.ReadAloudConfigChangeNotifier
import io.legado.app.help.readaloud.server.NovelAudioAndroidConfigStore
import io.legado.app.help.readaloud.server.NovelAudioServerException
import io.legado.app.help.readaloud.server.NovelAudioServerSettings
import io.legado.app.help.readaloud.server.NovelAudioServerSettingsDraft
import io.legado.app.help.readaloud.speech.SpeechRoute
import io.legado.app.model.ReadAloud
import io.legado.app.ui.theme.ThemeSync
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.toMiuixPalette
import kotlinx.coroutines.CoroutineStart

open class NovelAudioServerConfigDialog : ComposeDialogFragment() {
    override val dialogSize = AppDialogSize.Form
    private var draft by mutableStateOf(NovelAudioServerSettingsDraft())
    private var busy by mutableStateOf(true)
    private var notice by mutableStateOf("")
    private var error by mutableStateOf(false)
    private var operation: Coroutine<*>? = null
    private var operationEpoch = 0L

    /**
     * Kept overridable for isolated instrumentation. Production always uses the
     * Android Keystore-backed store; tests must never touch that pair.
     */
    internal open fun newSettings(context: android.content.Context): NovelAudioServerSettings =
        NovelAudioServerSettings(NovelAudioAndroidConfigStore.open(context))

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            LegadoComposeTheme {
                NovelAudioServerConfigContent(
                    draft, busy, notice, error,
                    onDraftChange = { draft = it; notice = ""; error = false },
                    onTest = {
                        val snapshot = draft
                        runOperation({ testConnection(snapshot) }) {
                            notice = "连接正常，分析与语音服务均已就绪；尚未保存"
                        }
                    },
                    onSave = {
                        val snapshot = draft
                        pauseNovelAudio()
                        runOperation({
                            save(snapshot)
                            // 同步提交成功后必须通知；视图取消只能丢弃 UI 回调，不能撤销落盘。
                            ReadAloudConfigChangeNotifier.notifyEngine()
                        }) {
                            notice = "已保存。请在朗读引擎中选择 AI 多角色听书"
                        }
                    },
                    onClear = {
                        showComposeConfirmDialog(
                            title = "清除 AI 听书服务配置？",
                            message = "清除地址和访问令牌，保留已下载音频。",
                            positiveText = "清除", dangerPositive = true,
                            onPositive = {
                                if (!busy && view != null) {
                                    pauseNovelAudio()
                                    runOperation({
                                        clear()
                                        ReadAloudConfigChangeNotifier.notifyEngine()
                                    }) {
                                        draft = NovelAudioServerSettingsDraft()
                                        notice = "已清除服务配置"
                                    }
                                }
                            }
                        )
                    },
                    onClose = { dismiss() }
                )
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        runOperation({ load() }) { draft = it }
    }

    private fun pauseNovelAudio() {
        if (SpeechRoute.resolveSpeechRoute(ReadAloud.ttsEngine).engineType == SpeechRoute.ENGINE_NOVEL_AUDIO) {
            // 凭据变更撤销当前播放/准备授权；保存本身不自动重新请求云服务。
            ReadAloud.pause(requireContext())
        }
    }

    private fun <T> runOperation(
        block: suspend NovelAudioServerSettings.() -> T,
        success: (T) -> Unit
    ) {
        val appContext = requireContext().applicationContext
        val epoch = ++operationEpoch
        busy = true
        isCancelable = false
        notice = ""
        error = false
        operation = Coroutine.async(viewLifecycleOwner.lifecycleScope, start = CoroutineStart.LAZY) {
            newSettings(appContext).block()
        }.onSuccess {
            if (epoch == operationEpoch) success(it)
        }.onError {
            if (epoch == operationEpoch) {
                error = true
                notice = NovelAudioServerException(
                    (it as? NovelAudioServerException)?.kind ?: "UNAVAILABLE"
                ).message.orEmpty()
            }
        }.onFinally {
            if (epoch == operationEpoch) {
                busy = false
                isCancelable = true
            }
        }.also { it.start() }
    }

    override fun onDestroyView() {
        operationEpoch++
        operation?.cancel()
        operation = null
        draft = NovelAudioServerSettingsDraft()
        super.onDestroyView()
    }
}

@Composable
internal fun NovelAudioServerConfigContent(
    draft: NovelAudioServerSettingsDraft,
    busy: Boolean,
    notice: String,
    error: Boolean,
    onDraftChange: (NovelAudioServerSettingsDraft) -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit
) {
    // 栈内主题切换也要重组，不能仅依赖宿主 Activity 重建。
    @Suppress("UNUSED_VARIABLE")
    val themeVersion = ThemeSync.version
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    val valid = remember(draft) { kotlin.runCatching { draft.credentials() }.isSuccess }
    AppDialogFrame(
        title = "AI 多角色听书服务",
        message = "选择此引擎并主动播放或下载时，章节正文将发送到所配置的服务，" +
            "用于角色分析和语音生成。测试连接只检查服务状态。",
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NovelAudioConfigField("服务地址", draft.baseUrl, !busy, false, style) {
                    onDraftChange(draft.withUrl(it))
                }
                Text("填写服务根地址，不带 /v1；更换地址需重新填写令牌。", color = style.secondaryText)
                NovelAudioConfigField("访问令牌", draft.token, !busy, true, style) {
                    onDraftChange(draft.withToken(it))
                }
                if (draft.baseUrl.trim().startsWith("http://", ignoreCase = true)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = draft.allowInsecureHttp,
                            onCheckedChange = { onDraftChange(draft.withHttpConsent(it)) },
                            enabled = !busy,
                            colors = CheckboxDefaults.colors(
                                checkedColor = style.accent, uncheckedColor = style.secondaryText,
                                checkmarkColor = style.onAccent,
                                disabledCheckedColor = style.secondaryText,
                                disabledUncheckedColor = style.secondaryText,
                                disabledIndeterminateColor = style.secondaryText
                            )
                        )
                        Text("允许明文 HTTP（仅可信局域网，正文与令牌不加密传输）", color = style.danger)
                    }
                }
                Text("令牌加密保存在本机，不随备份导出。保存或清除将暂停当前 AI 听书。", color = style.secondaryText)
                if (notice.isNotBlank()) Text(notice, color = if (error) style.danger else style.primaryText)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LegadoMiuixActionButton(
                        "测试连接", palette, onTest, enabled = !busy && valid,
                        cornerRadius = style.actionRadius
                    )
                    LegadoMiuixActionButton(
                        "清除配置", palette, onClear, enabled = !busy, danger = true,
                        cornerRadius = style.actionRadius
                    )
                }
                if (busy) Text("处理中…", color = style.secondaryText)
            }
        },
        actions = {
            LegadoMiuixActionButton(
                "关闭", palette, onClose, enabled = !busy, cornerRadius = style.actionRadius
            )
            LegadoMiuixActionButton(
                "保存", palette, onSave, enabled = !busy && valid, primary = true,
                cornerRadius = style.actionRadius
            )
        }
    )
}

@Composable
private fun NovelAudioConfigField(
    label: String, value: String, enabled: Boolean, secret: Boolean,
    style: AppDialogStyle, onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, enabled = enabled,
        modifier = Modifier.fillMaxWidth(), singleLine = true,
        label = { Text(label) }, shape = RoundedCornerShape(style.actionRadius),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = style.primaryText, unfocusedTextColor = style.primaryText,
            disabledTextColor = style.secondaryText,
            focusedContainerColor = style.fieldSurface, unfocusedContainerColor = style.fieldSurface,
            disabledContainerColor = style.fieldSurface,
            cursorColor = style.accent, focusedBorderColor = style.accent,
            selectionColors = TextSelectionColors(
                handleColor = style.accent,
                backgroundColor = style.accent.copy(alpha = 0.4f)
            ),
            unfocusedBorderColor = style.stroke, disabledBorderColor = style.stroke,
            focusedLabelColor = style.accent, unfocusedLabelColor = style.secondaryText,
            disabledLabelColor = style.secondaryText
        ),
        textStyle = LocalTextStyle.current.copy(color = style.primaryText, fontFamily = style.bodyFontFamily)
    )
}
