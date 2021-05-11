package ba.grbo.currencyconverter.ui.viewmodels

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ba.grbo.currencyconverter.R
import ba.grbo.currencyconverter.data.models.domain.ExchangeableCurrency
import ba.grbo.currencyconverter.data.models.domain.Miscellaneous
import ba.grbo.currencyconverter.data.source.CurrenciesRepository
import ba.grbo.currencyconverter.ui.viewmodels.ConverterViewModel.Dropdown.*
import ba.grbo.currencyconverter.ui.viewmodels.ConverterViewModel.DropdownState.*
import ba.grbo.currencyconverter.ui.viewmodels.ConverterViewModel.SearcherState.Unfocused
import ba.grbo.currencyconverter.ui.viewmodels.ConverterViewModel.SearcherState.Unfocusing
import ba.grbo.currencyconverter.util.FilterBy
import ba.grbo.currencyconverter.util.SharedStateLikeFlow
import ba.grbo.currencyconverter.util.SingleSharedFlow
import ba.grbo.currencyconverter.util.toSearcherState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConverterViewModel @Inject constructor(
    private val repository: CurrenciesRepository,
    filterBy: FilterBy,
) : ViewModel() {
    private lateinit var currencies: StateFlow<List<ExchangeableCurrency>>

    private val _databaseExceptionCaught =
        SharedStateLikeFlow<Triple<@StringRes Int, @StringRes Int, @StringRes Int>>()
    val databaseExceptionCaught: SharedFlow<Triple<Int, Int, Int>>
        get() = _databaseExceptionCaught

    private val _databaseExceptionAcknowledged = SharedStateLikeFlow<Unit>()
    val databaseExceptionAcknowledged: SharedFlow<Unit>
        get() = _databaseExceptionAcknowledged

    private lateinit var _countries: MutableStateFlow<MutableList<ExchangeableCurrency>>
    val countries: StateFlow<List<ExchangeableCurrency>?>
        get() = if (::_countries.isInitialized) _countries else MutableStateFlow(null)

    private lateinit var _fromCurrency: MutableStateFlow<ExchangeableCurrency>
    val fromCurrency: StateFlow<ExchangeableCurrency?>
        get() = if (::_fromCurrency.isInitialized) _fromCurrency else MutableStateFlow(null)

    private lateinit var _toCurrency: MutableStateFlow<ExchangeableCurrency>
    val toCurrency: StateFlow<ExchangeableCurrency?>
        get() = if (::_toCurrency.isInitialized) _toCurrency else MutableStateFlow(null)

    private var _showOnlyFavorites = false
    val showOnlyFavorites: Boolean
        get() = _showOnlyFavorites

    init {
        repository.exception
            .onEach {
                it?.let {
                    _databaseExceptionCaught.tryEmit(
                        Triple(
                            R.string.database_exception_caught_title,
                            R.string.database_exception_caught_message,
                            R.string.database_exception_caught_button
                        )
                    )
                }
            }
            .launchIn(viewModelScope)

        if (repository.converterDataIsReady()) {
            currencies = repository.exchangeableCurrencies
            _countries = MutableStateFlow(currencies.value.toMutableList())
            _fromCurrency =
                MutableStateFlow(repository.miscellaneous.lastUsedFromExchangeableCurrency)
            _toCurrency = MutableStateFlow(repository.miscellaneous.lastUsedToExchangeableCurrency)
            if (repository.miscellaneous.showOnlyFavorites) {
                _showOnlyFavorites = true
                filterCurrentCountries()
            }
            repository.observeCurrencies(viewModelScope)
        }
    }

    private val _dropdownState = MutableStateFlow<DropdownState>(Collapsed(NONE))
    val dropdownState: StateFlow<DropdownState>
        get() = _dropdownState

    private val _fromSelectedCurrency = SingleSharedFlow<ExchangeableCurrency>()
    val fromSelectedCurrency: SharedFlow<ExchangeableCurrency>
        get() = _fromSelectedCurrency

    private val _toSelectedCurrency = SingleSharedFlow<ExchangeableCurrency>()
    val toSelectedCurrency: SharedFlow<ExchangeableCurrency>
        get() = _toSelectedCurrency

    private val _searcherState = MutableStateFlow<SearcherState>(Unfocused(NONE))
    val searcherState: StateFlow<SearcherState>
        get() = _searcherState

    private val _modifyDivider = MutableStateFlow(true)
    val modifyDivider: StateFlow<Boolean>
        get() = _modifyDivider

    private val _showResetButton = SharedStateLikeFlow<Boolean>()
    val showResetButton: SharedFlow<Boolean>
        get() = _showResetButton

    private val _resetSearcher = SingleSharedFlow<Unit>()
    val resetSearcher: SharedFlow<Unit>
        get() = _resetSearcher

    private val _swappingState = MutableStateFlow(SwappingState.None)
    val swappingState: SharedFlow<SwappingState>
        get() = _swappingState

    private val _scrollCurrenciesToTop = SingleSharedFlow<Unit>()
    val scrollCurrenciesToTop: SharedFlow<Unit>
        get() = _scrollCurrenciesToTop

    private val _onFavoritesClicked = SingleSharedFlow<Boolean>()
    val onFavoritesClicked: SharedFlow<Boolean>
        get() = _onFavoritesClicked

    private val _notifyItemChanged = SingleSharedFlow<Int>()
    val notifyItemChanged: SharedFlow<Int>
        get() = _notifyItemChanged

    private val _notifyItemRemoved = SingleSharedFlow<Int>()
    val notifyItemRemoved: SharedFlow<Int>
        get() = _notifyItemRemoved

    private val onSearcherTextChanged: MutableStateFlow<String?> = MutableStateFlow(null)

    private val filter: (ExchangeableCurrency, String) -> Boolean

    private var lastClickedDropdown = NONE
    private var initialSwappingState = SwappingState.None

    companion object {
        const val DEBOUNCE_PERIOD = 250L
    }

    sealed class DropdownState {
        abstract val dropdown: Dropdown

        class Expanding(override val dropdown: Dropdown) : DropdownState()
        class Collapsing(override val dropdown: Dropdown) : DropdownState()
        data class Collapsed(override val dropdown: Dropdown) : DropdownState()
    }

    sealed class SearcherState {
        abstract val dropdown: Dropdown

        class Focusing(override val dropdown: Dropdown) : SearcherState()
        class Unfocusing(override val dropdown: Dropdown) : SearcherState()
        data class Unfocused(override val dropdown: Dropdown) : SearcherState()
    }

    enum class SwappingState {
        Swapping,
        SwappingInterrupted,
        Swapped,
        SwappedWithInterruption,
        ReverseSwapping,
        ReverseSwappingInterrupted,
        ReverseSwapped,
        ReverseSwappedWithInterruption,
        None
    }

    enum class Dropdown {
        FROM,
        TO,
        NONE
    }

    init {
        filter = initializeFilter(filterBy)
        viewModelScope.launch(Dispatchers.Default) {
            onSearcherTextChanged
                .collectLatest {
                    it?.let {
                        if (it.isEmpty()) resetCountries()
                        else {
                            setResetButton(true)
                            delay(DEBOUNCE_PERIOD)
                            filterCountries(it)
                        }
                    }
                }
        }
    }

    private fun initializeFilter(
        filterBy: FilterBy
    ): (ExchangeableCurrency, String) -> Boolean = when (filterBy) {
        FilterBy.CODE -> { currency, query -> currency.name.code.contains(query, true) }
        FilterBy.NAME -> { currency, query -> currency.name.name.contains(query, true) }
        FilterBy.BOTH -> { currency, query -> currency.name.codeAndName.contains(query, true) }
    }

    fun onDatabaseExceptionAcknowledged() {
        _databaseExceptionAcknowledged.tryEmit(Unit)
    }

    fun onFromDropdownActionClicked() {
        mutateDropdown(FROM)
    }

    fun onFromDropdownClicked() {
        onFromDropdownActionClicked() // Delegate
    }

    fun onFromTitleClicked() {
        onFromDropdownActionClicked() // Delegate
    }

    fun onToDropdownActionClicked() {
        mutateDropdown(TO)
    }

    fun onToDropdownClicked() {
        mutateDropdown(TO)
    }

    fun onToTitleClicked() {
        mutateDropdown(TO)
    }

    fun onDropdownSwapperClicked() {
        _swappingState.value = when (_swappingState.value) {
            SwappingState.None,
            SwappingState.SwappedWithInterruption,
            SwappingState.ReverseSwapped -> {
                initialSwappingState = SwappingState.Swapping
                SwappingState.Swapping
            }
            SwappingState.ReverseSwappedWithInterruption,
            SwappingState.Swapped -> {
                initialSwappingState = SwappingState.ReverseSwapping
                SwappingState.ReverseSwapping
            }
            SwappingState.Swapping,
            SwappingState.ReverseSwappingInterrupted -> SwappingState.SwappingInterrupted
            SwappingState.ReverseSwapping,
            SwappingState.SwappingInterrupted -> SwappingState.ReverseSwappingInterrupted
        }
    }

    fun onSwapped() {
        _swappingState.value = SwappingState.Swapped
    }

    fun onReverseSwapped() {
        _swappingState.value = SwappingState.ReverseSwapped
    }

    fun onSwappingInterrupted() {
        _swappingState.value = SwappingState.SwappedWithInterruption
    }

    fun onReverseSwappingInterrupted() {
        _swappingState.value = SwappingState.ReverseSwappedWithInterruption
    }

    fun onSwappingCompleted() {
        if (initialSwappingState == SwappingState.Swapping) swapSelectedCurrencies()
    }

    fun onReverseSwappingCompleted() {
        if (initialSwappingState == SwappingState.ReverseSwapping) swapSelectedCurrencies()
    }

    fun resetInternalState() {
        _swappingState.value = SwappingState.None
    }

    fun wasLastClickedTo() = lastClickedDropdown == TO

    private fun swapSelectedCurrencies() {
        val fromCountry = _fromCurrency.value
        val toCountry = _toCurrency.value

        viewModelScope.launch {
            launch { _fromCurrency.value = toCountry }
            launch { _toCurrency.value = fromCountry }
        }

        updateMiscellaneous(toCountry, fromCountry)
    }

    fun onResetSearcherClicked() {
        _resetSearcher.tryEmit(Unit)
    }

    fun onFavoritesClicked() {
        _onFavoritesClicked.tryEmit(!showOnlyFavorites)
    }

    fun onFavoritesAnimationEnd(isFavoritesOn: Boolean) {
        _showOnlyFavorites = isFavoritesOn
        if (isFavoritesOn) filterCurrentCountries() // Since they're already filtered
        else filterCountries(onSearcherTextChanged.value)

        updateMiscellaneous()
    }

    private fun filterCurrentCountries() {
        _countries.value = _countries.value.filter { it.isFavorite }.toMutableList()
    }

    fun onFavoritesAnimationEnd(
        currency: ExchangeableCurrency,
        isFavoritesOn: Boolean,
        position: Int
    ) {
        if (currency.isFavorite != isFavoritesOn) {
            val updatedExchangeableCurrency = currency.copy(isFavorite = isFavoritesOn)
            updateUnexchangeableCurrency(updatedExchangeableCurrency)
            updateCountries(updatedExchangeableCurrency, isFavoritesOn, position)
        }
    }

    private fun notifyItemChanged(position: Int) {
        _notifyItemChanged.tryEmit(position)
    }

    private fun removeUnfavoritedCountry(index: Int, position: Int) {
        _countries.value.removeAt(index)
        notifyItemRemoved(position)
    }

    private fun notifyItemRemoved(position: Int) {
        _notifyItemRemoved.tryEmit(position)
    }

    private fun updateCountries(
        updatedExchangeableCurrency: ExchangeableCurrency,
        isFavoritesOn: Boolean,
        position: Int
    ) {
        val index = _countries.value.indexOfFirst { it.code == updatedExchangeableCurrency.code }
        if (showOnlyFavorites && !isFavoritesOn) removeUnfavoritedCountry(index, position)
        else updateCountries(
            updatedExchangeableCurrency,
            index,
            position
        )
    }

    private fun updateUnexchangeableCurrency(currency: ExchangeableCurrency) {
        viewModelScope.launch { repository.updateCurrency(currency) }
    }

    private fun updateCountries(currency: ExchangeableCurrency, index: Int, position: Int) {
        _countries.value[index] = currency
        notifyItemChanged(position)
    }

    fun onCurrencyClicked(currency: ExchangeableCurrency) {
        updateSelectedCurrency(currency)
        collapseDropdown(_dropdownState.value.dropdown)
    }

    fun onScreenTouched(
        dropdown: Dropdown,
        currencyLayoutTouched: Boolean,
        dropdownTitleTouched: Boolean,
        currenciesCardTouched: Boolean
    ): Boolean {
        val shouldCollapse =
            !currencyLayoutTouched && !dropdownTitleTouched && !currenciesCardTouched
        return shouldCollapse.also { if (it) _dropdownState.value = Collapsing(dropdown) }
    }

    fun onScreenTouched(
        dropdown: Dropdown,
        currencyLayoutTouched: Boolean,
        dropdownTitleTouched: Boolean,
        currenciesCardTouched: Boolean,
        currenciesSearcherTouched: Boolean
    ): Boolean {
        val shouldUnfocus = currenciesCardTouched && !currenciesSearcherTouched
        return if (shouldUnfocus) {
            _searcherState.value = Unfocusing(dropdown)
            false
        } else {
            val shouldCollapse =
                !currencyLayoutTouched && !dropdownTitleTouched && !currenciesCardTouched
            shouldCollapse.also { if (it) _dropdownState.value = Collapsing(dropdown) }
        }
    }

    private fun updateSelectedCurrency(currency: ExchangeableCurrency) {
        if (_dropdownState.value.dropdown == FROM) {
            _fromSelectedCurrency.tryEmit(currency)
            _fromCurrency.value = currency
        } else {
            _toSelectedCurrency.tryEmit(currency)
            _toCurrency.value = currency
        }

        updateMiscellaneous(_fromCurrency.value, _toCurrency.value)
    }

    private fun updateMiscellaneous(
        fromCurrency: ExchangeableCurrency,
        toCurrency: ExchangeableCurrency
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateMiscellaneous(
                Miscellaneous(
                    showOnlyFavorites,
                    fromCurrency,
                    toCurrency
                ).toDatabase(currencies.value)
            )
        }
    }

    private fun updateMiscellaneous() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateMiscellaneous(
                Miscellaneous(
                    _showOnlyFavorites,
                    _fromCurrency.value,
                    _toCurrency.value
                ).toDatabase(currencies.value)
            )
        }
    }

    fun onDropdownCollapsed() {
        _dropdownState.value = Collapsed(NONE)
    }

    fun onDropdownExpanded(dropdown: Dropdown) {
        lastClickedDropdown = dropdown
    }

    fun onSearcherFocusChanged(focused: Boolean) {
        _searcherState.value = focused.toSearcherState(_dropdownState.value.dropdown)
    }

    fun onSearcherUnfocused(dropdown: Dropdown) {
        _searcherState.value = Unfocused(dropdown)
    }

    fun onSearcherTextChanged(query: String) {
        onSearcherTextChanged.value = query
    }

    private fun resetCountries() {
        filterCountries("")
        setResetButton(false)
    }

    fun onCountriesScrolled(topReached: Boolean) {
        _modifyDivider.value = topReached
    }

    fun onCountriesChanged() {
        _scrollCurrenciesToTop.tryEmit(Unit)
    }

    fun shouldModifyCurrenciesCardPosition(
        dropdown: Dropdown,
        landscapeModificator: Boolean
    ): Boolean {
        return !landscapeModificator && lastClickedDropdown != dropdown
    }

    private fun setResetButton(hasText: Boolean) {
        _showResetButton.tryEmit(hasText)
    }

    private fun filterCountries(query: String?) {
        if (!showOnlyFavorites) filterAllCountries(query ?: "")
        else filterFavoriteCountries(query ?: "")
    }

    private fun filterAllCountries(query: String) {
        _countries.value = if (query.isEmpty()) currencies.value.toMutableList()
        else currencies.value.filter { filter(it, query) }.toMutableList()

    }

    private fun filterFavoriteCountries(query: String) {
        _countries.value =
            if (query.isEmpty()) currencies.value.filter { it.isFavorite }.toMutableList()
            else currencies.value.filter { it.isFavorite && filter(it, query) }.toMutableList()
    }

    private fun mutateDropdown(dropdown: Dropdown) {
        if (isDropdownCollapsed()) expandDropdown(dropdown) else collapseDropdown(dropdown)
    }

    private fun expandDropdown(dropdown: Dropdown) {
        _dropdownState.value = Expanding(dropdown)
    }

    private fun collapseDropdown(dropdown: Dropdown) {
        _dropdownState.value = Collapsing(dropdown)
    }

    private fun isDropdownCollapsed(): Boolean {
        return _dropdownState.value == Collapsed(NONE)
    }
}