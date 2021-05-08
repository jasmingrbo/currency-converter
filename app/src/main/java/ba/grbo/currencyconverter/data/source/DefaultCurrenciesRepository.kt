package ba.grbo.currencyconverter.data.source

import android.content.Context
import ba.grbo.currencyconverter.data.models.database.EssentialExchangeRate
import ba.grbo.currencyconverter.data.models.database.ExchangeRate
import ba.grbo.currencyconverter.data.models.database.ExchangeableCurrency
import ba.grbo.currencyconverter.data.models.database.MultiExchangeableCurrency
import ba.grbo.currencyconverter.di.DispatcherDefault
import ba.grbo.currencyconverter.util.SharedStateLikeFlow
import ba.grbo.currencyconverter.util.toDomain
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import java.util.*
import javax.inject.Inject
import ba.grbo.currencyconverter.data.models.domain.ExchangeableCurrency as DomainCurrency

class DefaultCurrenciesRepository @Inject constructor(
    private val localCurrenciesSource: LocalCurrenciesSource,
    @DispatcherDefault private val coroutineDispatcher: CoroutineDispatcher,
    @ApplicationContext context: Context,
) : CurrenciesRepository {
    override val exchangeableCurrencies = MutableStateFlow<List<DomainCurrency>>(emptyList())
    override val exception: MutableSharedFlow<Exception> = SharedStateLikeFlow()

    init {
        runBlocking {
            when (val dbResult = getExchangeableCurrencies()) {
                is Success -> exchangeableCurrencies.value = dbResult.data.toDomain(context)
                is Error -> exception.tryEmit(dbResult.exception)
            }
        }
    }

    override fun observeCurrencies(scope: CoroutineScope) {
        observeForFavoritesChange(scope)
        observeForExchangeRatesChange(scope)
    }

    private fun observeForFavoritesChange(scope: CoroutineScope) {
        observeForChange(
            localCurrenciesSource::observeAreUnexchangeableCurrenciesFavorite,
            ::updateFavorites,
            scope
        )
    }

    private fun observeForExchangeRatesChange(scope: CoroutineScope) {
        observeForChange(
            localCurrenciesSource::observeMostRecentExchangeRates,
            ::updateExchangeRates,
            scope
        )
    }

    private fun <T> observeForChange(
        dbAction: () -> DatabaseResult<Flow<List<T>>>,
        onEach: (List<T>, CoroutineScope) -> Unit,
        scope: CoroutineScope
    ) {
        when (val dbResult = dbAction()) {
            is Success -> dbResult.data.collect(scope, onEach)
            is Error -> exception.tryEmit(dbResult.exception)
        }
    }

    private fun updateFavorites(list: List<Boolean>, scope: CoroutineScope) {
        list.forEachIndexed { index, isFavorite ->
            if (scope.isActive) exchangeableCurrencies.value[index].isFavorite = isFavorite
        }
    }

    private fun updateExchangeRates(
        exchangeRates: List<EssentialExchangeRate>,
        scope: CoroutineScope
    ) {
        exchangeRates.forEachIndexed { index, essentialExchangeRate ->
            if (scope.isActive) {
                exchangeableCurrencies.value[index].exchangeRate = essentialExchangeRate
            }
        }
    }

    private fun <T> Flow<T>.collect(
        scope: CoroutineScope,
        onEach: (T, CoroutineScope) -> Unit
    ) {
        distinctUntilChanged()
            .onEach { onEach(it, scope) }
            .flowOn(coroutineDispatcher)
            .launchIn(scope)
    }

    override suspend fun insertExchangeRate(exchangeRate: ExchangeRate): DatabaseResult<Boolean> {
        return localCurrenciesSource.insertExchangeRate(exchangeRate)
    }

    override fun observeMostRecentExchangeRates(): DatabaseResult<Flow<List<EssentialExchangeRate>>> {
        return localCurrenciesSource.observeMostRecentExchangeRates()
    }

    override suspend fun updateCurrency(currency: DomainCurrency): DatabaseResult<Boolean> {
        return localCurrenciesSource.updateUnexchangeableCurrency(currency.toDatabase())
    }

    override suspend fun getMultiExchangeableCurrency(
        code: String,
        fromDate: Date,
        toDate: Date
    ): DatabaseResult<MultiExchangeableCurrency> {
        return localCurrenciesSource.getMultiExchangeableCurrency(code, fromDate, toDate)
    }

    override suspend fun getExchangeableCurrencies(): DatabaseResult<List<ExchangeableCurrency>> {
        // return Error(Exception("bacili smo je"))
        return localCurrenciesSource.getExchangeableCurrencies()
    }

    override fun observeAreUnexchangeableCurrenciesFavorite(): DatabaseResult<Flow<List<Boolean>>> {
        return localCurrenciesSource.observeAreUnexchangeableCurrenciesFavorite()
    }
}