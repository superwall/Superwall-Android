import android.graphics.Color
import org.json.JSONObject
import java.net.URL

data class Paywalls(val paywalls: List<Paywall>)

data class Paywall(
    val databaseId: String,
    val identifier: String,
    val name: String,
    val url: URL,
    val htmlSubstitutions: String,
//    val presentation: Presentation,
//    val presentationStyle: PaywallPresentationStyle,
//    val presentationCondition: PresentationCondition,
    val backgroundColorHex: String,
//    val products: List<Product>,
//    val featureGating: FeatureGatingBehavior = FeatureGatingBehavior.NON_GATED
) {
    val backgroundColor: Int
        get() = Color.parseColor(backgroundColorHex)

//    val productIds: List<String>
//        get() = products.map { it.id }

    companion object {
        fun fromJson(json: JSONObject): Paywall {
            val databaseId = json.getString("id")
            val identifier = json.getString("identifier")
            val name = json.getString("name")
            val url = URL(json.getString("url"))
            val htmlSubstitutions = json.getString("paywalljs_event")
//            val presentationStyle = PaywallPresentationStyle.valueOf(json.getString("presentationStyleV2"))
//            val presentationCondition = PresentationCondition.valueOf(json.getString("presentationCondition"))
            val backgroundColorHex = json.getString("background_color_hex")
//            val productsJsonArray = json.getJSONArray("products")
//            val products = mutableListOf<Product>()
//            for (i in 0 until productsJsonArray.length()) {
//                products.add(Product.fromJson(productsJsonArray.getJSONObject(i)))
//            }
//            val featureGating = FeatureGatingBehavior.valueOf(json.optString("featureGating", "NON_GATED"))
//            val presentation = Presentation(presentationStyle, presentationCondition)

            return Paywall(
                databaseId,
                identifier,
                name,
                url,
                htmlSubstitutions,
//                presentation,
//                presentationStyle,
//                presentationCondition,
                backgroundColorHex,
//                products,
//                featureGating
            )
        }
    }
}

