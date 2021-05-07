package ba.grbo.currencyconverter.data.models.domain

import ba.grbo.currencyconverter.data.models.shared.Evaluable
import java.util.*

data class ExchangeRate(
    override val value: Double,
    override val date: Date
) : Evaluable