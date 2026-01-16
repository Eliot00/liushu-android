package com.elliot00.liushu.input

import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.lifecycle.ViewModel
import com.elliot00.liushu.input.data.CapsLockState
import com.elliot00.liushu.input.data.InputMethodAction
import com.elliot00.liushu.input.data.InputViewState
import com.elliot00.liushu.uniffi.Candidate
import com.elliot00.liushu.uniffi.Engine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class InputViewModel : ViewModel() {
    lateinit var engine: Engine

    private val _state = MutableStateFlow(InputViewState())
    val state = _state.asStateFlow()

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
                        val newCandidates = search(newInput)
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
            val newSegmentTokens = it.segmentedTokens.drop(1)
            if (newSegmentTokens.isEmpty()) {
                it.copy(input = "", candidates = emptyList(), segmentedTokens = newSegmentTokens)
            } else {
                it.copy(
                    input = newSegmentTokens.joinToString(""),
                    candidates = search(newSegmentTokens[0]),
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
            val newCandidates = search(newSegmentTokens[0])
            it.copy(
                input = newInput,
                candidates = newCandidates,
                segmentedTokens = newSegmentTokens
            )
        }
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
}