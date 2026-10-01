package com.lumeter.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumeter.R
import com.lumeter.data.FilmRoll
import com.lumeter.data.FilmStock
import com.lumeter.data.FilmStocks
import com.lumeter.data.FilmType
import com.lumeter.ui.AppViewModel
import com.lumeter.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Film stock screen: the stock library (pick to set ISO + reciprocity model) and the
 * user's rolls (load a stock, track frames, archive when developed).
 */
@Composable
fun FilmPage(
    appViewModel: AppViewModel,
    onBack: () -> Unit,
) {
    var tab by remember { mutableStateOf(0) }
    var showNewRoll by remember { mutableStateOf(false) }
    if (showNewRoll) {
        NewRollDialog(
            onCreate = { stock, frames, notes ->
                appViewModel.createRoll(stock.name, stock.iso, frames, notes)
                showNewRoll = false
                tab = 1
            },
            onDismiss = { showNewRoll = false },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ColorBody)
            .padding(horizontal = 20.dp)
    ) {
        // Header: back pinned left, title centered on the screen.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 48.dp, bottom = 20.dp)
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .clip(RoundedCornerShape(6.dp))
                    .background(ColorPanel)
                    .clickable { onBack() }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "← ${stringResource(R.string.back)}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = ColorDim,
                    letterSpacing = 0.5.sp
                )
            }

            Text(
                text = stringResource(R.string.film).uppercase(),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = ColorInk,
                letterSpacing = 2.sp,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Tabs
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TabChip(
                label = stringResource(R.string.tab_library),
                selected = tab == 0,
                modifier = Modifier.weight(1f),
            ) { tab = 0 }
            TabChip(
                label = stringResource(R.string.tab_rolls),
                selected = tab == 1,
                modifier = Modifier.weight(1f),
            ) { tab = 1 }
        }

        if (tab == 0) {
            Text(
                text = stringResource(R.string.film_description),
                fontSize = 13.sp,
                color = ColorDim,
                lineHeight = 20.sp,
                modifier = Modifier.padding(top = 16.dp, bottom = 16.dp)
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                FilmType.values().forEach { type ->
                    val films = FilmStocks.ALL.filter { it.type == type }
                    if (films.isNotEmpty()) {
                        item {
                            FilmTypeSection(
                                type = type,
                                films = films,
                                currentFilmStock = appViewModel.currentFilmStock,
                                onSelectFilm = { appViewModel.applyFilmStock(it) }
                            )
                        }
                    }
                }
            }
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(ColorAccent)
                        .clickable { showNewRoll = true }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                ) {
                    Text(
                        text = "＋ ${stringResource(R.string.new_roll)}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = ColorBody,
                    )
                }
            }

            if (appViewModel.rolls.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.no_rolls),
                        fontSize = 14.sp,
                        color = ColorDim,
                        lineHeight = 20.sp,
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    items(appViewModel.rolls, key = { it.id }) { roll ->
                        RollCard(
                            roll = roll,
                            isCurrent = appViewModel.currentRollId == roll.id && !roll.isArchived,
                            onSetCurrent = { appViewModel.setCurrentRoll(roll.id) },
                            onArchive = { appViewModel.archiveRoll(roll.id, !roll.isArchived) },
                            onDelete = { appViewModel.deleteRoll(roll.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TabChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) ColorAccent else ColorPanel)
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) ColorBody else ColorInk,
        )
    }
}

@Composable
private fun RollCard(
    roll: FilmRoll,
    isCurrent: Boolean,
    onSetCurrent: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = ColorPanel,
            title = { Text(stringResource(R.string.delete), color = ColorInk) },
            text = { Text(stringResource(R.string.delete_roll_confirm), color = ColorDim) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text(stringResource(R.string.delete), color = ColorDanger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    val dateText = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(roll.loadedAt))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isCurrent) ColorAccent.copy(alpha = 0.15f) else ColorPanel)
            .clickable(enabled = !roll.isArchived) { onSetCurrent() }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = roll.stockName,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isCurrent) ColorAccent else ColorInk,
                )
                Text(
                    text = "ISO ${roll.iso} · ${stringResource(R.string.roll_loaded)} $dateText" +
                        if (roll.notes.isNotBlank()) " · ${roll.notes}" else "",
                    fontSize = 11.sp,
                    color = ColorDim,
                )
            }
            if (isCurrent) {
                Text(
                    text = stringResource(R.string.current_chip),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = ColorBody,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(ColorAccent)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            } else if (roll.isArchived) {
                Text(
                    text = stringResource(R.string.archived_chip),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = ColorDim,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(ColorPanel2)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }

        // Frame counter: thin progress bar + numeric readout.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(ColorPanel2),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(
                            if (roll.frameCount > 0) {
                                roll.framesShot.toFloat() / roll.frameCount
                            } else {
                                0f
                            },
                        )
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (roll.framesLeft > 0) ColorAccent else ColorDanger),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = "${roll.framesShot}/${roll.frameCount}",
                fontSize = 12.sp,
                fontFamily = BarlowCondensed,
                fontWeight = FontWeight.SemiBold,
                color = if (roll.framesLeft > 0) ColorInk else ColorDanger,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RollAction(stringResource(R.string.set_current), enabled = !roll.isArchived, onClick = onSetCurrent)
            RollAction(
                if (roll.isArchived) stringResource(R.string.restore) else stringResource(R.string.archive),
                enabled = true,
                onClick = onArchive,
            )
            RollAction(stringResource(R.string.delete), enabled = true, color = ColorDanger, onClick = onDelete)
        }
    }
}

@Composable
private fun RollAction(
    label: String,
    enabled: Boolean,
    color: Color = ColorInk,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = if (enabled) color else color.copy(alpha = 0.4f),
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(ColorPanel2)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun NewRollDialog(
    onCreate: (FilmStock, Int, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var stock by remember { mutableStateOf(FilmStocks.ALL.first()) }
    var frames by remember { mutableStateOf(36) }
    var notes by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ColorPanel,
        title = { Text(stringResource(R.string.new_roll), color = ColorInk) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilmStocks.ALL.forEach { s ->
                        val on = s.name == stock.name
                        Text(
                            s.name,
                            fontSize = 11.sp,
                            color = if (on) ColorBody else ColorInk,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (on) ColorAccent else ColorPanel2)
                                .clickable { stock = s }
                                .padding(horizontal = 9.dp, vertical = 6.dp),
                        )
                    }
                }
                Text(
                    "ISO ${stock.iso}",
                    fontSize = 12.sp,
                    color = ColorDim,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.frames_count),
                        fontSize = 12.sp,
                        color = ColorDim,
                    )
                    listOf(12, 24, 36, 72).forEach { count ->
                        val on = count == frames
                        Text(
                            "$count",
                            fontSize = 12.sp,
                            color = if (on) ColorBody else ColorInk,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (on) ColorAccent else ColorPanel2)
                                .clickable { frames = count }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    singleLine = true,
                    placeholder = {
                        Text(
                            stringResource(R.string.notes),
                            fontSize = 13.sp,
                            color = ColorDim,
                        )
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = ColorPanel2,
                        unfocusedContainerColor = ColorPanel2,
                        focusedTextColor = ColorInk,
                        unfocusedTextColor = ColorInk,
                        focusedIndicatorColor = ColorAccent,
                        unfocusedIndicatorColor = ColorLine,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(stock, frames, notes.trim()) }) {
                Text(stringResource(R.string.create), color = ColorAccent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun FilmTypeSection(
    type: FilmType,
    films: List<FilmStock>,
    currentFilmStock: String?,
    onSelectFilm: (FilmStock) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = when (type) {
                FilmType.COLOUR_NEGATIVE -> stringResource(R.string.colour_negative)
                FilmType.BW_NEGATIVE -> stringResource(R.string.bw_negative)
                FilmType.COLOUR_SLIDE -> stringResource(R.string.colour_slide)
                FilmType.TUNGSTEN_NEGATIVE -> stringResource(R.string.tungsten_negative)
            },
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = ColorDim,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
        )

        films.forEach { film ->
            FilmStockCard(
                film = film,
                isSelected = film.name == currentFilmStock,
                onClick = { onSelectFilm(film) }
            )
        }
    }
}

@Composable
private fun FilmStockCard(
    film: FilmStock,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isSelected) ColorAccent.copy(alpha = 0.15f)
                else ColorPanel
            )
            .clickable { onClick() }
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = film.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isSelected) ColorAccent else ColorInk
            )

            Text(
                text = reciprocityText(film.reciprocityHint),
                fontSize = 11.sp,
                color = ColorDim
            )
        }

        Text(
            text = "ISO ${film.iso}",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (isSelected) ColorAccent else ColorInk
        )
    }
}


@Composable
private fun reciprocityText(hint: String): String {
    val id = when (hint) {
        "reciprocity_portra" -> R.string.reciprocity_portra
        "reciprocity_hp5" -> R.string.reciprocity_hp5
        "reciprocity_velvia" -> R.string.reciprocity_velvia
        "reciprocity_trix" -> R.string.reciprocity_trix
        "reciprocity_cinestill" -> R.string.reciprocity_cinestill
        "reciprocity_delta" -> R.string.reciprocity_delta
        else -> 0
    }
    return if (id != 0) stringResource(id) else hint
}
