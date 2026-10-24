package com.example.namufarm.webrequests

import android.content.Context
import com.example.namufarm.BuildConfig
import com.example.namufarm.data.remote.WebQuoteTransport
import com.example.namufarm.data.remote.WebQuoteValidator
import com.example.namufarm.data.repository.NamuFarmRepository

/** Transport credentials stay outside the business tables and outside the APK. */
class WebRequestConnection(context: Context, private val repository: NamuFarmRepository) {
    private val app = context.applicationContext
    private val settings = WebRequestSettings(app)
    private val api = settings.authenticatedApi(WebQuoteTransport.api)
    private val keys = com.example.namufarm.privacy.SellerKeys(app)
    val configured get() = settings.configured
    val paired get() = settings.paired
    val pushConfigured get() = WebRequestPush.configured
    val serverUrl get() = settings.url
    fun localRequests() = repository.getWebRequests()
    fun cachedFarms() = repository.getAllFarms().map {
        com.example.namufarm.data.remote.FarmDirectoryRecord(
            farmId = it.farmId ?: 0, farmName = it.farmName,
            address = it.address.orEmpty(), phone = it.phone.orEmpty(), memo = it.memo.orEmpty()
        )
    }.filter { it.farmId > 0 }
    fun cachedSpecies() = repository.getAllSpecies().map {
        com.example.namufarm.data.remote.SpeciesDirectoryRecord(
            speciesId = it.speciesId ?: 0, speciesName = it.speciesName,
            farmName = it.nameFarm.orEmpty(), chosung = it.chosung.orEmpty(),
            category = it.category.orEmpty(), memo = it.memo.orEmpty(), isActive = it.isActive ?: 1
        )
    }.filter { it.speciesId > 0 }
    val deferredCount get() = repository.deferredWebRequestCount()
    suspend fun read(id: String) = repository.markWebRequestRead(id)
    /** No file or password: new devices enroll on first manual sync or server connect. */
    suspend fun connect() {
        if (settings.paired) return
        check(settings.configured) { "나무팜 서버 주소를 확인해주세요." }
        val response = WebQuoteTransport.api.request(settings.url, mapOf(
            "action" to "connect_app", "authMode" to "auto-v1",
            "deviceToken" to settings.prepareToken(), "label" to "나무팜 Android"
        ))
        WebQuoteValidator.checkResponse(response)
        check(response.paired == true && response.authMode == "auto-v1") {
            "운영 Apps Script 배포가 이전 인증 방식입니다. Code.gs·SecureCommerce.gs를 최신 버전으로 반영하고 기존 웹앱 배포를 업데이트해주세요."
        }
        settings.markPaired()
        WebRequestPush.initialize(app)
        PushRegistrationWorker.enqueue(app)
    }
    suspend fun connectKey(key: String, url: String) {
        val profile = AppConnectionInput.fromInput(url, key)
        check(profile.url == settings.url || (!settings.paired && localRequests().isEmpty() && deferredCount == 0
                && repository.getAllOrders().isEmpty() && repository.getAllQuotes().isEmpty())) {
            "기존 자료가 있는 앱은 다른 서버로 바꿀 수 없습니다."
        }
        val response = WebQuoteTransport.api.request(profile.url, mapOf("action" to "connect_app",
            "authMode" to "shared-key-v1", "connectionKey" to profile.connectionKey,
            "deviceToken" to settings.prepareToken(), "label" to "나무팜 Android"))
        WebQuoteValidator.checkResponse(response)
        check(response.paired == true) { "연결 암호를 확인해주세요." }
        check(response.authMode == "shared-key-v1") { "공통 연결 암호를 지원하는 서버 버전으로 업데이트해주세요." }
        settings.markConnected(profile.url, profile.connectionKey)
        WebRequestPush.initialize(app)
        PushRegistrationWorker.enqueue(app)
    }
    suspend fun pair(invite: String) {
        check(configured) { "홈페이지 요청함 주소 설정이 필요합니다." }
        check(Regex("[a-f0-9]{64}").matches(invite.trim())) { "관리자가 발급한 연결 코드를 확인해주세요." }
        val result = WebQuoteTransport.api.request(settings.url, mapOf(
            "action" to "pair_device", "invite" to invite.trim(), "deviceToken" to settings.prepareToken(), "label" to "나무팜 Android"
        ))
        WebQuoteValidator.checkResponse(result)
        check(result.paired == true) { "기기 연결을 확인하지 못했습니다. 같은 코드로 다시 시도해주세요." }
        settings.markPaired()
        WebRequestPush.initialize(app)
        PushRegistrationWorker.enqueue(app)
    }
    suspend fun refresh(): Int {
        if (!paired) connect()
        check(configured && paired) { "홈페이지 요청함을 먼저 연결해주세요." }
        PushRegistrationWorker.enqueue(app)
        return repository.syncWebSnapshot(api, settings.url, settings.deviceToken, ::readRequest)
    }
    suspend fun latestRequest(id: String): com.example.namufarm.data.WebQuoteRequestEntity? {
        refresh()
        return localRequests().firstOrNull { it.requestId == id }
    }
    private fun readRequest(outer: com.example.namufarm.data.remote.WebQuoteRemote): com.example.namufarm.data.remote.WebQuoteRemote =
        readLegacyWebRequest(outer, keys)
    suspend fun restoreLegacy(): Int = repository.restoreDeferredWebRequests(::readRequest)
    fun restoreBackup(uri: android.net.Uri, password: CharArray) {
        try {
            val bytes=checkNotNull(app.contentResolver.openInputStream(uri)).use { stream ->
                val out=java.io.ByteArrayOutputStream();val buf=ByteArray(2048)
                while(true) { val n=stream.read(buf); if(n<0) break; check(out.size()+n<=20000);out.write(buf,0,n) };out.toByteArray()
            }
            keys.importBackup(bytes.toString(Charsets.UTF_8),password)
            bytes.fill(0)
        } finally { password.fill('\u0000') }
    }
    private suspend fun farmDirectoryAction(action: String, fields: Map<String, Any> = emptyMap()): com.example.namufarm.data.remote.WebQuoteEnvelope {
        if (!paired) connect()
        val response = api.request(settings.url, fields + mapOf("action" to action, "deviceToken" to settings.deviceToken))
        if (response.ok != true && response.code == "CONFLICT")
            error("농원 정보가 변경되었거나 같은 이름이 있습니다. 새로고침 후 다시 확인해주세요.")
        if (response.ok != true && response.code in setOf("INVALID_ACTION", "UPGRADE_REQUIRED"))
            error("농원관리 서버 업데이트가 필요합니다. 운영 Apps Script에 FarmManagement.gs를 반영해주세요.")
        WebQuoteValidator.checkResponse(response)
        return response
    }
    suspend fun farmDirectory(): List<com.example.namufarm.data.remote.FarmDirectoryRecord> {
        val farms = checkNotNull(farmDirectoryAction("list_farms").farms) { "농원 목록을 확인하지 못했습니다." }
        farms.forEach { it.checked() }
        check(farms.map { it.farmId }.distinct().size == farms.size &&
            farms.map { it.farmName }.distinct().size == farms.size) { "농원 번호 또는 이름이 중복되어 있습니다." }
        repository.cacheDirectoryFarms(farms)
        return farms
    }
    suspend fun saveDirectoryFarm(farm: com.example.namufarm.data.FarmEntity, expectedVersion: String,
        operationId: String): com.example.namufarm.data.remote.FarmDirectoryRecord {
        val fields = mapOf("farmName" to farm.farmName.trim(), "address" to farm.address.orEmpty().trim(),
            "phone" to farm.phone.orEmpty().trim(), "memo" to farm.memo.orEmpty().trim())
        val version = if (farm.farmId != null && expectedVersion.isBlank())
            farmDirectory().singleOrNull { it.farmId == farm.farmId }?.version
                ?: error("농원이 서버에서 삭제되었습니다. 새로고침해주세요.")
            else expectedVersion
        val response = farmDirectoryAction("save_farm", mapOf("farmId" to (farm.farmId ?: 0),
            "farm" to fields, "expectedVersion" to version, "operationId" to operationId))
        val saved = checkNotNull(response.farm).checked()
        check(response.saved == true && response.operationId == operationId &&
            (farm.farmId == null || saved.farmId == farm.farmId) &&
            saved.farmName == fields["farmName"] && saved.address == fields["address"] &&
            saved.phone == fields["phone"] && saved.memo == fields["memo"]) { "농원 저장 결과를 확인하지 못했습니다." }
        repository.cacheDirectoryFarms(listOf(saved))
        return saved
    }
    suspend fun deleteDirectoryFarm(farm: com.example.namufarm.data.remote.FarmDirectoryRecord) {
        repository.ensureDirectoryFarmCanDelete(farm.farmId)
        val current = if (farm.version.isBlank()) farmDirectory().singleOrNull { it.farmId == farm.farmId }
            ?: error("농원이 서버에서 삭제되었습니다. 새로고침해주세요.") else farm
        val response = farmDirectoryAction("delete_farm",
            mapOf("farmId" to current.farmId, "expectedVersion" to current.version))
        check(response.deleted == true && response.farmId == farm.farmId) { "농원 삭제 결과를 확인하지 못했습니다." }
        repository.removeDirectoryFarm(farm.farmId)
    }

    private suspend fun speciesAction(action: String, fields: Map<String, Any> = emptyMap()):
        com.example.namufarm.data.remote.WebQuoteEnvelope {
        if (!paired) connect()
        val response = api.request(settings.url,
            fields + mapOf("action" to action, "deviceToken" to settings.deviceToken))
        if (response.ok != true && response.code == "CONFLICT")
            error("묘목 정보가 변경되었거나 중복된 이름입니다. 새로고침해주세요.")
        if (response.ok != true && response.code in setOf("INVALID_ACTION", "UPGRADE_REQUIRED"))
            error("묘목관리 서버 업데이트가 필요합니다. SpeciesManagement.gs를 반영해주세요.")
        WebQuoteValidator.checkResponse(response)
        return response
    }
    suspend fun speciesDirectory(): List<com.example.namufarm.data.remote.SpeciesDirectoryRecord> {
        val species = checkNotNull(speciesAction("list_species").species) { "묘목 목록을 확인하지 못했습니다." }
        species.forEach { it.checked() }
        check(species.map { it.speciesId }.distinct().size == species.size &&
            species.map { it.speciesName }.distinct().size == species.size) {
            "묘목 번호 또는 이름이 중복되어 있습니다."
        }
        repository.cacheDirectorySpecies(species)
        return species
    }
    suspend fun saveDirectorySpecies(species: com.example.namufarm.data.remote.SpeciesDirectoryRecord,
        operationId: String): com.example.namufarm.data.remote.SpeciesDirectoryRecord {
        val current = if (species.speciesId > 0)
            speciesDirectory().singleOrNull { it.speciesId == species.speciesId }
                ?: error("묘목이 서버에서 삭제되었습니다. 새로고침해주세요.")
            else null
        val payload = mapOf("speciesName" to species.speciesName.trim(),
            "farmName" to species.farmName.trim(), "chosung" to species.chosung.trim(),
            "category" to species.category.trim(), "memo" to species.memo.trim(),
            "isActive" to species.isActive)
        val response = speciesAction("save_species",
            mapOf("speciesId" to species.speciesId, "species" to payload,
                "expectedVersion" to (current?.version ?: ""), "operationId" to operationId))
        val saved = checkNotNull(response.speciesRecord).checked()
        check(response.saved == true && response.operationId == operationId) { "묘목 저장 확인에 실패했습니다." }
        repository.cacheDirectorySpecies(listOf(saved))
        return saved
    }
    suspend fun deleteDirectorySpecies(species: com.example.namufarm.data.remote.SpeciesDirectoryRecord) {
        val current = speciesDirectory().singleOrNull { it.speciesId == species.speciesId }
            ?: error("묘목이 서버에서 삭제되었습니다. 새로고침해주세요.")
        val response = speciesAction("delete_species",
            mapOf("speciesId" to current.speciesId, "expectedVersion" to current.version))
        check(response.deleted == true && response.speciesId == current.speciesId) { "묘목 비활성 처리를 확인하지 못했습니다." }
        repository.cacheDirectorySpecies(listOf(current.copy(isActive = 0)))
    }

    private suspend fun inventoryAction(action: String, extra: Map<String, Any> = emptyMap()):
        com.example.namufarm.data.remote.WebQuoteEnvelope {
        if (!paired) connect()
        val response = api.request(settings.url,
            extra + mapOf("action" to action, "deviceToken" to settings.deviceToken))
        if (response.ok != true && response.code == "INVALID_ACTION")
            error("운영 서버에 InventoryManagement.gs를 반영하고 기존 웹앱을 업데이트해주세요.")
        if (response.ok != true && response.code == "CONFLICT")
            error("다른 기기에서 농원별 묘목 자료를 수정했습니다. 새로고침으로 확인해주세요.")
        WebQuoteValidator.checkResponse(response)
        return response
    }

    /** Saves an existing Room outbox item, using a stable idempotency key on retries. */
    suspend fun uploadPendingInventory(syncId: Int,
        payload: com.example.namufarm.data.PendingInventorySyncPayload): Boolean {
        val entity = payload.entity
        val operationId = java.util.UUID.nameUUIDFromBytes(
            ("namufarm:inventory:" + settings.prepareToken() + ":" + syncId)
                .toByteArray(Charsets.UTF_8)).toString()
        val fields = mapOf<String, Any>(
            "farmId" to entity.farmId, "speciesId" to entity.speciesId,
            "quality" to entity.quality, "age" to entity.age, "size" to entity.size,
            "plantType" to entity.plantType, "quantity" to entity.quantity,
            "wholesalePrice" to entity.wholesalePrice, "retailPrice" to entity.retailPrice
        )
        val result = inventoryAction("save_inventory",
            mapOf("inventory" to fields, "operationId" to operationId))
        return result.saved == true && result.operationId == operationId &&
            result.inventoryRecord?.checked()?.asEntity() == entity
    }

    /** Called by the farm-mode Refresh button, not on every screen open. */
    suspend fun refreshDirectoryInventory(): Int {
        repository.flushInventoryPending()
        val inventory = checkNotNull(inventoryAction("list_inventory").inventory) {
            "농원별 묘목 목록을 확인하지 못했습니다."
        }
        inventory.forEach { it.checked() }
        val keys = inventory.map { listOf(it.farmId, it.speciesId, it.quality, it.age, it.size, it.plantType) }
        check(keys.distinct().size == keys.size) { "농원별 묘목 재고 키가 중복되어 있습니다." }
        repository.cacheDirectoryInventory(inventory)
        return inventory.count { it.quantity > 0 }
    }

    suspend fun inquiryAction(action: String, fields: Map<String, Any> = emptyMap()): com.example.namufarm.data.remote.WebQuoteEnvelope {
        check(configured && paired) { "견적모드의 연결 · 설정에서 서버에 먼저 연결해주세요." }
        require(action in setOf("list_customer_inquiries", "save_inquiry_reply", "send_inquiry_reply", "set_inquiry_status", "inquiry_sms_status"))
        val response = api.request(settings.url, fields + mapOf("action" to action, "deviceToken" to settings.deviceToken))
        if (response.ok != true && response.code in setOf("NOT_READY", "INVALID_ACTION", "UPGRADE_REQUIRED")) {
            throw IllegalStateException("고객문의 서버를 최신 버전으로 재배포하고 setupCustomerInquiries를 실행해주세요.")
        }
        WebQuoteValidator.checkResponse(response)
        return response
    }

    suspend fun quoteAction(action: String, fields: Map<String, Any>): com.example.namufarm.data.remote.WebQuoteEnvelope {
        check(paired) { "서버를 먼저 연결해주세요." }
        require(action in setOf("send_quote_sms", "quote_sms_status", "send_quote_email"))
        val response = api.request(settings.url, fields + mapOf("action" to action, "deviceToken" to settings.deviceToken))
        if (response.ok != true && response.code == "CONFLICT" && action in setOf("send_quote_sms", "send_quote_email")) {
            val current = latestRequest(fields["requestId"] as String)
            if (current == null || current.status != "신규") throw com.example.namufarm.quotation.ServerQuoteRequestChanged()
            throw IllegalStateException("서버가 전송을 거절했습니다. 요청은 신규 상태입니다. 운영 서버의 문자·메일 처리 버전을 확인해주세요.")
        }
        WebQuoteValidator.checkResponse(response)
        return response
    }
    suspend fun confirmQuote(id: String, draft: com.example.namufarm.quotation.SellerQuoteDraft) {
        check(paired)
        val quotePricing = mapOf<String, Any>(
            "items" to draft.rows.map { row ->
                mapOf("species" to row.name, "specification" to row.spec,
                    "quantity" to (row.quantity.toIntOrNull() ?: error("수량을 확인해주세요.")),
                    "unitPrice" to (row.price.toIntOrNull() ?: error("단가를 확인해주세요.")))
            },
            "isPrepaid" to draft.prepaid,
            "shippingFee" to draft.shipping(),
            "taxAmount" to if (draft.recipient.formalQuoteRequested) draft.options.taxAmount() else 0L
        )
        val response = api.request(settings.url, mapOf(
            "action" to "save_quote_pricing", "deviceToken" to settings.deviceToken,
            "requestId" to id, "quotePricing" to quotePricing
        ))
        WebQuoteValidator.checkResponse(response)
        check(response.updated == true && response.requestId == id && response.status == "견적확정") {
            "견적확정 상태를 확인하지 못했습니다."
        }
    }
    suspend fun markQuoteSent(id: String) {
        check(paired) { "홈페이지 요청함을 먼저 연결해주세요." }
        val response = api.request(settings.url, mapOf(
            "action" to "update_request_status", "deviceToken" to settings.deviceToken,
            "requestId" to id, "status" to "견적발송완료"
        ))
        WebQuoteValidator.checkResponse(response)
        check(response.updated == true && response.requestId == id && response.status == "견적발송완료") {
            "견적 발송완료 상태를 서버에서 확인하지 못했습니다."
        }
    }
    suspend fun close(id: String) {
        check(paired)
        val response=api.request(settings.url,mapOf("action" to "close_request","deviceToken" to settings.deviceToken,"requestId" to id))
        WebQuoteValidator.checkResponse(response);check(response.closed==true)
        repository.closeLocalRequest(id)
    }
}

internal fun readLegacyWebRequest(outer: com.example.namufarm.data.remote.WebQuoteRemote, keys: com.example.namufarm.privacy.SellerKeys): com.example.namufarm.data.remote.WebQuoteRemote {
    val envelope = outer.encryptedRequest ?: return outer
    if (!keys.initialized || keys.keyId() != envelope.keyId) throw com.example.namufarm.data.remote.LegacyRequestKeyUnavailable()
    return com.google.gson.Gson().fromJson(keys.open(envelope, "quote", checkNotNull(outer.requestId)), com.example.namufarm.data.remote.WebQuoteRemote::class.java)
}




