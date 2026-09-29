package com.example.namufarm.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import com.example.namufarm.ui.components.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh // 🚨 동기화 아이콘 추가
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.namufarm.data.InventoryDisplayRow
import com.example.namufarm.viewmodel.ManagementViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManagementModeScreen(viewModel: ManagementViewModel, onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.message) {
        uiState.message?.let { message ->
            snackbarHostState.showSnackbar(
                message = message,
                withDismissAction = true,
                duration = SnackbarDuration.Short
            )
            viewModel.clearMessage()
        }
    }

    var showConfirmDialog by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showFarmManageDialog by remember { mutableStateOf(false) }
    var showSpeciesManageDialog by remember { mutableStateOf(false) }

    val hasUnsavedChanges =
        uiState.inputQuantity > 0 || uiState.inputWholesale > 0 || uiState.inputRetail > 0

    val handleSelection = { action: () -> Unit ->
        if (hasUnsavedChanges && uiState.isInputActive) {
            pendingAction = action
            showConfirmDialog = true
        } else {
            action()
        }
    }

    BackHandler(enabled = hasUnsavedChanges && uiState.isInputActive) {
        handleSelection { onBack() }
    }

    val canManageInventory = uiState.selectedSpecies != null

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            text = "재고 관리",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Text(
                            text = "농원 · 묘목 · 가격",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.76f)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    FilledTonalIconButton(
                        onClick = {
                            viewModel.syncFarmMasterFromGoogleSheet()
                            viewModel.syncSpeciesMasterFromGoogleSheet()
                        },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.14f),
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "농원/묘목 마스터 동기화"
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    OutlinedButton(
                        onClick = { handleSelection { onBack() } },
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.55f)
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.padding(end = 10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            "판매",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("등록할 재고를 선택하세요", style = MaterialTheme.typography.titleMedium)
                    Text("농원 선택 → 묘목 선택 → 수량·가격 입력", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        listOfNotNull(uiState.selectedFarm?.farmName, uiState.selectedSpecies?.speciesName).joinToString(" · ").ifBlank { "농원과 묘목을 선택해주세요" },
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            SelectionSection(
                title = "농원",
                color = MaterialTheme.colorScheme.primary,
                headerAction = {
                    TextButton(onClick = { showFarmManageDialog = true }) {
                        Text(
                            text = "농원 관리",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            ) {
                if (uiState.farmList.isEmpty()) {
                    ManagementEmptyHint("등록된 농원이 없습니다. 농원 관리에서 추가해주세요.")
                } else {
                    GridSelectionMenu(
                        items = uiState.farmList,
                        columns = 3,
                        itemContent = { farm -> farm.farmName.trim() },
                        isSelected = { farm ->
                            farm.farmId == uiState.selectedFarm?.farmId
                        },
                        onSelect = { farm ->
                            handleSelection { viewModel.selectFarm(farm) }
                        },
                        selectedBorderColor = MaterialTheme.colorScheme.primary,
                        selectedBorderWidth = 2.dp,
                        selectedTextWeight = FontWeight.ExtraBold
                    )
                }
            }

            SelectionSection(
                title = "묘목",
                color = MaterialTheme.colorScheme.primary,
                headerAction = {
                    TextButton(onClick = { showSpeciesManageDialog = true }) {
                        Text(
                            text = "묘목 관리",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            ) {
                ChosungPicker(uiState.selectedChosung, { initial ->
                    handleSelection { viewModel.selectChosung(initial) }
                })

                if (uiState.speciesList.isEmpty()) {
                    ManagementEmptyHint("선택한 초성의 묘목이 없습니다.")
                } else {
                    GridSelectionMenu(
                        items = uiState.speciesList,
                        columns = 3,
                        itemContent = { it.speciesName },
                        isSelected = {
                            it.speciesId == uiState.selectedSpecies?.speciesId
                        },
                        onSelect = { species ->
                            handleSelection { viewModel.selectSpecies(species) }
                        }
                    )
                }
            }

            if (canManageInventory) {
                if (uiState.inventoryList.isNotEmpty()) {
                    InventoryListSection(viewModel = viewModel)
                } else if (uiState.selectedFarm != null) {
                    ManagementEmptyHint(
                        "${uiState.selectedSpecies?.speciesName.orEmpty()} 재고가 아직 없습니다."
                    )
                }

                Button(
                    onClick = { handleSelection { viewModel.activateNewInput() } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(13.dp)
                ) {
                    Text(
                        text = "새 재고 등록",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            when {
                uiState.isInputActive -> {
                    InventoryInputPane(viewModel = viewModel)
                }

                canManageInventory -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant
                        )
                    ) {
                        Text(
                            text = "기존 재고를 눌러 수정하거나 ‘새 재고 등록’을 선택하세요.",
                            modifier = Modifier.padding(18.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                else -> {
                    ManagementEmptyHint("묘목을 선택하면 재고를 등록하거나 수정할 수 있습니다.")
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
        }
    }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text("입력 내용을 변경할까요?") },
            text = { Text("저장하지 않은 수량/가격 입력값이 초기화됩니다.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.resetInput()
                        pendingAction?.invoke()
                        showConfirmDialog = false
                    }
                ) {
                    Text("변경")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text("계속 입력")
                }
            }
        )
    }

    if (showFarmManageDialog) {
        FarmManageDialog(
            selectedFarm = uiState.selectedFarm,
            onDismiss = { showFarmManageDialog = false },
            onSave = { farmId, name, address, phone, memo ->
                viewModel.saveFarm(
                    farmId = farmId,
                    farmName = name,
                    address = address,
                    phone = phone,
                    memo = memo
                )
                showFarmManageDialog = false
            },
            onDelete = { farmId ->
                viewModel.deleteFarm(farmId)
                showFarmManageDialog = false
            }
        )
    }

    if (showSpeciesManageDialog) {
        SpeciesManageDialog(
            selectedSpecies = uiState.selectedSpecies,
            onDismiss = { showSpeciesManageDialog = false },
            onSave = { id, name ->
                val chosung = getChosungFromText(name)
                viewModel.saveSpecies(id, name, "", chosung, "", "", 1)
                showSpeciesManageDialog = false
            },
            onDelete = { id ->
                viewModel.deleteSpecies(id)
                showSpeciesManageDialog = false
            }
        )
    }
}

@Composable
private fun ManagementEmptyHint(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}


@Composable
fun FarmManageDialog(
    selectedFarm: com.example.namufarm.data.FarmEntity?,
    onDismiss: () -> Unit,
    onSave: (Int?, String, String, String, String) -> Unit,
    onDelete: (Int) -> Unit
) {
    var editingFarmId by remember(selectedFarm) { mutableStateOf(selectedFarm?.farmId) }
    var farmName by remember(selectedFarm) { mutableStateOf(selectedFarm?.farmName.orEmpty()) }
    var farmAddress by remember(selectedFarm) { mutableStateOf(selectedFarm?.address.orEmpty()) }
    var farmPhone by remember(selectedFarm) { mutableStateOf(selectedFarm?.phone.orEmpty()) }
    var farmMemo by remember(selectedFarm) { mutableStateOf(selectedFarm?.memo.orEmpty()) }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showSaveConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm && editingFarmId != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("농원을 삭제할까요?", fontWeight = FontWeight.Bold) },
            text = {
                Text("이 농원과 연결된 재고도 함께 삭제됩니다. 주문 기록은 유지됩니다.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete(editingFarmId!!)
                        showDeleteConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("삭제")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("취소")
                }
            }
        )
    }

    if (showSaveConfirm) {
        AlertDialog(
            onDismissRequest = { showSaveConfirm = false },
            title = { Text("농원 저장", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("입력한 정보로 저장할까요?")
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = farmName,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (farmAddress.isNotBlank()) Text(farmAddress)
                    if (farmPhone.isNotBlank()) Text(farmPhone)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSave(
                            editingFarmId,
                            farmName.trim(),
                            farmAddress.trim(),
                            farmPhone.trim(),
                            farmMemo.trim()
                        )
                        showSaveConfirm = false
                    }
                ) {
                    Text("저장")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveConfirm = false }) {
                    Text("취소")
                }
            }
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 3.dp,
            shadowElevation = 14.dp
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (editingFarmId == null) "새 농원" else "농원 정보",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "판매와 재고에 사용할 기본 정보를 관리합니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (editingFarmId != null) {
                        TextButton(
                            onClick = {
                                editingFarmId = null
                                farmName = ""
                                farmAddress = ""
                                farmPhone = ""
                                farmMemo = ""
                            }
                        ) {
                            Text("새로 등록", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                OutlinedTextField(
                    value = farmName,
                    onValueChange = { farmName = it },
                    label = { Text("농원명") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                OutlinedTextField(
                    value = farmAddress,
                    onValueChange = { farmAddress = it },
                    label = { Text("주소") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                OutlinedTextField(
                    value = farmPhone,
                    onValueChange = { farmPhone = it },
                    label = { Text("전화번호") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                OutlinedTextField(
                    value = farmMemo,
                    onValueChange = { farmMemo = it },
                    label = { Text("메모") },
                    minLines = 3,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (editingFarmId != null) {
                        OutlinedButton(
                            onClick = { showDeleteConfirm = true },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.error.copy(alpha = 0.45f)
                            )
                        ) {
                            Text("삭제", fontWeight = FontWeight.Bold)
                        }
                    }

                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("닫기")
                    }

                    Button(
                        onClick = {
                            if (farmName.isNotBlank()) showSaveConfirm = true
                        },
                        enabled = farmName.isNotBlank(),
                        modifier = Modifier.weight(1.2f).height(48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("저장", fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }
    }
}

@Composable
fun InventoryListSection(viewModel: ManagementViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    SelectionSection(
        title = "재고 현황",
        color = MaterialTheme.colorScheme.primary
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            uiState.inventoryList.forEach { row ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            viewModel.loadExistingRowToInput(
                                row,
                                uiState.allSpeciesList
                            )
                        },
                    shape = RoundedCornerShape(13.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            val spec = listOf(
                                row.age,
                                row.size,
                                row.plantType,
                                row.quality
                            ).filter { it.isNotBlank() }.joinToString(" · ")

                            Text(
                                text = spec.ifBlank { "규격 미지정" },
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "도매 ${String.format("%,d", row.wholesalePrice)}원 · 소매 ${String.format("%,d", row.retailPrice)}원",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = "${String.format("%,d", row.quantity)}주",
                                modifier = Modifier.padding(
                                    horizontal = 10.dp,
                                    vertical = 6.dp
                                ),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InventoryInputPane(viewModel: ManagementViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    var showValidationErrorDialog by remember { mutableStateOf(false) }
    var showSaveConfirmDialog by remember { mutableStateOf(false) }
    var showFarmRequiredError by remember { mutableStateOf(false) }

    val qualityOptions = listOf("", "특묘", "상묘", "중묘", "하묘")
    val plantTypes = listOf("", "실생", "접목", "삽목", "근삽")
    val currentAgeInt = uiState.inputAge
        .replace(Regex("[^0-9]"), "")
        .toIntOrNull() ?: 0

    if (showFarmRequiredError) {
        AlertDialog(
            onDismissRequest = { showFarmRequiredError = false },
            title = { Text("농원을 선택해주세요", fontWeight = FontWeight.Bold) },
            text = { Text("재고를 저장하려면 먼저 대상 농원을 선택해야 합니다.") },
            confirmButton = {
                Button(onClick = { showFarmRequiredError = false }) {
                    Text("확인")
                }
            }
        )
    }

    if (showValidationErrorDialog) {
        AlertDialog(
            onDismissRequest = { showValidationErrorDialog = false },
            title = { Text("가격을 확인해주세요", fontWeight = FontWeight.Bold) },
            text = {
                Text("수량이 있는 재고는 도매가와 소매가를 모두 입력해야 합니다.")
            },
            confirmButton = {
                Button(onClick = { showValidationErrorDialog = false }) {
                    Text("확인")
                }
            }
        )
    }

    if (showSaveConfirmDialog) {
        val isDelete = uiState.inputQuantity == 0

        AlertDialog(
            onDismissRequest = { showSaveConfirmDialog = false },
            title = {
                Text(
                    if (isDelete) "재고를 삭제할까요?" else "재고를 저장할까요?",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "${uiState.selectedFarm?.farmName.orEmpty()} · ${uiState.selectedSpecies?.speciesName.orEmpty()}",
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    val spec = listOf(
                        uiState.inputAge,
                        String.format("%.1fm", uiState.inputSizeMeters),
                        uiState.inputPlantType,
                        uiState.inputQuality
                    ).filter { it.isNotBlank() }.joinToString(" · ")

                    Text(
                        text = spec.ifBlank { "규격 미지정" },
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    Text(
                        text = "수량  ${String.format("%,d", uiState.inputQuantity)}주",
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "도매  ${String.format("%,d", uiState.inputWholesale)}원"
                    )
                    Text(
                        text = "소매  ${String.format("%,d", uiState.inputRetail)}원"
                    )

                    if (isDelete) {
                        Text(
                            text = "수량 0은 해당 규격 재고를 삭제합니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.saveInventory()
                        showSaveConfirmDialog = false
                    },
                    colors = if (isDelete) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    } else {
                        ButtonDefaults.buttonColors()
                    }
                ) {
                    Text(if (isDelete) "삭제" else "저장")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveConfirmDialog = false }) {
                    Text("취소")
                }
            }
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SelectionSection(
            title = "연생",
            color = MaterialTheme.colorScheme.primary
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.width(74.dp).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(11.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = if (currentAgeInt > 0) "${currentAgeInt}년생" else "미지정",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                OutlinedButton(
                    onClick = { viewModel.updateAge("") },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(11.dp)
                ) {
                    Text("미지정")
                }

                FilledTonalButton(
                    onClick = {
                        viewModel.updateAge("${currentAgeInt + 1}년생")
                    },
                    modifier = Modifier.weight(1.25f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(11.dp)
                ) {
                    Text("+1년", fontWeight = FontWeight.Bold)
                }
            }
        }

        SelectionSection(
            title = "크기",
            color = MaterialTheme.colorScheme.primary
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.width(74.dp).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(11.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = if (uiState.inputSizeMeters > 0f) {
                                String.format("%.1fm", uiState.inputSizeMeters)
                            } else {
                                "미지정"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                OutlinedButton(
                    onClick = { viewModel.updateSize(0f) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(11.dp)
                ) {
                    Text("초기화")
                }

                FilledTonalButton(
                    onClick = {
                        viewModel.updateSize(uiState.inputSizeMeters + 0.1f)
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(11.dp)
                ) {
                    Text("+10cm", fontWeight = FontWeight.Bold)
                }

                FilledTonalButton(
                    onClick = {
                        viewModel.updateSize(uiState.inputSizeMeters + 1f)
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(11.dp)
                ) {
                    Text("+1m", fontWeight = FontWeight.Bold)
                }
            }
        }

        SelectionSection(
            title = "재배 타입",
            color = MaterialTheme.colorScheme.primary
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                plantTypes.forEach { type ->
                    SelectionButton(
                        text = if (type.isBlank()) "미지정" else type,
                        isSelected = uiState.inputPlantType == type,
                        modifier = Modifier.weight(1f)
                    ) {
                        viewModel.updatePlantType(type)
                    }
                }
            }
        }

        SelectionSection(
            title = "품질",
            color = MaterialTheme.colorScheme.primary
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                qualityOptions.forEach { quality ->
                    SelectionButton(
                        text = if (quality.isBlank()) "미지정" else quality,
                        isSelected = uiState.inputQuality == quality,
                        modifier = Modifier.weight(1f)
                    ) {
                        viewModel.updateQuality(quality)
                    }
                }
            }
        }

        SelectionSection(
            title = "수량 · 가격",
            color = MaterialTheme.colorScheme.primary
        ) {
            ValueAdjuster(
                label = "수량(주)",
                value = uiState.inputQuantity,
                steps = listOf(1, 10, 100),
                resetLabel = "품절",
                onAdd = { viewModel.addQuantity(it) },
                onReset = { viewModel.resetQuantityToSoldOut() },
                onSet = viewModel::setQuantity
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            ValueAdjuster(
                label = "도매가(원)",
                value = uiState.inputWholesale,
                steps = listOf(100, 1000, 10000),
                onAdd = { viewModel.addWholesale(it) },
                onReset = { viewModel.resetWholesale() },
                onSet = viewModel::setWholesale
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            ValueAdjuster(
                label = "소매가(원)",
                value = uiState.inputRetail,
                steps = listOf(100, 1000, 10000),
                onAdd = { viewModel.addRetail(it) },
                onReset = { viewModel.resetRetail() },
                onSet = viewModel::setRetail
            )
        }

        val deleting = uiState.inputQuantity == 0

        Button(
            onClick = {
                when {
                    uiState.selectedFarm == null -> {
                        showFarmRequiredError = true
                    }
                    uiState.inputQuantity > 0 &&
                        (uiState.inputWholesale == 0 || uiState.inputRetail == 0) -> {
                        showValidationErrorDialog = true
                    }
                    else -> {
                        showSaveConfirmDialog = true
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(14.dp),
            colors = if (deleting) {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            } else {
                ButtonDefaults.buttonColors()
            }
        ) {
            Text(
                text = if (deleting) "이 규격 재고 삭제" else "재고 저장",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}

@Composable
fun <T> GridSelectionMenu(
    items: List<T>,
    columns: Int,
    itemContent: (T) -> String,
    isSelected: (T) -> Boolean,
    onSelect: (T) -> Unit,
    selectedBorderColor: Color = Color(0xFF245C49),
    selectedBorderWidth: Dp = 1.dp,
    selectedTextWeight: FontWeight = FontWeight.Bold
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        items.chunked(columns).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                rowItems.forEach { item ->
                    val selected = isSelected(item)

                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .semantics { this.selected = selected }
                            .clickable { onSelect(item) },
                        shape = RoundedCornerShape(11.dp),
                        color = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        border = BorderStroke(
                            width = if (selected) selectedBorderWidth else 1.dp,
                            color = if (selected) {
                                selectedBorderColor
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            }
                        )
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = itemContent(item),
                                style = MaterialTheme.typography.labelMedium,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                fontWeight = if (selected) {
                                    selectedTextWeight
                                } else {
                                    FontWeight.Medium
                                }
                            )
                        }
                    }
                }

                repeat(columns - rowItems.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun SelectionSection(
    title: String,
    color: Color,
    headerAction: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(20.dp)
                        .background(color, RoundedCornerShape(2.dp))
                )
                Spacer(modifier = Modifier.width(9.dp))
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.ExtraBold,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                headerAction?.invoke()
            }
            content()
        }
    }
}

@Composable
fun SelectionButton(
    text: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .heightIn(min = 48.dp)
            .semantics { selected = isSelected }
            .clickable { onClick() },
        shape = RoundedCornerShape(11.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        border = BorderStroke(
            1.dp,
            if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
        )
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@Composable
fun ValueAdjuster(
    label: String,
    value: Int,
    steps: List<Int>,
    resetLabel: String = "초기화",
    onAdd: (Int) -> Unit,
    onReset: () -> Unit,
    onSet: ((Int) -> Unit)? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        if (onSet != null) {
            OutlinedTextField(
                value = if (value == 0) "" else value.toString(),
                onValueChange = { text ->
                    if (text.isEmpty()) onSet(0)
                    else text.toIntOrNull()?.takeIf { it >= 0 }?.let(onSet)
                },
                label = { Text(label) },
                placeholder = { Text("0") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                shape = MaterialTheme.shapes.medium
            )
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                Text(String.format("%,d", value), style = MaterialTheme.typography.titleLarge)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            OutlinedButton(
                onClick = onReset,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                shape = RoundedCornerShape(11.dp),
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (resetLabel == "품절") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    text = resetLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            steps.forEach { step ->
                FilledTonalButton(
                    onClick = { onAdd(step) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(11.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        text = "+$step",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun SpeciesManageDialog(
    selectedSpecies: com.example.namufarm.data.SpeciesEntity?,
    onDismiss: () -> Unit,
    onSave: (Int?, String) -> Unit,
    onDelete: (Int) -> Unit
) {
    var editingSpeciesId by remember(selectedSpecies) {
        mutableStateOf(selectedSpecies?.speciesId)
    }
    var speciesName by remember(selectedSpecies) {
        mutableStateOf(selectedSpecies?.speciesName.orEmpty())
    }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm && editingSpeciesId != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("묘목을 목록에서 숨길까요?", fontWeight = FontWeight.Bold) },
            text = { Text("묘목은 비활성 처리되며 기존 주문 기록은 유지됩니다.") },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete(editingSpeciesId!!)
                        showDeleteConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("비활성")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("취소")
                }
            }
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 3.dp,
            shadowElevation = 14.dp
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (editingSpeciesId == null) "새 묘목" else "묘목 정보",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "묘목명으로 초성이 자동 분류됩니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (editingSpeciesId != null) {
                        TextButton(
                            onClick = {
                                editingSpeciesId = null
                                speciesName = ""
                            }
                        ) {
                            Text("새로 등록", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                OutlinedTextField(
                    value = speciesName,
                    onValueChange = { speciesName = it },
                    label = { Text("묘목명") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (editingSpeciesId != null) {
                        OutlinedButton(
                            onClick = { showDeleteConfirm = true },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.error.copy(alpha = 0.45f)
                            )
                        ) {
                            Text("비활성", fontWeight = FontWeight.Bold)
                        }
                    }

                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("닫기")
                    }

                    Button(
                        onClick = {
                            if (speciesName.isNotBlank()) {
                                onSave(editingSpeciesId, speciesName.trim())
                            }
                        },
                        enabled = speciesName.isNotBlank(),
                        modifier = Modifier.weight(1.2f).height(48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("저장", fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }
    }
}

// 초성 자동 추출 도우미 함수
fun getChosungFromText(text: String): String {
    val chosungArray = arrayOf("ㄱ", "ㄲ", "ㄴ", "ㄷ", "ㄸ", "ㄹ", "ㅁ", "ㅂ", "ㅃ", "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅉ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ")
    val firstChar = text.firstOrNull() ?: return "ㄱ"
    val code = firstChar.code
    if (code in 0xAC00..0xD7A3) {
        val index = (code - 0xAC00) / (21 * 28)
        val c = chosungArray.getOrNull(index) ?: "ㄱ"
        return when(c) { "ㄲ"->"ㄱ"; "ㄸ"->"ㄷ"; "ㅃ"->"ㅂ"; "ㅆ"->"ㅅ"; "ㅉ"->"ㅈ"; else -> c }
    }
    return firstChar.toString()
}