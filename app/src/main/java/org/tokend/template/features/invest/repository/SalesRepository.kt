package org.tokend.template.features.invest.repository

import com.fasterxml.jackson.databind.ObjectMapper
import io.reactivex.Single
import org.tokend.rx.extensions.toSingle
import org.tokend.sdk.api.base.model.DataPage
import org.tokend.sdk.api.base.params.PagingOrder
import org.tokend.sdk.api.base.params.PagingParamsV2
import org.tokend.sdk.api.sales.model.SaleState
import org.tokend.sdk.api.v3.sales.params.SaleParamsV3
import org.tokend.sdk.api.v3.sales.params.SalesPageParamsV3
import org.tokend.template.data.repository.base.RepositoryCache
import org.tokend.template.data.repository.base.pagination.PagedDataRepository
import org.tokend.template.di.providers.ApiProvider
import org.tokend.template.di.providers.UrlConfigProvider
import org.tokend.template.di.providers.WalletInfoProvider
import org.tokend.template.extensions.mapSuccessful
import org.tokend.template.features.invest.model.SaleRecord

class SalesRepository(
        private val walletInfoProvider: WalletInfoProvider,
        private val apiProvider: ApiProvider,
        private val urlConfigProvider: UrlConfigProvider,
        private val mapper: ObjectMapper,
        itemsCache: RepositoryCache<SaleRecord>
) : PagedDataRepository<SaleRecord>(itemsCache) {

    private var baseAsset: String? = null

    override fun getPage(nextCursor: String?): Single<DataPage<SaleRecord>> {
        val accountId = walletInfoProvider.getWalletInfo()?.accountId
                ?: return Single.error(IllegalStateException("No wallet info found"))

        val signedApi = apiProvider.getSignedApi()
                ?: return Single.error(IllegalStateException("No signed API instance found"))

        val requestParams = SalesPageParamsV3(
                pagingParams = PagingParamsV2(
                        page = nextCursor,
                        order = PagingOrder.DESC,
                        limit = DEFAULT_LIMIT
                ),
                state = SaleState.OPEN,
                baseAsset = baseAsset,
                includes = listOf(
                        SaleParamsV3.Includes.BASE_ASSET,
                        SaleParamsV3.Includes.QUOTE_ASSET,
                        SaleParamsV3.Includes.DEFAULT_QUOTE_ASSET
                )
        )

        return signedApi
                .v3
                .sales
                .getForAccount(accountId, requestParams)
                .toSingle()
                .map { page ->
                    DataPage(
                            page.nextCursor,
                            page.items.mapSuccessful {
                                SaleRecord.fromResource(it, urlConfigProvider.getConfig(), mapper)
                            },
                            page.isLast
                    )
                }
    }

    fun getSingle(id: Long): Single<SaleRecord> {
        val accountId = walletInfoProvider.getWalletInfo()?.accountId
                ?: return Single.error(IllegalStateException("No wallet info found"))

        val signedApi = apiProvider.getSignedApi()
                ?: return Single.error(IllegalStateException("No signed API instance found"))

        val params = SaleParamsV3(
                include = listOf(
                        SaleParamsV3.Includes.BASE_ASSET,
                        SaleParamsV3.Includes.QUOTE_ASSET,
                        SaleParamsV3.Includes.DEFAULT_QUOTE_ASSET
                )
        )

        return signedApi
                .v3
                .sales
                .getByIdForAccount(accountId, id.toString(), params)
                .toSingle()
                .map { SaleRecord.fromResource(it, urlConfigProvider.getConfig(), mapper) }
    }

    fun forQuery(baseAsset: String? = null): SalesRepository {
        this.baseAsset = baseAsset
        return this
    }

    companion object {
        private const val DEFAULT_LIMIT = 10
    }
}