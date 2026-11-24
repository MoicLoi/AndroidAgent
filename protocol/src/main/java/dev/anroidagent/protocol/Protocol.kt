package dev.anroidagent.protocol

/**
 * 协议层**只有接口**。
 *
 * 本仓库不包含任何第三方 App 的协议实现、签名算法、加密实现。
 * 使用者必须为自己拥有或有授权的目标自行实现 [TargetAdapter]。
 * 这一条是 LICENSE 与 Prohibited Use 的一部分，不要在本仓库内添加实现。
 */

data class Credential(
    val account: String,
    val secret: String,
)

data class Session(
    val adapterId: String,
    val token: String,
    val expiresAt: Long = 0L,
    val extra: Map<String, String> = emptyMap(),
)

data class ApiRequest(
    val method: String = "POST",
    val path: String,
    val body: String? = null,
    val query: Map<String, String> = emptyMap(),
)

data class ApiResponse(
    val code: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
)

interface TargetAdapter {
    val id: String

    suspend fun login(cred: Credential): Session

    suspend fun refresh(session: Session): Session

    suspend fun call(session: Session, req: ApiRequest): ApiResponse
}

/**
 * 签名实现。**唯一必须支持热更新的部分**：目标服务端签名算法一变，
 * 所有旧版本当天失效，必须能远程下发新版实现。
 */
interface Signer {
    val version: Int

    /** 需要 DeviceInfoProvider 提供哪些字段（用于一致性校验） */
    val requiredInputs: Set<String>

    fun sign(
        req: ApiRequest,
        session: Session,
        device: Map<String, String>,
    ): Map<String, String>
}

/**
 * 签名实现注册表。
 *
 * 加载外部实现前必须校验：dex 的 sha256 是否匹配服务端下发的值、
 * version 是否 >= 服务端要求的最低版本。任一不满足则拒绝加载并禁用协议功能。
 */
class SignerRegistry {

    private val impls = LinkedHashMap<Int, Signer>()

    @Volatile
    private var active: Int = 0

    fun register(signer: Signer) {
        impls[signer.version] = signer
        if (signer.version > active) active = signer.version
    }

    fun active(): Signer? = impls[active]

    fun requireAtLeast(minVersion: Int): Boolean = active >= minVersion

    fun verify(bytes: ByteArray, expectedSha256: String): Boolean {
        val actual = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        return actual.equals(expectedSha256, ignoreCase = true)
    }
}

/** 适配器注册表：基础版默认空，目标由使用者自行注册 */
class AdapterRegistry {

    private val adapters = LinkedHashMap<String, TargetAdapter>()

    fun register(adapter: TargetAdapter) {
        adapters[adapter.id] = adapter
    }

    fun get(id: String): TargetAdapter? = adapters[id]

    fun ids(): Set<String> = adapters.keys
}
