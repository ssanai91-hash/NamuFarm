package com.example.namufarm.ui

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
                        onClick = onBack,
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
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
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
                        columns = 4,
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
                val chosungRows = listOf(
                    listOf("ㄱ", "ㄴ", "ㄷ", "ㄹ", "ㅁ", "ㅂ", "ㅅ"),
                    listOf("ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ")
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    chosungRows.forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            rowItems.forEach { chosung ->
                                val selected = uiState.selectedChosung == chosung

                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(38.dp)
                                        .clickable {
                                            handleSelection {
                                                viewModel.selectChosung(chosung)
                                            }
                                        },
                                    shape = RoundedCornerShape(19.dp),
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    border = BorderStroke(
                                        1.dp,
                                        if (selected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outlineVariant
                                        }
                                    )
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = chosung,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = if (selected) {
                                                MaterialTheme.colorScheme.onPrimary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (uiState.speciesList.isEmpty()) {
                    ManagementEmptyHint("선택한 초성의 묘목이 없습니다.")
                } else {
                    GridSelectionMenu(
                        items = uiState.speciesList,
                        columns = 4,
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
                    onClick = { viewModel.activateNewInput() },
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
                        shape = RoundedCornerShape(16.dp),
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
                modifier = Modifier.padding(20.dp),
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

    val qualityOptions = listOf("", "특묘", "상묘", "중묘", "하묘")
    val plantTypes = listOf("", "실생", "접목", "삽목", "근삽")

    val currentAgeInt = uiState.inputAge.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0

    var showFarmRequiredError by remember { mutableStateOf(false) }

    if (showFarmRequiredError) {
        AlertDialog(
            onDismissRequest = { showFarmRequiredError = false },
            title = { Text("⚠️ 농원 선택 필요", fontWeight = FontWeight.Bold, color = Color.Red) },
            text = { Text("재고를 저장하려면 대상 '농원'이 필요합니다.\n화면 맨 위에서 농원을 먼저 선택해주세요.") },
            confirmButton = { Button(onClick = { showFarmRequiredError = false }) { Text("확인") } }
        )
    }

    if (showSaveConfirmDialog) {
        val isDelete = uiState.inputQuantity == 0
        AlertDialog(
            onDismissRequest = { showSaveConfirmDialog = false },
            title = { Text(if (isDelete) "🗑️ 해당 재고 삭제" else "💾 재고 저장 확인", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(if (isDelete) "다음 재고를 목록에서 완전히 삭제하시겠습니까?" else "다음 정보로 재고를 저장하시겠습니까?", color = Color.DarkGray)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("• 농원: ${uiState.selectedFarm?.farmName}")
                    Text("• 묘목: ${uiState.selectedSpecies?.speciesName}")
                    Text("• 년생: ${uiState.inputAge}")
                    // 🚨 로컬 변수 대신 uiState 사용!
                    Text("• 크기: ${String.format("%.1fm", uiState.inputSizeMeters)}")
                    Text("• 타입: ${uiState.inputPlantType}")
                    Text("• 품질: ${uiState.inputQuality}")
                    Text("• 수량: ${uiState.inputQuantity}주", color = if (isDelete) Color.Red else Color.Blue, fontWeight = FontWeight.ExtraBold)
                    Text("• 도매가: ${String.format("%,d", uiState.inputWholesale)}원")
                    Text("• 소매가: ${String.format("%,d", uiState.inputRetail)}원")
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.saveInventory(); showSaveConfirmDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = if (isDelete) Color.Red else Color(0xFF2196F3))
                ) { Text(if (isDelete) "삭제 진행" else "저장 진행", color = Color.White) }
            },
            dismissButton = { OutlinedButton(onClick = { showSaveConfirmDialog = false }) { Text("취소") } }
        )
    }

    Column(modifier = Modifier.padding(8.dp)) {

        SelectionSection("연생", color = Color(0xFF673AB7)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (currentAgeInt > 0) "${currentAgeInt}년생" else "-",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF673AB7),
                    modifier = Modifier.width(60.dp),
                    textAlign = TextAlign.Center
                )

                // 🚨 2. 년생 초기화 시 "0년생" 대신 완전한 빈칸("")을 넘겨주도록 수정!
                OutlinedButton(
                    onClick = { viewModel.updateAge("") }, // 수정됨!
                    modifier = Modifier.height(44.dp).weight(0.8f),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("선택안함", color = Color.Gray, fontSize = 13.sp) // 라벨 변경
                }

                Button(
                    onClick = { viewModel.updateAge("${currentAgeInt + 1}년생") },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                    modifier = Modifier.height(44.dp).weight(1.5f)
                ) {
                    Text("+1년생 추가", fontWeight = FontWeight.Bold)
                }
            }
        }

        SelectionSection("크기", color = Color(0xFF0288D1)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 🚨 로컬 변수 대신 uiState.inputSizeMeters 사용!
                Text(
                    text = String.format("%.1fm", uiState.inputSizeMeters),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF0288D1),
                    modifier = Modifier.width(60.dp),
                    textAlign = TextAlign.Center
                )

                OutlinedButton(
                    onClick = { viewModel.updateSize(0f) }, // 🚨 뷰모델 함수 호출
                    modifier = Modifier.height(44.dp).weight(0.8f),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("초기화", color = Color.Gray, fontSize = 13.sp)
                }

                Button(
                    onClick = { viewModel.updateSize(uiState.inputSizeMeters + 1.0f) }, // 🚨 뷰모델 함수 호출
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF29B6F6)),
                    modifier = Modifier.height(44.dp).weight(1f),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("+1m", color = Color.White, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = { viewModel.updateSize(uiState.inputSizeMeters + 0.1f) }, // 🚨 뷰모델 함수 호출
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF81D4FA)),
                    modifier = Modifier.height(44.dp).weight(1f),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("+10cm", color = Color(0xFF01579B), fontWeight = FontWeight.Bold)
                }
            }
        }

        SelectionSection("재배 타입", color = Color(0xFF8D6E63)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                plantTypes.forEach { type ->
                    val isSelected = uiState.inputPlantType == type
                    // 🚨 3. 빈칸("")일 때는 "선택안함"이라고 버튼에 글씨만 띄워줌!
                    val label = if (type.isEmpty()) "선택안함" else type

                    Button(
                        onClick = { viewModel.updatePlantType(type) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelected) Color(0xFF8D6E63) else Color(0xFFEFEBE9)
                        ),
                        modifier = Modifier.weight(1f).height(44.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(
                            text = label,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) Color.White else Color(0xFF4E342E),
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        SelectionSection("품질", color = Color(0xFF009688)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                qualityOptions.forEach { q ->
                    // 🚨 2. 빈칸("")일 때는 "선택안함"으로 글씨 표시
                    val label = if (q.isEmpty()) "선택안함" else q

                    SelectionButton(
                        text = label,
                        isSelected = uiState.inputQuality == q,
                        modifier = Modifier.weight(1f)
                    ) {
                        viewModel.updateQuality(q)
                    }
                }
            }
        }

        SelectionSection("수량 · 가격", color = Color(0xFFE91E63)) {
            ValueAdjuster("수량(주)", uiState.inputQuantity, listOf(1, 10, 100), resetLabel = "품절", onAdd = { viewModel.addQuantity(it) }, onReset = { viewModel.resetQuantityToSoldOut() })
            Divider(modifier = Modifier.padding(vertical = 12.dp), color = Color(0xFFF0F0F0))
            ValueAdjuster("도매가(원)", uiState.inputWholesale, listOf(100, 1000, 10000), resetLabel = "초기화", onAdd = { viewModel.addWholesale(it) }, onReset = { viewModel.resetWholesale() })
            Divider(modifier = Modifier.padding(vertical = 12.dp), color = Color(0xFFF0F0F0))
            ValueAdjuster("소매가(원)", uiState.inputRetail, listOf(100, 1000, 10000), resetLabel = "초기화", onAdd = { viewModel.addRetail(it) }, onReset = { viewModel.resetRetail() })
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                if (uiState.selectedFarm == null) {
                    // 🚨 농원이 없으면 빠꾸!
                    showFarmRequiredError = true
                } else if (uiState.inputQuantity > 0 && (uiState.inputWholesale == 0 || uiState.inputRetail == 0)) {
                    showValidationErrorDialog = true
                } else {
                    showSaveConfirmDialog = true
                }
            },
            modifier = Modifier.fillMaxWidth().height(64.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(if (uiState.inputQuantity == 0) "해당 재고 삭제 (DB 반영)" else "재고 저장 / 업데이트", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
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
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(16.dp)
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
            .height(44.dp)
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
    onReset: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = String.format("%,d", value),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            OutlinedButton(
                onClick = onReset,
                modifier = Modifier.weight(1f).height(44.dp),
                shape = RoundedCornerShape(11.dp),
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
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
                    modifier = Modifier.weight(1f).height(44.dp),
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
                modifier = Modifier.padding(20.dp),
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