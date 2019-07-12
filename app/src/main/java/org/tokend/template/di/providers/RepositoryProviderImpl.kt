package org.tokend.template.di.providers

import android.content.Context
import android.support.v4.util.LruCache
import com.fasterxml.jackson.databind.ObjectMapper
import org.tokend.template.BuildConfig
import org.tokend.template.data.model.history.converter.DefaultParticipantEffectConverter
import org.tokend.template.data.repository.*
import org.tokend.template.data.repository.assets.AssetChartRepository
import org.tokend.template.data.repository.assets.AssetsRepository
import org.tokend.template.data.repository.balancechanges.BalanceChangesCache
import org.tokend.template.data.repository.balancechanges.BalanceChangesRepository
import org.tokend.template.data.repository.balances.BalancesRepository
import org.tokend.template.data.repository.base.MemoryOnlyRepositoryCache
import org.tokend.template.data.repository.pairs.AssetPairsRepository
import org.tokend.template.data.repository.tfa.TfaFactorsRepository
import org.tokend.template.data.repository.tradehistory.TradeHistoryRepository
import org.tokend.template.extensions.getOrPut
import org.tokend.template.features.clients.repository.CompanyClientsRepository
import org.tokend.template.features.invest.model.SaleRecord
import org.tokend.template.features.invest.repository.InvestmentInfoRepository
import org.tokend.template.features.invest.repository.SalesRepository
import org.tokend.template.features.kyc.storage.KycStateRepository
import org.tokend.template.features.kyc.storage.SubmittedKycStatePersistor
import org.tokend.template.features.offers.repository.OffersCache
import org.tokend.template.features.offers.repository.OffersRepository
import org.tokend.template.features.polls.repository.PollsCache
import org.tokend.template.features.polls.repository.PollsRepository
import org.tokend.template.features.send.recipient.repository.ContactsRepository
import org.tokend.template.features.trade.orderbook.repository.OrderBookRepository

/**
 * @param context if not specified then android-related repositories
 * will be unavailable
 */
class RepositoryProviderImpl(
        private val apiProvider: ApiProvider,
        private val walletInfoProvider: WalletInfoProvider,
        private val urlConfigProvider: UrlConfigProvider,
        private val mapper: ObjectMapper,
        private val context: Context? = null,
        private val companyInfoProvider: CompanyInfoProvider? = null,
        private val kycStatePersistor: SubmittedKycStatePersistor? = null
) : RepositoryProvider {
    private val conversionAssetCode =
            if (BuildConfig.ENABLE_BALANCES_CONVERSION)
                BuildConfig.BALANCES_CONVERSION_ASSET
            else
                null

    private val companyId: String?
        get() = companyInfoProvider?.getCompany()?.id

    private val balancesRepositories =
            LruCache<String, BalancesRepository>(MAX_SAME_REPOSITORIES_COUNT)
    private val accountDetails: AccountDetailsRepository by lazy {
        AccountDetailsRepository(apiProvider)
    }
    private val systemInfoRepository: SystemInfoRepository by lazy {
        SystemInfoRepository(apiProvider)
    }
    private val tfaFactorsRepository: TfaFactorsRepository by lazy {
        TfaFactorsRepository(apiProvider, walletInfoProvider, MemoryOnlyRepositoryCache())
    }
    private val assetsRepositories =
            LruCache<String, AssetsRepository>(MAX_SAME_REPOSITORIES_COUNT)
    private val orderBookRepositories =
            LruCache<String, OrderBookRepository>(MAX_SAME_REPOSITORIES_COUNT)
    private val assetPairsRepository: AssetPairsRepository by lazy {
        AssetPairsRepository(apiProvider, urlConfigProvider, mapper,
                conversionAssetCode, MemoryOnlyRepositoryCache())
    }
    private val offersRepositories =
            LruCache<String, OffersRepository>(MAX_SAME_REPOSITORIES_COUNT)
    private val accountRepository: AccountRepository by lazy {
        AccountRepository(apiProvider, walletInfoProvider)
    }
    private val salesRepositories =
            LruCache<String, SalesRepository>(MAX_SAME_REPOSITORIES_COUNT)
    private val filteredSalesRepositories =
            LruCache<String, SalesRepository>(MAX_SAME_REPOSITORIES_COUNT)
    private val contactsRepository: ContactsRepository by lazy {
        context ?: throw IllegalStateException("This provider has no context " +
                "required to provide contacts repository")
        ContactsRepository(context, MemoryOnlyRepositoryCache())
    }

    private val limitsRepository: LimitsRepository by lazy {
        LimitsRepository(apiProvider, walletInfoProvider)
    }

    private val feesRepository: FeesRepository by lazy {
        FeesRepository(apiProvider, walletInfoProvider)
    }

    private val companiesRepository: CompaniesRepository by lazy {
        CompaniesRepository(apiProvider, walletInfoProvider, MemoryOnlyRepositoryCache())
    }

    private val pollsRepositories =
            LruCache<String, PollsRepository>(MAX_SAME_REPOSITORIES_COUNT)

    private val balanceChangesRepositoriesByBalanceId =
            LruCache<String, BalanceChangesRepository>(MAX_SAME_REPOSITORIES_COUNT)

    private val tradesRepositoriesByAssetPair =
            LruCache<String, TradeHistoryRepository>(MAX_SAME_REPOSITORIES_COUNT)

    private val chartRepositoriesByCode =
            LruCache<String, AssetChartRepository>(MAX_SAME_REPOSITORIES_COUNT)

    private val investmentInfoRepositoriesBySaleId =
            LruCache<Long, InvestmentInfoRepository>(MAX_SAME_REPOSITORIES_COUNT)

    private val atomicSwapRepositoryByAsset =
            LruCache<String, AtomicSwapRequestsRepository>(MAX_SAME_REPOSITORIES_COUNT)

    private val companyClientsRepository: CompanyClientsRepository by lazy {
        CompanyClientsRepository(apiProvider, walletInfoProvider, MemoryOnlyRepositoryCache())
    }

    override fun balances(): BalancesRepository {
        val key = companyId.toString()
        return balancesRepositories.getOrPut(key) {
            BalancesRepository(
                    apiProvider,
                    walletInfoProvider,
                    urlConfigProvider,
                    mapper,
                    conversionAssetCode,
                    MemoryOnlyRepositoryCache()
            )
        }
    }

    override fun accountDetails(): AccountDetailsRepository {
        return accountDetails
    }

    override fun systemInfo(): SystemInfoRepository {
        return systemInfoRepository
    }

    override fun tfaFactors(): TfaFactorsRepository {
        return tfaFactorsRepository
    }

    override fun assets(): AssetsRepository {
        val key = companyId.toString()
        return assetsRepositories.getOrPut(key) {
            AssetsRepository(companyId, apiProvider, urlConfigProvider,
                    mapper, MemoryOnlyRepositoryCache())
        }
    }

    override fun assetPairs(): AssetPairsRepository {
        return assetPairsRepository
    }

    override fun orderBook(baseAsset: String,
                           quoteAsset: String): OrderBookRepository {
        val key = "$baseAsset.$quoteAsset"
        return orderBookRepositories.getOrPut(key) {
            OrderBookRepository(apiProvider, baseAsset, quoteAsset)
        }
    }

    override fun offers(onlyPrimaryMarket: Boolean): OffersRepository {
        val key = "$onlyPrimaryMarket"
        return offersRepositories.getOrPut(key) {
            OffersRepository(apiProvider, walletInfoProvider, onlyPrimaryMarket, OffersCache())
        }
    }

    private val kycStateRepository: KycStateRepository by lazy {
        KycStateRepository(apiProvider, walletInfoProvider, kycStatePersistor)
    }

    override fun account(): AccountRepository {
        return accountRepository
    }

    override fun sales(): SalesRepository {
        val key = companyId.toString()
        return salesRepositories.getOrPut(key) {
            SalesRepository(companyId, walletInfoProvider,
                    apiProvider, urlConfigProvider, mapper, MemoryOnlyRepositoryCache())
        }
    }

    override fun filteredSales(): SalesRepository {
        val key = companyId.toString()
        return filteredSalesRepositories.getOrPut(key) {
            SalesRepository(companyId, walletInfoProvider,
                    apiProvider, urlConfigProvider, mapper, MemoryOnlyRepositoryCache())
        }
    }

    override fun contacts(): ContactsRepository {
        return contactsRepository
    }

    override fun limits(): LimitsRepository {
        return limitsRepository
    }

    override fun fees(): FeesRepository {
        return feesRepository
    }

    override fun balanceChanges(balanceId: String?): BalanceChangesRepository {
        return balanceChangesRepositoriesByBalanceId.getOrPut(balanceId.toString()) {
            BalanceChangesRepository(
                    balanceId,
                    walletInfoProvider.getWalletInfo()?.accountId,
                    apiProvider,
                    DefaultParticipantEffectConverter(),
                    BalanceChangesCache()
            )
        }
    }

    override fun tradeHistory(base: String, quote: String): TradeHistoryRepository {
        return tradesRepositoriesByAssetPair.getOrPut("$base:$quote") {
            TradeHistoryRepository(
                    base,
                    quote,
                    apiProvider,
                    MemoryOnlyRepositoryCache()
            )
        }
    }

    override fun assetChart(asset: String): AssetChartRepository {
        return chartRepositoriesByCode.getOrPut(asset) {
            AssetChartRepository(
                    asset,
                    apiProvider
            )
        }
    }

    override fun assetChart(baseAsset: String, quoteAsset: String): AssetChartRepository {
        return chartRepositoriesByCode.getOrPut("$baseAsset-$quoteAsset") {
            AssetChartRepository(
                    baseAsset,
                    quoteAsset,
                    apiProvider
            )
        }
    }

    override fun kycState(): KycStateRepository {
        return kycStateRepository
    }

    override fun investmentInfo(sale: SaleRecord): InvestmentInfoRepository {
        return investmentInfoRepositoriesBySaleId.getOrPut(sale.id) {
            InvestmentInfoRepository(sale, offers(), sales())
        }
    }

    override fun polls(): PollsRepository {
        val key = companyId.toString()
        return pollsRepositories.getOrPut(key) {
            PollsRepository(companyId, apiProvider, walletInfoProvider, PollsCache())
        }
    }

    override fun atomicSwapAsks(asset: String): AtomicSwapRequestsRepository {
        return atomicSwapRepositoryByAsset.getOrPut(asset) {
            AtomicSwapRequestsRepository(apiProvider, asset,
                    MemoryOnlyRepositoryCache())
        }
    }

    override fun companies(): CompaniesRepository {
        return companiesRepository
    }

    override fun companyClients(): CompanyClientsRepository {
        return companyClientsRepository
    }

    companion object {
        private const val MAX_SAME_REPOSITORIES_COUNT = 10
    }
}