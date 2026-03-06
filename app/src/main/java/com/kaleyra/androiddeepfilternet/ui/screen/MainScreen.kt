@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package com.kaleyra.androiddeepfilternet.ui.screen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AirplanemodeActive
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kaleyra.androiddeepfilternet.ui.viewmodel.MainViewModel
import com.kaleyra.androiddeepfilternet.ui.viewmodel.NoiseFilterUiState
import com.kaleyra.androiddeepfilternet.ui.viewmodel.NoisyAudioSource
import com.kaleyra.androiddeepfilternet.ui.viewmodel.PlaybackState
import com.kaleyra.androiddeepfilternet.ui.viewmodel.UserIntent
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun NoiseFilterDemoScreen(
    viewModel: MainViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    NoiseFilterDemoScreen(
        uiState = uiState,
        onAudioSourceSelected = { source ->
            viewModel.processIntent(UserIntent.PlayAudioSource(source))
        },
        onTogglePlayback = {
            viewModel.processIntent(UserIntent.TogglePlayback)
        },
        onToggleNoiseFilter = { enabled ->
            viewModel.processIntent(UserIntent.ToggleNoiseFilter(enabled))
        },
        onSeekPlayback = { progress ->
            viewModel.processIntent(UserIntent.SeekPlayback(progress))
        },
        onSeekForward = {
            viewModel.processIntent(UserIntent.SeekForward)
        },
        onSeekBackward = {
            viewModel.processIntent(UserIntent.SeekBackward)
        },
        onAttenuationLevel = { level ->
            viewModel.processIntent(UserIntent.AttenuationLevel(level))
        }
    )
}

@Composable
fun NoiseFilterDemoScreen(
    uiState: NoiseFilterUiState,
    onAudioSourceSelected: (NoisyAudioSource) -> Unit,
    onTogglePlayback: () -> Unit,
    onToggleNoiseFilter: (Boolean) -> Unit,
    onSeekPlayback: (Float) -> Unit,
    onSeekForward: () -> Unit,
    onSeekBackward: () -> Unit,
    onAttenuationLevel: (Float) -> Unit
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            AudioSourceSelection(
                isAudioReady = uiState.isAudioReady,
                selectedSource = uiState.selectedAudioSource,
                onSourceSelected = onAudioSourceSelected,
                modifier = Modifier.padding()
            )

            Spacer(Modifier.height(32.dp))

            NoiseFilterSwitch(
                isNoiseFilterEnabled = uiState.isNoiseFilterEnabled,
                onToggleNoiseFilter = onToggleNoiseFilter
            )

            Spacer(Modifier.height(32.dp))

            AttenuationLevel(
                initialAttenuationLevel = uiState.initialAttenuationLevel,
                onAttenuationLevel = onAttenuationLevel
            )

            Spacer(Modifier.height(32.dp))

            AudioPlayer(
                title = when (uiState.selectedAudioSource) {
                    NoisyAudioSource.Airplane -> "Airplane noise"
                    NoisyAudioSource.Crowd -> "Crowd noise"
                    NoisyAudioSource.Restaurant -> "Restaurant noise"
                    NoisyAudioSource.ClientAudio -> "Client audio"
                },
                subtitle = if (uiState.isNoiseFilterEnabled) "Denoised" else "Noisy",
                playbackState = uiState.playbackState,
                isAudioReady = uiState.isAudioReady,
                onTogglePlayback = onTogglePlayback,
                onSeek = onSeekPlayback,
                onSeekForward = onSeekForward,
                onSeekBackward = onSeekBackward,
            )
        }
    }
}

@Composable
fun AttenuationLevel(
    initialAttenuationLevel: Float,
    onAttenuationLevel: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var sliderValue by remember { mutableFloatStateOf(initialAttenuationLevel) }

    Column(modifier) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ){
            Text(
                text = "Attenuation level",
                style = MaterialTheme.typography.titleLargeEmphasized
            )
            Text(
                text = "${sliderValue.roundToInt()}",
                style = MaterialTheme.typography.titleLargeEmphasized
            )
        }

        Spacer(Modifier.height(8.dp))

        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            valueRange = 0f..100f,
            steps = 19,
            onValueChangeFinished = { onAttenuationLevel(sliderValue) }
        )
    }
}

@Composable
fun AudioSourceSelection(
    isAudioReady: Boolean,
    selectedSource: NoisyAudioSource,
    onSourceSelected: (NoisyAudioSource) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            text = "Noisy sources",
            style = MaterialTheme.typography.titleLargeEmphasized
        )

        Spacer(Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            val audioSourcesAlpha by animateFloatAsState(if (isAudioReady) 1f else 0f)
            Box(contentAlignment = Alignment.Center) {
                if (!isAudioReady) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LoadingIndicator()
                        AnimatedLoadingText()
                    }
                }

                Column(Modifier.graphicsLayer { alpha = audioSourcesAlpha }) {
                    AudioSourceItem(
                        isSelected = selectedSource == NoisyAudioSource.Airplane,
                        text = "Airplane noise",
                        icon = Icons.Filled.AirplanemodeActive,
                        onItemClick = {
                            onSourceSelected(NoisyAudioSource.Airplane)
                        }
                    )
                    AudioSourceItem(
                        isSelected = selectedSource == NoisyAudioSource.Crowd,
                        text = "Crowd noise",
                        icon = Icons.Filled.People,
                        onItemClick =  {
                            onSourceSelected(NoisyAudioSource.Crowd)
                        }
                    )
                    AudioSourceItem(
                        isSelected = selectedSource == NoisyAudioSource.Restaurant,
                        text = "Restaurant noise",
                        icon = Icons.Filled.Restaurant,
                        onItemClick =  {
                            onSourceSelected(NoisyAudioSource.Restaurant)
                        }
                    )
                    AudioSourceItem(
                        isSelected = selectedSource == NoisyAudioSource.ClientAudio,
                        text = "Client audio",
                        icon = Icons.Filled.Audiotrack,
                        onItemClick =  {
                            onSourceSelected(NoisyAudioSource.ClientAudio)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun AudioSourceItem(
    isSelected: Boolean,
    text: String,
    icon: ImageVector,
    onItemClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Unspecified,
        onClick = onItemClick,
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(16.dp))
            Text(text, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun NoiseFilterSwitch(
    isNoiseFilterEnabled: Boolean,
    onToggleNoiseFilter: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Text(
            text = "Noise filter",
            style = MaterialTheme.typography.titleLargeEmphasized
        )

        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            NoiseFilterButton(
                text = "On",
                checked = isNoiseFilterEnabled,
                onCheckedChange = { onToggleNoiseFilter(!isNoiseFilterEnabled) },
                shapes = ButtonGroupDefaults.connectedLeadingButtonShapes()
            )
            NoiseFilterButton(
                text = "Off",
                checked = !isNoiseFilterEnabled,
                onCheckedChange = { onToggleNoiseFilter(!isNoiseFilterEnabled) },
                ButtonGroupDefaults.connectedTrailingButtonShapes()
            )
        }
    }
}

@Composable
fun RowScope.NoiseFilterButton(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    shapes: ToggleButtonShapes,
    modifier: Modifier = Modifier
) {
    val weight by animateFloatAsState(if (checked) 1.5f else 1f)
    ToggleButton(
        checked = checked,
        onCheckedChange = onCheckedChange,
        contentPadding = PaddingValues(vertical = 16.dp),
        shapes = shapes,
        modifier = modifier
            .weight(weight)
            .semantics { role = Role.RadioButton },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLargeEmphasized
        )
    }
}

@Composable
fun AudioPlayer(
    title: String,
    subtitle: String,
    isAudioReady: Boolean,
    playbackState: PlaybackState,
    onTogglePlayback: () -> Unit,
    onSeek: (Float) -> Unit,
    onSeekForward: () -> Unit,
    onSeekBackward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(modifier.padding(24.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMediumEmphasized
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(16.dp))

            Slider(
                value = playbackState.progress,
                onValueChange = onSeek,
                enabled = isAudioReady
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledIconButton(
                    onClick = onSeekForward,
                    enabled = isAudioReady,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.FastRewind,
                        contentDescription = "Rewind 10 seconds",
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                val toggleButtonColor = MaterialTheme.colorScheme.primary
                val disabledToggleButtonColor = MaterialTheme.colorScheme.onSurface
                FilledIconToggleButton(
                    checked = !playbackState.isPlaying,
                    onCheckedChange = {
                        onTogglePlayback()
                    },
                    enabled = isAudioReady,
                    shapes = IconButtonDefaults.toggleableShapes(checkedShape = IconButtonDefaults.extraLargeSquareShape),
                    colors = IconButtonDefaults.iconToggleButtonColors(
                        containerColor = toggleButtonColor,
                        contentColor = contentColorFor(toggleButtonColor),
                        disabledContainerColor = disabledToggleButtonColor.copy(alpha = .1f),
                        disabledContentColor = disabledToggleButtonColor.copy(alpha = .38f),
                        checkedContainerColor = toggleButtonColor,
                        checkedContentColor = contentColorFor(toggleButtonColor)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                ) {
                    Icon(
                        imageVector = if (playbackState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                FilledIconButton(
                    onClick = onSeekBackward,
                    enabled = isAudioReady,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.FastForward,
                        contentDescription = "Fast Forward 10 seconds",
                    )
                }
            }
        }
    }
}

@Composable
fun AnimatedLoadingText() {
    val animatedDots = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        while (true) {
            animatedDots.animateTo(
                targetValue = 3f,
                animationSpec = tween(durationMillis = 1000)
            )
            delay(200)
            animatedDots.snapTo(0f)
        }
    }

    Row {
        val style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
        Text(
            text = "Denoising",
            style = style
        )
        Text(
            text = ".".repeat(animatedDots.value.toInt().coerceIn(0, 3)),
            style = style
        )
    }
}

@Preview(showBackground = true)
@Composable
fun NoiseFilterDemoScreenLoadingPreview() {
    MaterialTheme {
        NoiseFilterDemoScreen(
            uiState = NoiseFilterUiState(isAudioReady = false),
            onAudioSourceSelected = {},
            onTogglePlayback = {},
            onToggleNoiseFilter = {},
            onSeekPlayback = {},
            onSeekForward = {},
            onSeekBackward = {},
            onAttenuationLevel = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
fun NoiseFilterDemoScreenReadyPreview() {
    MaterialTheme {
        NoiseFilterDemoScreen(
            uiState = NoiseFilterUiState(
                isAudioReady = true,
                selectedAudioSource = NoisyAudioSource.Crowd,
                playbackState = PlaybackState(
                    isPlaying = true,
                    currentPositionMs = 4,
                    totalDurationMs = 10
                ),
                isNoiseFilterEnabled = true
            ),
            onAudioSourceSelected = {},
            onTogglePlayback = {},
            onToggleNoiseFilter = {},
            onSeekPlayback = {},
            onSeekForward = {},
            onSeekBackward = {},
            onAttenuationLevel = {}
        )
    }
}