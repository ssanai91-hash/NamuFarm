package com.example.namufarm.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import com.example.namufarm.data.InventoryDisplayRow
import com.example.namufarm.quotemode.components.*
import com.example.namufarm.ui.farm.*
import com.example.namufarm.ui.theme.*
import com.example.namufarm.viewmodel.ManagementViewModel
import com.google.gson.Gson

/** Farm navigation owns its filters; it never changes the selling tab's initial filter. */
@Composable
fun ManagementModeScreen(viewModel: ManagementViewModel, onBack: () -> Unit,
    onBottomBarVisibleChange: (Boolean) -> Unit = {}, isActive: Boolean = true) {
    val state by viewModel.uiState.collectAsState()
    val inventoryReview by viewModel.inventoryReview.collectAsState()
    var showInventoryReview by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    var feedbackError by remember { mutableStateOf(false) }
    LaunchedEffect(state.message, isActive) {
        if (isActive) state.feedback?.let {
            feedbackError = it.isError
            snackbar.showSnackbar(it.text, actionLabel = "닫기")
            viewModel.clearMessage()
        }
    }
    var view by rememberSaveable { mutableIntStateOf(0) }
    var page by rememberSaveable { mutableStateOf("list") }
    var search by rememberSaveable { mutableStateOf("") }
    var initial by rememberSaveable { mutableStateOf("전체") }
    var originalRowJson by rememberSaveable { mutableStateOf<String?>(null) }
    var baseline by rememberSaveable { mutableStateOf("") }
    var showLeaveConfirm by remember { mutableStateOf(false) }
    val originalRow = remember(originalRowJson) {
        originalRowJson?.let { Gson().fromJson(it, InventoryDisplayRow::class.java) }
    }
    fun leaveInput() {
        viewModel.resetInput()
        state.selectedSpecies?.let(viewModel::selectSpecies)
        page = "spec"
    }
    fun back() {
        when (page) {
            "input" -> if (stockInputKey(state) != baseline) showLeaveConfirm = true else leaveInput()
            "spec" -> page = "list"
            else -> onBack()
        }
    }
    BackHandler(enabled = isActive && page != "list") { back() }
    DisposableEffect(page, isActive) {
        if (isActive) onBottomBarVisibleChange(page != "input")
        onDispose { if (isActive) onBottomBarVisibleChange(true) }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (inventoryReview.differences.isNotEmpty()) {
                FarmInfo(if (inventoryReview.firstLink) "휴대폰과 서버의 재고를 먼저 맞춰주세요." else "다른 기기(PC)와 재고 수정이 겹쳤어요.", warning = !inventoryReview.firstLink)
                TextButton(onClick = { showInventoryReview = true }) { Text("확인하기") }
            }
            when (page) {
                "input" -> StockEditor(viewModel, originalRow, onBack = ::back, onSaved = ::leaveInput)
                "spec" -> StockSpecificationList(state, onBack = ::back, onEdit = { row ->
                    originalRowJson = Gson().toJson(row)
                    viewModel.loadExistingRowToInput(row, state.allSpeciesList)
                    baseline = stockInputKey(viewModel.uiState.value)
                    page = "input"
                }, onNew = {
                    originalRowJson = null
                    viewModel.activateNewInput()
                    baseline = stockInputKey(viewModel.uiState.value)
                    page = "input"
                })
                else -> {
                    NamuTopBar("농원·묘목", masterSyncLabel(state.lastMasterSyncAt), onSync = {
                        viewModel.syncFarmMasterFromGoogleSheet()
                        viewModel.syncSpeciesMasterFromGoogleSheet()
                    })
                    FarmTopArea(state, view, onView = { view = it }) {
                        if (view != 2) FarmPicker(state, viewModel::selectFarmForStock)
                        if (view != 1) {
                            FarmSearch(search, onChange = { search = it })
                            NamuChosungRow(initial, state.allSpeciesList.size, onSelect = { initial = it }, available = { ch ->
                                state.allSpeciesList.any { namuMatchesInitial(it.speciesName, it.chosung, ch) }
                            })
                        }
                    }
                    when (view) {
                        0 -> StockSpeciesList(state, initial, search) { species ->
                            viewModel.selectSpecies(species); page = "spec"
                        }
                        1 -> FarmList(viewModel)
                        2 -> SpeciesList(viewModel, initial, search)
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter)) { data ->
            Snackbar(data, containerColor = if (feedbackError) NamuColors.danger else NamuColors.dark)
        }
    }
    if (showInventoryReview && inventoryReview.differences.isNotEmpty()) InventoryReviewSheet(inventoryReview, state,
        onClose = { showInventoryReview = false }, onResolve = viewModel::resolveInventoryReview)
    if (showLeaveConfirm) AlertDialog(onDismissRequest = { showLeaveConfirm = false },
        title = { Text("입력 취소") }, text = { Text("입력 중인 내용이 사라집니다. 나갈까요?") },
        confirmButton = { TextButton(onClick = { showLeaveConfirm = false; leaveInput() }) { Text("나가기") } },
        dismissButton = { TextButton(onClick = { showLeaveConfirm = false }) { Text("계속 입력") } })
}

// Shared by the legacy name and the farm tab's search/initial display.
fun getChosungFromText(text: String): String = namuInitials(text).firstOrNull()?.toString() ?: "ㄱ"
