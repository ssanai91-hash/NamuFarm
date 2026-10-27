package com.example.namufarm.ui.farm

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.example.namufarm.data.InventoryDisplayRow
import com.example.namufarm.data.SpeciesEntity
import com.example.namufarm.viewmodel.ManagementUiState
import com.example.namufarm.viewmodel.ManagementViewModel

@Composable
internal fun StockSpeciesList(state: ManagementUiState, initial: String, search: String, onSelect: (SpeciesEntity) -> Unit) { Text("재고") }
@Composable
internal fun StockSpecificationList(state: ManagementUiState, onBack: () -> Unit, onEdit: (InventoryDisplayRow) -> Unit, onNew: () -> Unit) { Text("규격 재고") }
@Composable
internal fun StockEditor(viewModel: ManagementViewModel, original: InventoryDisplayRow?, onBack: () -> Unit, onSaved: () -> Unit) { Text("재고 입력") }
@Composable
internal fun FarmList(viewModel: ManagementViewModel) { Text("농원") }
@Composable
internal fun SpeciesList(viewModel: ManagementViewModel, initial: String, search: String) { Text("묘목") }
