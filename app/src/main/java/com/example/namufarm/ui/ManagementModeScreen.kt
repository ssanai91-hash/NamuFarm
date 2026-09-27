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

    var showConfirmDialog by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val hasUnsavedChanges = uiState.inputQuantity > 0 || uiState.inputWholesale > 0 || uiState.inputRetail > 0

    val handleSelection = { action: () -> Unit ->
        if (hasUnsavedChanges) { pendingAction = action; showConfirmDialog = true } else { action() }
    }

    val isSpeciesEnabled = uiState.selectedFarm != null
    var showFarmManageDialog by remember { mutableStateOf(false) }

    // 기존 변수들 밑에 이거 하나 추가!
    var showSpeciesManageDialog by remember { mutableStateOf(false) }

    // (기존 isSpeciesEnabled 변수는 지우거나 그냥 둬도 돼. 이제 무조건 true로 쓸 거니까!)
    val isInputEnabled = uiState.selectedSpecies != null // 농원 조건 제거!

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "나무팜 관리모드",
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                },
                // 🚨 1. 왼쪽 뒤로가기 화살표(navigationIcon) 완전 삭제!

                // 🚨 2. 우측(actions)에 매매모드와 완벽하게 동일한 스타일의 버튼 배치
                actions = {
                    // (아까 추가했던 마스터 동기화 버튼 유지)
                    IconButton(onClick = {
                        viewModel.syncFarmMasterFromGoogleSheet()
                        viewModel.syncSpeciesMasterFromGoogleSheet()
                    }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "마스터 동기화",
                            tint = Color.White
                        )
                    }

                    // 🚨 [새로 추가된 매매모드 전환 버튼]
                    Button(
                        onClick = onBack, // onBack 파라미터가 매매모드로 돌아가는 역할을 함
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.padding(end = 8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("매매모드", color = Color.Black, fontWeight = FontWeight.ExtraBold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                modifier = Modifier.background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color(0xFF4CAF50), Color(0xFF2E7D32))
                    )
                )
            )
        }
    ) { padding ->
        // ... 아래 화면 내용은 기존 코드 그대로 유지 ... { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).background(Color(0xFFF2F4F7)).verticalScroll(scrollState)) {

            SelectionSection(
                title = "1. 농원 선택",
                color = Color(0xFF1976D2),
                headerAction = {
                    OutlinedButton(
                        onClick = { showFarmManageDialog = true },
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.5.dp, Color(0xFF1976D2)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text(
                            text = "농원관리",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1976D2)
                        )
                    }
                }
            ) {
                GridSelectionMenu(
                    items = uiState.farmList,
                    columns = 5,
                    itemContent = { farm -> farm.farmName.trim() },
                    isSelected = { farm -> farm.farmId == uiState.selectedFarm?.farmId },
                    onSelect = { farm -> handleSelection { viewModel.selectFarm(farm) } },
                    selectedBorderColor = Color(0xFF1565C0),
                    selectedBorderWidth = 2.dp,
                    selectedTextWeight = FontWeight.ExtraBold
                )
            }

            // 🚨 여기서부터 복사! (기존 Box 껍데기는 완전히 지워버리세요)
            SelectionSection(
                title = "2. 묘목 선택",
                color = Color(0xFF388E3C),
                headerAction = {
                    OutlinedButton(
                        onClick = { showSpeciesManageDialog = true },
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.5.dp, Color(0xFF388E3C)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text(
                            text = "묘목관리",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF388E3C)
                        )
                    }
                }
            ) {
                val chosungRows = listOf(
                    listOf("ㄱ", "ㄴ", "ㄷ", "ㄹ", "ㅁ", "ㅂ", "ㅅ"),
                    listOf("ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ")
                )

                Surface(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = Color.White,
                    border = BorderStroke(1.dp, Color(0xFFE0E0E0))
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        chosungRows.forEach { rowItems ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowItems.forEach { chosung ->
                                    val isSelected = uiState.selectedChosung == chosung

                                    Button(
                                        onClick = {
                                            // 🚨 제한 조건 싹 지움! 무조건 클릭됨!
                                            handleSelection { viewModel.selectChosung(chosung) }
                                        },
                                        modifier = Modifier.weight(1f).height(42.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isSelected) Color(0xFF388E3C) else Color(0xFFF1F8E9)
                                        ),
                                        shape = RoundedCornerShape(20.dp),
                                        contentPadding = PaddingValues(0.dp)
                                    ) {
                                        Text(
                                            text = chosung,
                                            color = if (isSelected) Color.White else Color(0xFF2E7D32),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                GridSelectionMenu(
                    items = uiState.speciesList,
                    columns = 5,
                    itemContent = { it.speciesName },
                    isSelected = { it.speciesId == uiState.selectedSpecies?.speciesId },
                    onSelect = { species ->
                        // 🚨 제한 조건 싹 지움! 무조건 클릭됨!
                        handleSelection { viewModel.selectSpecies(species) }
                    }
                )
            }
            // 🚨 여기까지 덮어쓰기!

            if (isInputEnabled) {
                if (uiState.inventoryList.isNotEmpty()) InventoryListSection(viewModel = viewModel)
                Button(
                    onClick = { viewModel.activateNewInput() },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)), shape = RoundedCornerShape(8.dp)
                ) { Text("'${uiState.selectedSpecies?.speciesName}' 추가", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            }

            Box(modifier = Modifier.alpha(if (uiState.isInputActive) 1f else 0.4f)) {
                Box {
                    InventoryInputPane(viewModel = viewModel)
                    if (!uiState.isInputActive) Box(modifier = Modifier.matchParentSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {})
                }
            }
            Spacer(modifier = Modifier.height(30.dp))
        }
    }

    if (showConfirmDialog) {
        AlertDialog(onDismissRequest = { showConfirmDialog = false }, title = { Text("알림") }, text = { Text("입력 중인 데이터가 사라집니다. 계속할까요?") }, confirmButton = { TextButton(onClick = { viewModel.resetInput(); pendingAction?.invoke(); showConfirmDialog = false }) { Text("확인") } }, dismissButton = { TextButton(onClick = { showConfirmDialog = false }) { Text("취소") } })
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
                // ViewModel의 saveSpecies (id, 이름, 농원명(빈값), 초성, 카테고리(빈값), 메모(빈값), 활성(1)) 호출
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
            title = { Text("농원 삭제", fontWeight = FontWeight.Bold) },
            text = {
                Text("선택한 농원을 삭제할까요?\n해당 농원의 묘목 재고도 모두 삭제됩니다.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete(editingFarmId!!)
                        showDeleteConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) {
                    Text("삭제", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteConfirm = false }) {
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
                Column {
                    Text("다음 정보로 저장할까요?")
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("농원명: $farmName")
                    if (farmAddress.isNotBlank()) Text("주소: $farmAddress")
                    if (farmPhone.isNotBlank()) Text("전화: $farmPhone")
                    if (farmMemo.isNotBlank()) Text("메모: $farmMemo")
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
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
                ) {
                    Text("저장", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showSaveConfirm = false }) {
                    Text("취소")
                }
            }
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.White,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "농원관리",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF1976D2),
                        modifier = Modifier.weight(1f)
                    )

                    TextButton(
                        onClick = {
                            editingFarmId = null
                            farmName = ""
                            farmAddress = ""
                            farmPhone = ""
                            farmMemo = ""
                        }
                    ) {
                        Text(
                            text = "신규추가",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF2E7D32)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = farmName,
                    onValueChange = { farmName = it },
                    label = { Text("농원명") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = farmAddress,
                    onValueChange = { farmAddress = it },
                    label = { Text("농원주소") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = farmPhone,
                    onValueChange = { farmPhone = it },
                    label = { Text("전화번호") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = farmMemo,
                    onValueChange = { farmMemo = it },
                    label = { Text("메모") },
                    minLines = 3,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (editingFarmId != null) {
                        Button(
                            onClick = { showDeleteConfirm = true },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                        ) {
                            Text("삭제", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }

                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("취소")
                    }

                    Button(
                        onClick = {
                            if (farmName.isNotBlank()) {
                                showSaveConfirm = true
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
                    ) {
                        Text("저장", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun InventoryListSection(viewModel: ManagementViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    Column(modifier = Modifier.padding(horizontal = 8.dp)) {
        SelectionSection("${uiState.selectedSpecies?.speciesName} 재고 목록 (클릭 시 수정)", color = Color(0xFFE65100)) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth().background(Color(0xFFF5F5F5)).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    // 🚨 2. 헤더 수정 (스펙 한눈에 보기)
                    Text("묘목 스펙", modifier = Modifier.weight(2f), fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text("재고", modifier = Modifier.weight(1f), fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text("도매가", modifier = Modifier.weight(1.2f), fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text("소매가", modifier = Modifier.weight(1.2f), fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }

                uiState.inventoryList.forEach { row ->
                    HorizontalDivider(color = Color(0xFFEEEEEE), thickness = 0.5.dp)
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { viewModel.loadExistingRowToInput(row, uiState.allSpeciesList) }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 🚨 3. 내용 수정 (크기와 타입 표시)
                        Column(modifier = Modifier.weight(2f).padding(start = 8.dp)) {
                            Text("${row.age} / ${row.size}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                            Text("${row.plantType} / ${row.quality}", fontSize = 11.sp, color = Color.Gray)
                        }
                        Text("${String.format("%,d", row.quantity)}주", modifier = Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFFD32F2F), textAlign = TextAlign.Center)
                        Text(String.format("%,d", row.wholesalePrice), modifier = Modifier.weight(1.2f), fontSize = 13.sp, color = Color(0xFF1976D2), textAlign = TextAlign.End)
                        Text(String.format("%,d", row.retailPrice), modifier = Modifier.weight(1.2f).padding(end = 8.dp), fontSize = 13.sp, color = Color(0xFF388E3C), textAlign = TextAlign.End)
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

        SelectionSection("3. 년생 선택", color = Color(0xFF673AB7)) {
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

        SelectionSection("4. 묘목 크기", color = Color(0xFF0288D1)) {
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

        SelectionSection("5. 재배 타입", color = Color(0xFF8D6E63)) {
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

        SelectionSection("6. 품질 선택", color = Color(0xFF009688)) {
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

        SelectionSection("7. 수량 및 가격 설정", color = Color(0xFFE91E63)) {
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
    selectedBorderColor: Color = Color(0xFF2196F3),
    selectedBorderWidth: Dp = 1.dp,
    selectedTextWeight: FontWeight = FontWeight.Bold
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val rows = items.chunked(columns)

        rows.forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                rowItems.forEach { item ->
                    val selected = isSelected(item)

                    // 🚨 [기존 Box 안쪽 내용 교체]
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .background(
                                if (selected) Color(0xFFE3F2FD) else Color.White,
                                RoundedCornerShape(8.dp)
                            )
                            .border(
                                width = if (selected) selectedBorderWidth else 1.dp,
                                color = if (selected) selectedBorderColor else Color(0xFFE0E0E0),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable { onSelect(item) },
                        contentAlignment = Alignment.Center
                    ) {
                        // 🚨 1. 글자 길이에 따라 폰트 크기를 다르게 줍니다!
                        val textStr = itemContent(item)
                        val dynamicFontSize = when {
                            textStr.length >= 7 -> 9.sp    // 가이즈까향나무 (아주 작게)
                            textStr.length >= 5 -> 10.sp   // 5~6글자
                            else -> 12.sp                  // 감, 사과나무 (기본 크기)
                        }

                        Text(
                            text = textStr,
                            fontSize = dynamicFontSize,
                            textAlign = TextAlign.Center,
                            maxLines = 1, // 🚨 무조건 1줄로 강제!
                            softWrap = false, // 🚨 자동 줄바꿈 금지!
                            letterSpacing = (-0.5).sp, // 🚨 자간을 살짝 좁혀서 공간 확보
                            color = if (selected) Color(0xFF1565C0) else Color.DarkGray,
                            fontWeight = if (selected) selectedTextWeight else FontWeight.Normal
                        )
                    }
                }

                if (rowItems.size < columns) {
                    repeat(columns - rowItems.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(Color.White),
        elevation = CardDefaults.cardElevation(2.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    color = color,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f)
                )

                headerAction?.invoke()
            }

            Spacer(modifier = Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
fun SelectionButton(text: String, isSelected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(modifier = modifier.height(44.dp).clickable { onClick() }, shape = RoundedCornerShape(8.dp), color = if (isSelected) Color(0xFFE3F2FD) else Color(0xFFF8F9FA), border = BorderStroke(1.dp, if (isSelected) Color(0xFF2196F3) else Color(0xFFE0E0E0))) {
        Box(contentAlignment = Alignment.Center) { Text(text = text, fontSize = 13.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, color = if (isSelected) Color(0xFF1976D2) else Color.DarkGray) }
    }
}

@Composable
fun ValueAdjuster(label: String, value: Int, steps: List<Int>, resetLabel: String = "초기화", onAdd: (Int) -> Unit, onReset: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Black, modifier = Modifier.width(70.dp))
            Text(String.format("%,d", value), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF1976D2), modifier = Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onReset, modifier = Modifier.weight(1f).height(46.dp), colors = ButtonDefaults.buttonColors(Color(0xFFFFEBEE)), shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(0.dp)) {
                Text(resetLabel, color = Color(0xFFD32F2F), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            steps.forEach { step ->
                Button(onClick = { onAdd(step) }, modifier = Modifier.weight(1f).height(46.dp), colors = ButtonDefaults.buttonColors(Color(0xFFF1F8E9)), shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(0.dp)) {
                    Text("+$step", color = Color(0xFF388E3C), fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
    var editingSpeciesId by remember(selectedSpecies) { mutableStateOf(selectedSpecies?.speciesId) }
    var speciesName by remember(selectedSpecies) { mutableStateOf(selectedSpecies?.speciesName.orEmpty()) }

    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm && editingSpeciesId != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("묘목 삭제(비활성)", fontWeight = FontWeight.Bold) },
            text = { Text("이 묘목을 목록에서 삭제하시겠습니까?") },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete(editingSpeciesId!!)
                        showDeleteConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) { Text("삭제", color = Color.White, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { OutlinedButton(onClick = { showDeleteConfirm = false }) { Text("취소") } }
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = Color.White, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("묘목관리", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF388E3C), modifier = Modifier.weight(1f))
                    TextButton(onClick = { editingSpeciesId = null; speciesName = "" }) {
                        Text("신규추가", fontWeight = FontWeight.Bold, color = Color(0xFF1976D2))
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = speciesName,
                    onValueChange = { speciesName = it },
                    label = { Text("묘목명") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(18.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (editingSpeciesId != null) {
                        Button(onClick = { showDeleteConfirm = true }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))) {
                            Text("삭제", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("취소") }
                    Button(
                        onClick = { if (speciesName.isNotBlank()) onSave(editingSpeciesId, speciesName.trim()) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF388E3C))
                    ) { Text("저장", color = Color.White, fontWeight = FontWeight.Bold) }
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