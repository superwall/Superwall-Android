import com.superwall.sdk.dependencies.ApiFactory
import com.superwall.sdk.models.geo.GeoWrapper
import com.superwall.sdk.network.session.CustomHttpUrlConnection
import com.superwall.sdk.network.session.NetworkService

class GeoService(
    override val host: String,
    override val version: String,
    val factory: ApiFactory,
    override val customHttpUrlConnection: CustomHttpUrlConnection,
) : NetworkService() {
    override suspend fun makeHeaders(
        isForDebugging: Boolean,
        requestId: String,
    ): Map<String, String> = factory.makeHeaders(isForDebugging, requestId)

    suspend fun geo() = get<GeoWrapper>("geo")
}
