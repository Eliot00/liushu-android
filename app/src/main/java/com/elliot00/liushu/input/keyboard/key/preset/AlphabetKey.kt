/*
 * Copyright (C) 2023 Elliot Xu
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.elliot00.liushu.input.keyboard.key.preset

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.elliot00.liushu.input.keyboard.key.VariantKeyLabel
import timber.log.Timber
import kotlin.math.abs

@Composable
fun RowScope.AlphabetKey(
    label: VariantKeyLabel,
    isAsciiMode: Boolean,
    isCapsLockMode: Boolean,
    onClick: () -> Unit,
    swipeUpText: String,
    onSwipeUp: (() -> Unit)? = null,
) {
    val text = when {
        isCapsLockMode -> label.textInCapsLock
        isAsciiMode -> label.textInAscii
        else -> label.text
    }
    var swipeDirection by remember { mutableStateOf(SwipeDirection.IDLE) }

    Box(
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                shape = MaterialTheme.shapes.small
            )
            .clip(shape = MaterialTheme.shapes.small)
            .weight(1f)
            .fillMaxHeight()
            .pointerInput(Unit) {
                detectTapGestures { onClick() }
            }
            .pointerInput(Unit) {
                detectDragGestures(onDrag = { change, dragAmount ->
                    val (x, y) = dragAmount
                    if (x == 0f && y == 0f) {
                        return@detectDragGestures
                    }

                    change.consume()
                    if (abs(x) > abs(y)) {
                        when {
                            x > 0 -> {
                                swipeDirection = SwipeDirection.RIGHT
                            }

                            x < 0 -> {
                                swipeDirection = SwipeDirection.LEFT
                            }
                        }
                    } else {
                        when {
                            y > 0 -> {
                                swipeDirection = SwipeDirection.DOWN
                            }

                            y < 0 -> {
                                swipeDirection = SwipeDirection.UP
                            }
                        }
                    }
                }, onDragEnd = {
                    when (swipeDirection) {
                        SwipeDirection.UP -> {
                            Timber.d("swipe up")
                            onSwipeUp?.let { it() }
                        }

                        else -> {
                            Timber.d("other")
                        }

                    }
                    swipeDirection = SwipeDirection.IDLE
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = swipeUpText,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.align(Alignment.TopEnd)
        )
        Text(
            text = text,
            fontSize = 26.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center
        )
    }
}

enum class SwipeDirection {
    RIGHT, LEFT, DOWN, UP, IDLE
}