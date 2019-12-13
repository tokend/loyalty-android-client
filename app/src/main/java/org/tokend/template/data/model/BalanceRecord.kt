package org.tokend.template.data.model

import com.fasterxml.jackson.databind.ObjectMapper
import org.tokend.sdk.api.generated.resources.ConvertedBalanceStateResource
import org.tokend.sdk.api.ingester.generated.resources.BalanceResource
import java.io.Serializable
import java.math.BigDecimal

class BalanceRecord(
        val id: String,
        val asset: AssetRecord,
        var available: BigDecimal,
        val conversionAsset: Asset?,
        var convertedAmount: BigDecimal?,
        val conversionPrice: BigDecimal?,
        val company: CompanyRecord?
) : Serializable {
    constructor(source: BalanceResource, urlConfig: UrlConfig?, mapper: ObjectMapper,
                companiesMap: Map<String, CompanyRecord>) : this(
            id = source.id,
            available = source.state.available,
            asset = AssetRecord.fromResource(source.asset, urlConfig, mapper),
            conversionAsset = null,
            convertedAmount = null,
            conversionPrice = null,
            company = companiesMap[source.asset.owner.id]
    )

    constructor(source: ConvertedBalanceStateResource,
                urlConfig: UrlConfig?,
                mapper: ObjectMapper,
                conversionAsset: Asset?,
                companiesMap: Map<String, CompanyRecord>) : this(
            id = source.balance.id,
            available = source.initialAmounts.available,
            asset = throw NotImplementedError("Converted balances are not yet supported"),
            conversionAsset = conversionAsset,
            convertedAmount =
            if (source.isConverted)
                source.convertedAmounts.available
            else
                null,
            conversionPrice =
            if (source.isConverted)
                source.price
            else
                null,
            company = companiesMap[source.balance.asset.owner.id]
    )

    val assetCode: String
        get() = asset.code

    val hasAvailableAmount: Boolean
        get() = available.signum() > 0
}