package com.lumeter.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumeter.R
import com.lumeter.data.FilmStock
import com.lumeter.data.FilmStocks
import com.lumeter.data.FilmType
import com.lumeter.ui.AppViewModel
import com.lumeter.ui.theme.*

/**
 * Film stock selection screen.
 */
@Composable
fun FilmPage(
    appViewModel: AppViewModel,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ColorBody)
            .padding(horizontal = 20.dp)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 48.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
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
                letterSpacing = 2.sp
            )
            
            Spacer(Modifier.width(80.dp))
        }
        
        // Description
        Text(
            text = stringResource(R.string.film_description),
            fontSize = 13.sp,
            color = ColorDim,
            lineHeight = 20.sp,
            modifier = Modifier.padding(bottom = 24.dp)
        )
        
        // Film list by category
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
                            currentIso = appViewModel.userIso,
                            onSelectFilm = { appViewModel.applyFilmStock(it) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilmTypeSection(
    type: FilmType,
    films: List<FilmStock>,
    currentIso: Int,
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
                isSelected = film.iso == currentIso,
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
                text = stringResource(
                    when (film.reciprocityHint) {
                        "reciprocity_portra" -> R.string.reciprocity_portra
                        "reciprocity_hp5" -> R.string.reciprocity_hp5
                        "reciprocity_velvia" -> R.string.reciprocity_velvia
                        "reciprocity_trix" -> R.string.reciprocity_trix
                        "reciprocity_cinestill" -> R.string.reciprocity_cinestill
                        "reciprocity_delta" -> R.string.reciprocity_delta
                        else -> R.string.reciprocity_portra
                    }
                ),
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
