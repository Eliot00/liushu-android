/*
 *     Copyright (C) 2023  Elliot Xu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Affero General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Affero General Public License for more details.
 *
 *     You should have received a copy of the GNU Affero General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.elliot00.liushu.input.service

import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.elliot00.liushu.input.InputView
import com.elliot00.liushu.input.data.CapsLockState
import com.elliot00.liushu.input.data.InputMethodAction
import com.elliot00.liushu.input.data.InputViewState
import com.elliot00.liushu.uniffi.Candidate
import com.elliot00.liushu.uniffi.Engine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import timber.log.Timber
import java.io.File

class LiushuInputMethodService : LifecycleInputMethodService(), SavedStateRegistryOwner {
    lateinit var engine: Engine

    private var torchModule: Module? = null
    private val pnyn2idx = mutableMapOf<String, Int>()
    private val idx2hanzi = mutableMapOf<Int, String>()

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry = savedStateRegistryController.savedStateRegistry

    override val lifecycle = dispatcher.lifecycle

    private val _state = MutableStateFlow(InputViewState())
    val state = _state.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        Timber.d("InputMethodService onCreate")
        savedStateRegistryController.performRestore(null)
        val dictDir = "sunman"
        val dictFile = "sunman.trie"
        val path = sequenceOf(filesDir, dictDir, dictFile).joinToString(separator = File.separator)
        engine = Engine(path)
        initTorchModule()
        initVocab()
    }

    private fun initTorchModule() {
        val modelFile = File(filesDir, "sunman/model.pte")
        if (modelFile.exists()) {
            torchModule = Module.load(modelFile.absolutePath)
            Timber.d("PyTorch model loaded")
        } else {
            Timber.e("Model file not found")
        }
    }

    private fun initVocab() {
        val vocabFile = File(filesDir, "sunman/vocab.json")
        if (vocabFile.exists()) {
            try {
                val jsonArray = JSONArray(vocabFile.readText())
                if (jsonArray.length() >= 4) {
                    // 读取 pnyn2idx（数组第0个元素）
                    val pnyn2idxObj = jsonArray.getJSONObject(0)
                    pnyn2idx.clear()
                    val keys = pnyn2idxObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        pnyn2idx[key] = pnyn2idxObj.getInt(key)
                    }
                    Timber.d("pnyn2idx loaded, size: %d", pnyn2idx.size)

                    // 读取 idx2hanzi（数组第3个元素）
                    val idx2hanziObj = jsonArray.getJSONObject(3)
                    idx2hanzi.clear()
                    val idxKeys = idx2hanziObj.keys()
                    while (idxKeys.hasNext()) {
                        val key = idxKeys.next()
                        idx2hanzi[key.toInt()] = idx2hanziObj.getString(key)
                    }
                    Timber.d("idx2hanzi loaded, size: %d", idx2hanzi.size)
                } else {
                    Timber.e("vocab.json array length < 4")
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to parse vocab.json")
            }
        } else {
            Timber.e("vocab.json not found")
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        Timber.d("Input starting")
    }

    override fun onCreateInputView(): View {
        Timber.d("Creating input view")
        val view = InputView(this)

        window?.window?.decorView?.let { decorView ->
            decorView.setViewTreeLifecycleOwner(this)
            decorView.setViewTreeSavedStateRegistryOwner(this)
        }

        return view
    }

    fun onAction(action: InputMethodAction) {
        when (action) {
            is InputMethodAction.CommitCandidate -> {
                commitCandidate(action.candidate)
            }

            is InputMethodAction.SendComposableKey -> {
                handleValidAlphaKey(action.key)
            }

            is InputMethodAction.DirectlyCommit -> {
                commitText(action.text)
            }

            is InputMethodAction.ToggleAsciiMode -> {
                _state.update { it.copy(isAsciiMode = !it.isAsciiMode) }
            }

            is InputMethodAction.ChangeInputType -> {
                _state.update { it.copy(inputType = action.inputType) }
            }

            is InputMethodAction.Backspace -> {
                if (_state.value.input.isEmpty()) {
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                } else {
                    _state.update {
                        val newInput = it.input.dropLast(1)
                        val newSegmentedTokens = getSegmentedInputTokens(newInput)
                        val newCandidates = smartSearch(newInput)
                        it.copy(
                            input = newInput,
                            segmentedTokens = newSegmentedTokens,
                            candidates = newCandidates
                        )
                    }
                }
            }

            is InputMethodAction.Enter -> {
                handleEnter()
            }

            is InputMethodAction.CapsLock -> {
                _state.update {
                    it.copy(
                        capsLockState = if (it.capsLockState == CapsLockState.DEACTIVATED) CapsLockState.ACTIVATED else CapsLockState.DEACTIVATED
                    )
                }
            }

            is InputMethodAction.Shift -> {
                _state.update {
                    it.copy(
                        capsLockState = if (it.capsLockState == CapsLockState.DEACTIVATED) CapsLockState.SINGLE_LETTER else CapsLockState.DEACTIVATED
                    )
                }
            }

            is InputMethodAction.Space -> {
                if (_state.value.candidates.isEmpty()) {
                    commitText(" ")
                } else {
                    commitCandidate(_state.value.candidates.first())
                }
            }
        }
    }

    private fun commitCandidate(candidate: Candidate) {
        commitText(candidate.text)
        _state.update {
            val newSegmentTokens =
                if (candidate.comment == "Torch") emptyList() else it.segmentedTokens.drop(1)
            if (newSegmentTokens.isEmpty()) {
                it.copy(input = "", candidates = emptyList(), segmentedTokens = newSegmentTokens)
            } else {
                it.copy(
                    input = newSegmentTokens.joinToString(""),
                    candidates = smartSearch(newSegmentTokens[0]),
                    segmentedTokens = newSegmentTokens
                )
            }
        }
    }

    private fun handleValidAlphaKey(code: String) {
        when (_state.value.capsLockState) {
            CapsLockState.ACTIVATED -> {
                commitText(code.uppercase())
                return
            }

            CapsLockState.SINGLE_LETTER -> {
                commitText(code.uppercase())
                _state.update { it.copy(capsLockState = CapsLockState.DEACTIVATED) }
                return
            }

            CapsLockState.DEACTIVATED -> {}
        }

        if (_state.value.isAsciiMode) {
            commitText(code)
            return
        }

        _state.update {
            val newInput = it.input + code
            val newSegmentTokens = getSegmentedInputTokens(newInput)
            val newCandidates = smartSearch(newInput)
            it.copy(
                input = newInput,
                candidates = newCandidates,
                segmentedTokens = newSegmentTokens
            )
        }
    }

    override fun onFinishInput() {
        super.onFinishInput()
        Timber.d("Input finishing")
    }

    override fun onDestroy() {
        engine.close()
        torchModule?.destroy()
        engine.close()
        super.onDestroy()
    }

    private fun commitText(text: String) {
        currentInputConnection.commitText(text, 1)
    }

    private fun search(code: String): List<Candidate> {
        return engine.search(code)
    }

    private fun handleEnter() {
        if (_state.value.input.isNotEmpty()) {
            commitText(_state.value.input)
            _state.update {
                it.copy(
                    input = "",
                    segmentedTokens = emptyList(),
                    candidates = emptyList()
                )
            }
        } else {
            val inputType = currentInputEditorInfo.inputType
            if ((inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0) {
                commitText("\n")
            } else {
                currentInputConnection.performEditorAction(EditorInfo.IME_ACTION_GO)
            }
        }
    }

    private fun getSegmentedInputTokens(input: String): List<String> {
        return engine.segment(input)
    }

    private fun smartSearch(input: String): List<Candidate> {
        Timber.e("Fuck")
        return if (input.length <= 8) {
            engine.search(input)  // 原有逻辑
        } else {
            Timber.e("Fuck torch")
            torchSearch(input)    // 模型推理
        }
    }

    private fun torchSearch(pinyinStr: String): List<Candidate> {
        val module = torchModule
        if (module == null) {
            Timber.e("torchSearch failed: module is null")
            return emptyList()
        }

        Timber.e(pinyinStr)

        // 1. 将拼音字符串转换为索引序列
        val inputIds = pinyinStr.map { pnyn2idx[it.toString()] ?: 1 }.toMutableList()

        // 2. 填充/截断到 maxlen = 50
        val maxlen = 50
        while (inputIds.size < maxlen) inputIds.add(0)
        val inputArray = inputIds.take(maxlen).map { it.toLong() }.toLongArray()

        // 3. 构建 Tensor (形状: 1, maxlen)
        val inputTensor = Tensor.fromBlob(inputArray, longArrayOf(1, maxlen.toLong()))
        val inputEValue = EValue.from(inputTensor)

        // 4. 推理
        val output = module.forward(inputEValue)
        val outputData = output[0].toTensor().dataAsLongArray  // 形状 (1, maxlen)

        // 5. 后处理：提取有效汉字
        val validLen = pinyinStr.length.coerceAtMost(maxlen)
        val chars = mutableListOf<String>()
        for (i in 0 until validLen) {
            val idx = outputData[i].toInt()
            if (idx == 0) break           // 遇到 padding 提前结束（安全处理）
            val char = idx2hanzi[idx] ?: ""
            if (char != "_" && char.isNotEmpty()) {
                chars.add(char)
            }
        }
        val result = chars.joinToString("")

        // 6. 包装为 Candidate 列表（按原有结构）
        return if (result.isNotEmpty()) {
            listOf(
                Candidate(
                    text = result,
                    code = pinyinStr,
                    weight = 100000000u,
                    comment = "Torch"
                )
            ) // 假设 Candidate 有 text 和 code 属性
        } else {
            emptyList()
        }
    }
}