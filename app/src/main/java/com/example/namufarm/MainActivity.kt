package com.example.namufarm

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.telephony.TelephonyManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.namufarm.data.AppDatabase
import com.example.namufarm.data.SyncManager
import com.example.namufarm.data.remote.RetrofitClient
import com.example.namufarm.ui.ManagementModeScreen
import com.example.namufarm.ui.SellingModeScreen
import com.example.namufarm.viewmodel.ManagementViewModel
import com.example.namufarm.viewmodel.ManagementViewModelFactory
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    // 🚨 뷰모델을 클래스 전역에서 쓸 수 있게 빼줌
    private lateinit var viewModel: ManagementViewModel

    // 🚨 통화 상태를 실시간으로 감시할 스파이(리시버)
    private var phoneStateReceiver: BroadcastReceiver? = null

    // 🚨 앱 시작 시 권한을 물어보는 런처
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val phoneStateGranted = permissions[Manifest.permission.READ_PHONE_STATE] ?: false
        val callLogGranted = permissions[Manifest.permission.READ_CALL_LOG] ?: false

        if (phoneStateGranted && callLogGranted) {
            registerPhoneStateReceiver() // 권한 받으면 감시자 투입!
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.WHITE

        if (com.example.namufarm.data.DataMaintenance.active) {
            setContent { com.example.namufarm.ui.theme.NamuTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("자료를 보존하는 중입니다. 잠시 기다려주세요.") }
            } }
            lifecycleScope.launch {
                com.example.namufarm.data.DataMaintenance.state.first { !it }
                if (!isFinishing && !isDestroyed) recreate()
            }
            return
        }

        // Complete or roll back an interrupted replacement before opening any database.
        com.example.namufarm.data.BackupRestoreJournal.recover(this)

        // 1. DB와 DAO 가져오기
        val database = AppDatabase.getDatabase(this)
        val dao = database.managementDao()

        // 2. 동기화 엔진(API 통신망) 생성 및 조립
        val credentials = com.example.namufarm.data.remote.MasterDeviceCredentials(this)
        val masterClient = com.example.namufarm.data.remote.OperationsMasterClient(credentials)
        val masterSync = com.example.namufarm.data.OperationsMasterSync(database, masterClient)
        val stockSync = com.example.namufarm.data.InventorySync(database, masterClient, masterSync, credentials, filesDir)
        val syncManager = SyncManager(dao, masterSync, stockSync)

        // 3. 공장에 dao와 syncManager 둘 다 넘겨주기!
        val factory = ManagementViewModelFactory(dao, syncManager)

        // 🚨 위에서 선언한 전역 변수에 뷰모델 할당!
        viewModel = ViewModelProvider(this, factory)[ManagementViewModel::class.java]

        val quoteConnection = com.example.namufarm.quotemode.InitialQuoteConnection(
            com.example.namufarm.quotemode.InitialQuoteDatabase.get(this), masterClient)
        val quoteViewModel = ViewModelProvider(this,
            com.example.namufarm.quotemode.InitialQuoteViewModelFactory(quoteConnection, this))[
                com.example.namufarm.quotemode.InitialQuoteViewModel::class.java]

        val inquiryRepository = com.example.namufarm.data.repository.CustomerInquiryRepository(
            com.example.namufarm.data.CustomerInquiryDatabase.get(this),
            com.example.namufarm.quotemode.CustomerInquiryConnection(masterClient))
        val inquiryViewModel = ViewModelProvider(this,
            com.example.namufarm.viewmodel.CustomerInquiryViewModelFactory(inquiryRepository))[
                com.example.namufarm.viewmodel.CustomerInquiryViewModel::class.java]
        com.example.namufarm.quotemode.CustomerInquiryCleanupWorker.schedule(this)

        val backupController = com.example.namufarm.data.DataBackupController(this,
            quiesce = {
                withContext(Dispatchers.IO) {
                    androidx.work.WorkManager.getInstance(this@MainActivity).cancelUniqueWork("initial-inquiry-disposal").result.get()
                }
                listOf(viewModel, quoteViewModel, inquiryViewModel).forEach {
                    it.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
                }
                withContext(Dispatchers.Main) { viewModelStore.clear() }
            }, resumeUi = { if (!isFinishing && !isDestroyed) recreate() })

        // 🚨 4. 권한 체크 및 통화 감시자 작동 시작!
        checkAndRequestPermissions()

        setContent {
            val navController = rememberNavController()

            NavHost(navController = navController, startDestination = "splash") {
                composable("splash") {
                    SplashScreen {
                        navController.navigate("modes") { popUpTo("splash") { inclusive = true } }
                    }
                }
                composable("modes") {
                    com.example.namufarm.quotemode.InitialAppModes(viewModel, quoteViewModel, inquiryViewModel, backupController)
                }
            }
        }
    }

    // --- 🚨 [통화 감시 관련 핵심 로직] ---

    private fun checkAndRequestPermissions() {
        val hasPhoneState = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val hasCallLog = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

        if (hasPhoneState && hasCallLog) {
            registerPhoneStateReceiver() // 이미 권한 있으면 바로 감시자 투입
        } else {
            // 없으면 권한 요청 팝업 띄우기
            requestPermissionLauncher.launch(
                arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG)
            )
        }
    }

    private fun registerPhoneStateReceiver() {
        if (phoneStateReceiver != null) return

        phoneStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
                    val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                    val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

                    // 🚨 전화벨이 울리거나(RINGING) 통화가 연결되었을 때(OFFHOOK)!
                    if (state == TelephonyManager.EXTRA_STATE_RINGING || state == TelephonyManager.EXTRA_STATE_OFFHOOK) {
                        if (!incomingNumber.isNullOrBlank()) {
                            // 뷰모델의 전화번호 업데이트 함수를 쏴버림! -> UI에 마법처럼 촥 꽂힘
                            viewModel.updateCustomerPhone(incomingNumber)
                            android.util.Log.d("NamuFarm", "통화 감지됨! 번호: $incomingNumber")
                        }
                    }
                }
            }
        }

        // 안드로이드 시스템에 우리 감시자를 정식으로 등록
        val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        registerReceiver(phoneStateReceiver, filter)
    }

    override fun onDestroy() {
        super.onDestroy()
        // 앱 꺼질 때 감시자도 같이 퇴근시킴 (메모리 누수 방지)
        phoneStateReceiver?.let { unregisterReceiver(it) }
    }
}

// 🌲 스플래시 화면 컴포넌트
@Composable
fun SplashScreen(onTimeout: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(1500)
        onTimeout()
    }
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFFE8F5E9)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🌲", fontSize = 100.sp)
            Text("나무팜 NamuFarm", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF2E7D32))
            Text("재고 및 견적 관리 시스템", fontSize = 14.sp, color = Color(0xFF388E3C))
        }
    }
}

