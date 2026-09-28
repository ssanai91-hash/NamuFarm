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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.namufarm.data.AppDatabase
import com.example.namufarm.data.SyncManager
import com.example.namufarm.data.local.LocalDataSource
import com.example.namufarm.data.repository.NamuFarmRepository
import com.example.namufarm.data.remote.RetrofitClient
import com.example.namufarm.ui.ManagementModeScreen
import com.example.namufarm.ui.SellingModeScreen
import com.example.namufarm.ui.theme.NamuFarmTheme
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

        // 1. DB와 DAO 가져오기
        val database = AppDatabase.getDatabase(this)
        val dao = database.managementDao()

        // 2. 데이터 계층 조립
        val api = RetrofitClient.api
        val localDataSource = LocalDataSource(database, dao)
        val syncManager = SyncManager(localDataSource, api)
        val repository = NamuFarmRepository(localDataSource, syncManager, RetrofitClient.jusoApi)

        // 3. ViewModel은 Repository만 바라본다.
        val factory = ManagementViewModelFactory(repository)

        // 🚨 위에서 선언한 전역 변수에 뷰모델 할당!
        viewModel = ViewModelProvider(this, factory)[ManagementViewModel::class.java]

        // 🚨 4. 권한 체크 및 통화 감시자 작동 시작!
        checkAndRequestPermissions()

        setContent {
            NamuFarmTheme {
                val navController = rememberNavController()

                NavHost(navController = navController, startDestination = "splash") {
                    composable("splash") {
                        SplashScreen {
                            navController.navigate("selling") {
                                popUpTo("splash") { inclusive = true }
                            }
                        }
                    }
                    composable("selling") {
                        SellingModeScreen(
                            viewModel = viewModel,
                            onNavigateToManagement = { navController.navigate("management") }
                        )
                    }
                    composable("management") {
                        ManagementModeScreen(
                            viewModel = viewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
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
        delay(900)
        onTimeout()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                modifier = Modifier.size(88.dp),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.primary
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "나",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 38.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "나무팜",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Text(
                text = "묘목 판매 · 재고 관리",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}