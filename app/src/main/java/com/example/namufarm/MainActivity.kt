package com.example.namufarm

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
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
import com.example.namufarm.ui.IncomingCallAssistDialog
import com.example.namufarm.phone.CallAssistRequest
import androidx.compose.runtime.saveable.rememberSaveable
import com.example.namufarm.ui.theme.NamuFarmTheme
import com.example.namufarm.viewmodel.ManagementViewModel
import com.example.namufarm.viewmodel.ManagementViewModelFactory
import kotlinx.coroutines.delay
import com.example.namufarm.ui.WebQuoteInboxDialog
import com.example.namufarm.viewmodel.WebInboxViewModel
import com.example.namufarm.viewmodel.WebInboxViewModelFactory
import com.example.namufarm.webrequests.WebRequestConnection
import com.example.namufarm.webrequests.WebRequestPush

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: ManagementViewModel
    private var webRequestId by mutableStateOf<String?>(null)
    private var callAssistRequest by mutableStateOf<CallAssistRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webRequestId = if (savedInstanceState == null) WebRequestPush.fromIntent(intent) else savedInstanceState.getString("web_request_id")
        callAssistRequest = if (savedInstanceState == null) CallAssistRequest.fromIntent(intent)
        else savedInstanceState.getString("incoming_caller_id")?.let {
            CallAssistRequest(it, savedInstanceState.getBoolean("incoming_reply"))
        }

        // 1. DB와 DAO 가져오기
        val database = AppDatabase.getDatabase(this)
        val dao = database.managementDao()

        // 2. 데이터 계층 조립
        val api = RetrofitClient.api
        val keys = com.example.namufarm.privacy.SellerKeys(this)
        val localDataSource = LocalDataSource(database, dao, keys)
        val syncManager = SyncManager(localDataSource, api, com.example.namufarm.sales.DeviceSalesGateway(com.example.namufarm.webrequests.WebRequestSettings(this),keys))
        com.example.namufarm.privacy.PrivacyCleanupWorker.schedule(this)
        val repository = NamuFarmRepository(localDataSource, syncManager, RetrofitClient.jusoApi)

        // 3. ViewModel은 Repository만 바라본다.
        val factory = ManagementViewModelFactory(repository)

        viewModel = ViewModelProvider(this, factory)[ManagementViewModel::class.java]

        val webInbox = ViewModelProvider(this, WebInboxViewModelFactory(WebRequestConnection(this, repository)))[WebInboxViewModel::class.java]

        setContent {
            NamuFarmTheme {
                val navController = rememberNavController()
                var showWebInbox by rememberSaveable { mutableStateOf(false) }
                val webState by webInbox.state.collectAsState()
                LaunchedEffect(showWebInbox, webRequestId) {
                    if ((showWebInbox || webRequestId != null) && webState.paired) webInbox.refresh()
                }
                var showCallAssist by rememberSaveable { mutableStateOf(false) }
                val uiState by viewModel.uiState.collectAsState()

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
                            onNavigateToManagement = { navController.navigate("management") },
                            onCallAssist = { showCallAssist = true },
                            onWebInbox = { if (webState.paired) webInbox.refresh() else showWebInbox = true },
                            onWebSettings = { showWebInbox = true },
                            webRequests = webState.requests, webBusy = webState.busy,
                            onRequest = { request -> if (!uiState.isSavingQuote) { viewModel.useWebRequestForQuote(request); webInbox.read(request.requestId) } },
                            webUnread = webState.unread
                        )
                    }
                    composable("management") {
                        ManagementModeScreen(
                            viewModel = viewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
                LaunchedEffect(webState.message, showWebInbox, webRequestId) {
                    if (!showWebInbox && webRequestId == null) webState.message?.let {
                        viewModel.showMessage(it); webInbox.clearMessage()
                    }
                }
                LaunchedEffect(webState.busy) { if (!webState.busy) viewModel.loadTodayData() }
                if (showWebInbox || webRequestId != null) {
                    WebQuoteInboxDialog(webState, webRequestId, uiState.customerPhone, uiState.estimateCart.size,
                        onRefresh = webInbox::refresh, onPair = webInbox::pair, onRead = webInbox::read,
                        onRestore=webInbox::restore,onClose=webInbox::close,onConnectFile=webInbox::connectFile,
                        onUseRequest = { request ->
                            if (uiState.isSavingQuote) webInbox.showMessage("견적 저장이 끝난 뒤 고객 번호를 적용해주세요.")
                            else {
                                viewModel.useWebRequestForQuote(request)
                                viewModel.showMessage("희망 묘목을 불러왔어요. 규격과 판매 단가를 입력해주세요.")
                                showWebInbox = false; webRequestId = null
                            }
                        }, onMessageConsumed = webInbox::clearMessage,
                        onDismiss = { showWebInbox = false; webRequestId = null })
                } else if (showCallAssist || callAssistRequest != null) {
                    IncomingCallAssistDialog(
                        request = callAssistRequest,
                        customerPhone = uiState.customerPhone,
                        cartItemCount = uiState.estimateCart.size,
                        onUseNumber = { phone ->
                            if (uiState.isSavingQuote) {
                                viewModel.showMessage("견적 저장이 끝난 뒤 고객 번호를 적용해주세요.")
                            } else {
                                viewModel.updateCustomerPhone(phone)
                                viewModel.showMessage("고객 번호를 적용했어요. 판매화면에서 품목을 담고 견적을 작성해주세요.")
                            }
                            showCallAssist = false
                            callAssistRequest = null
                        },
                        onDismiss = { showCallAssist = false; callAssistRequest = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        WebRequestPush.fromIntent(intent)?.let { webRequestId = it }
        CallAssistRequest.fromIntent(intent)?.let { callAssistRequest = it }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webRequestId?.let { outState.putString("web_request_id", it) }
        callAssistRequest?.let {
            outState.putString("incoming_caller_id", it.callerId)
            outState.putBoolean("incoming_reply", it.reply)
        }
        super.onSaveInstanceState(outState)
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

