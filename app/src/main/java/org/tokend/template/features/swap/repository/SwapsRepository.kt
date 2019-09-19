package org.tokend.template.features.swap.repository

import com.fasterxml.jackson.databind.ObjectMapper
import io.reactivex.Maybe
import io.reactivex.Single
import io.reactivex.rxkotlin.toMaybe
import org.tokend.rx.extensions.toSingle
import org.tokend.sdk.api.base.params.PagingParamsV2
import org.tokend.sdk.api.generated.resources.SwapResource
import org.tokend.sdk.api.v3.swaps.params.SwapParams
import org.tokend.sdk.api.v3.swaps.params.SwapsPageParams
import org.tokend.sdk.utils.SimplePagedResourceLoader
import org.tokend.sdk.utils.extentions.decodeHex
import org.tokend.template.data.repository.assets.AssetsRepository
import org.tokend.template.data.repository.base.RepositoryCache
import org.tokend.template.data.repository.base.SimpleMultipleItemsRepository
import org.tokend.template.di.providers.ApiProvider
import org.tokend.template.di.providers.UrlConfigProvider
import org.tokend.template.di.providers.WalletInfoProvider
import org.tokend.template.extensions.mapSuccessful
import org.tokend.template.features.swap.model.SwapRecord
import org.tokend.template.features.swap.model.SwapState
import org.tokend.template.features.swap.persistence.SwapSecretsPersistor

class SwapsRepository(
        private val apiProvider: ApiProvider,
        private val urlConfigProvider: UrlConfigProvider,
        private val walletInfoProvider: WalletInfoProvider,
        private val objectMapper: ObjectMapper,
        private val secretsPersistor: SwapSecretsPersistor,
        private val assetsRepository: AssetsRepository,
        itemsCache: RepositoryCache<SwapRecord>
) : SimpleMultipleItemsRepository<SwapRecord>(itemsCache) {
    private var sourceSystemIndex: Int? = null

    override fun getItems(): Single<List<SwapRecord>> {
        val accountId = walletInfoProvider.getWalletInfo()?.accountId
                ?: return Single.error(IllegalStateException("No wallet info found"))

        return getSourceSystemIndex()
                .flatMap { sourceSystemIndex ->
                    getAllSwaps(sourceSystemIndex, accountId)
                }
                .map { allSwaps ->
                    allSwaps.groupBy(SwapResource::getSecretHash)
                            .mapValues { it.value.sortedBy(SwapResource::getCreatedAt) }
                }
                .map { swapsByHash ->
                    swapsByHash.entries.mapSuccessful { (_, connectedSwaps) ->
                        val initialSwap = connectedSwaps.first()
                        if (initialSwap.source.id == accountId)
                            getRecordFromSourceSwap(initialSwap, connectedSwaps)
                        else
                            getRecordFromDestSwap(initialSwap, connectedSwaps)
                    }
                }
                .flatMap(this::loadAndSetAssets)
    }

    private fun getSourceSystemIndex(): Single<Int> {
        val email = walletInfoProvider.getWalletInfo()?.email
                ?: return Single.error(IllegalStateException("No wallet info found"))

        val obtainSystemIndex = Single.defer {
            (0 until urlConfigProvider.getConfigsCount())
                    .map { i ->
                        apiProvider.getKeyServer(i)
                                .getLoginParams(email)
                                .toSingle()
                                .toMaybe()
                                .onErrorComplete()
                                .map { i }
                    }
                    .let { Maybe.merge(it) }
                    .firstOrError()
                    .doOnSuccess { index ->
                        sourceSystemIndex = index
                    }
        }

        return sourceSystemIndex
                .toMaybe()
                .switchIfEmpty(obtainSystemIndex)
    }

    private fun getAllSwaps(sourceSystemIndex: Int,
                            accountId: String): Single<List<SwapResource>> {
        val sourceSwaps = getSourceSwaps(sourceSystemIndex, accountId)
        val destSwaps = getDestSwaps(sourceSystemIndex, accountId)

        return Single
                .merge(sourceSwaps, destSwaps)
                .collect<MutableList<List<SwapResource>>>(
                        { mutableListOf() },
                        { a, b -> a.add(b) }
                )
                .map { it.flatten() }
    }

    private fun getSourceSwaps(sourceSystemIndex: Int,
                               accountId: String): Single<List<SwapResource>> {
        val signedApi = apiProvider.getSignedApi(sourceSystemIndex)
                ?: return Single.error(IllegalStateException("No signed API found for system $sourceSystemIndex"))

        val loader = SimplePagedResourceLoader({ nextCursor ->
            signedApi.v3.swaps
                    .get(SwapsPageParams(
                            source = accountId,
                            include = listOf(SwapParams.Includes.ASSET),
                            pagingParams = PagingParamsV2(
                                    page = nextCursor,
                                    limit = 20
                            )
                    ))
        })

        return loader.loadAll().toSingle()
    }

    private fun getDestSwaps(sourceSystemIndex: Int,
                             accountId: String): Single<List<SwapResource>> {
        return (0 until urlConfigProvider.getConfigsCount())
                .toMutableList()
                .apply { remove(sourceSystemIndex) }
                .takeIf { it.isNotEmpty() }
                ?.map { getSystemDestSwaps(it, accountId) }
                ?.let { Single.merge(it) }
                ?.collect<MutableList<List<SwapResource>>>(
                        { mutableListOf() },
                        { a, b -> a.add(b) }
                )
                ?.map { it.flatten() }
                ?: Single.just(emptyList())
    }

    private fun getSystemDestSwaps(index: Int,
                                   accountId: String): Single<List<SwapResource>> {
        val signedApi = apiProvider.getSignedApi(index)
                ?: return Single.error(IllegalStateException("No signed API found for system $index"))

        val loader = SimplePagedResourceLoader({ nextCursor ->
            signedApi.v3.swaps
                    .get(SwapsPageParams(
                            destination = accountId,
                            include = listOf(SwapParams.Includes.ASSET),
                            pagingParams = PagingParamsV2(
                                    page = nextCursor,
                                    limit = 20
                            )
                    ))
        })

        return loader.loadAll().toSingle()
    }

    private fun getRecordFromSourceSwap(swapResource: SwapResource,
                                        connectedSwaps: List<SwapResource>): SwapRecord {
        val hash = swapResource.secretHash
        val systemIndex = sourceSystemIndex!!
        val remoteState =
                org.tokend.sdk.api.v3.swaps.model.SwapState.fromValue(swapResource.state.value)

        val secret = secretsPersistor.loadSecret(hash)
        var destId: String? = null

        val state = when {
            remoteState == org.tokend.sdk.api.v3.swaps.model.SwapState.CANCELED ->
                SwapState.CANCELED
            connectedSwaps.size == 1 ->
                SwapState.CREATED
            connectedSwaps.size == 2 -> {
                val swapByDest = connectedSwaps.first { it.source.id == swapResource.destination.id }
                destId = swapByDest.id

                when (org.tokend.sdk.api.v3.swaps.model.SwapState.fromValue(swapByDest.state.value)) {
                    org.tokend.sdk.api.v3.swaps.model.SwapState.OPEN ->
                        SwapState.WAITING_FOR_CLOSE_BY_SOURCE
                    org.tokend.sdk.api.v3.swaps.model.SwapState.CLOSED ->
                        SwapState.COMPLETED
                    org.tokend.sdk.api.v3.swaps.model.SwapState.CANCELED ->
                        SwapState.CANCELED_BY_COUNTERPARTY
                }
            }
            else -> throw IllegalStateException("Unable to define state of swap $hash")
        }

        return SwapRecord.fromResource(swapResource, secret, state,
                false, objectMapper, systemIndex, destId)
    }

    private fun getRecordFromDestSwap(swapBySource: SwapResource,
                                      connectedSwaps: List<SwapResource>): SwapRecord {
        val hash = swapBySource.secretHash
        val systemIndex = (0 until urlConfigProvider.getConfigsCount())
                .first { it != sourceSystemIndex }
        val sourceSwapState =
                org.tokend.sdk.api.v3.swaps.model.SwapState.fromValue(swapBySource.state.value)

        var secret: ByteArray? = null

        val state = when {
            sourceSwapState == org.tokend.sdk.api.v3.swaps.model.SwapState.CANCELED ->
                SwapState.CANCELED
            connectedSwaps.size == 1 ->
                SwapState.CREATED
            connectedSwaps.size == 2 -> {
                val ourSwap = connectedSwaps.first { it.destination.id == swapBySource.source.id }

                secret = ourSwap.secret?.decodeHex()

                when (org.tokend.sdk.api.v3.swaps.model.SwapState.fromValue(ourSwap.state.value)) {
                    org.tokend.sdk.api.v3.swaps.model.SwapState.OPEN ->
                        SwapState.WAITING_FOR_CLOSE_BY_SOURCE
                    org.tokend.sdk.api.v3.swaps.model.SwapState.CLOSED -> {
                        if (sourceSwapState == org.tokend.sdk.api.v3.swaps.model.SwapState.OPEN)
                            SwapState.CAN_BE_RECEIVED_BY_DEST
                        else
                            SwapState.COMPLETED
                    }
                    else -> throw IllegalStateException("Unable to define state of swap $hash")
                }
            }
            else -> throw IllegalStateException("Unable to define state of swap $hash")
        }

        return SwapRecord.fromResource(swapBySource, secret, state,
                true, objectMapper, systemIndex, null)
    }

    private fun loadAndSetAssets(items: List<SwapRecord>): Single<List<SwapRecord>> {
        val codes = items
                .map { listOf(it.quoteAsset.code, it.baseAsset.code) }
                .flatten()
                .distinct()

        return assetsRepository.ensureAssets(codes)
                .map { assetsMap ->
                    items.apply {
                        forEach { swap ->
                            swap.quoteAsset = assetsMap.getValue(swap.quoteAsset.code)
                            swap.baseAsset = assetsMap.getValue(swap.baseAsset.code)
                        }
                    }
                }
    }
}